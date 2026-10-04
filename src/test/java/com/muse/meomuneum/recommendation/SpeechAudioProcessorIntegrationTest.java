package com.muse.meomuneum.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.recommendation.controller.SpeechTranscriptionController;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionExceptionHandler;
import com.muse.meomuneum.recommendation.media.AudioMetadataInspector;
import com.muse.meomuneum.recommendation.media.SpeechAudioProcessor;
import com.muse.meomuneum.recommendation.service.SpeechTranscriptionService;

class SpeechAudioProcessorIntegrationTest {
    @TempDir
    Path workspace;
    private final AudioMetadataInspector inspector = new AudioMetadataInspector();
    private final String ffmpeg = System.getenv().getOrDefault("SPEECH_AUDIO_FFMPEG_PATH", "ffmpeg");

    @ParameterizedTest
    @CsvSource({"webm,59", "webm,60", "webm,60.1", "webm,70", "mp4,59", "mp4,60", "mp4,60.001", "mp4,70"})
    void preparesRealAudioWithinTheAiLimit(String format, double duration) throws Exception {
        byte[] original = generate(format, duration);
        Path temporaryRoot = Files.createDirectory(workspace.resolve("processing"));
        var processor = new SpeechAudioProcessor(ffmpeg, Duration.ofSeconds(15), inspector, temporaryRoot.toString());
        AtomicInteger calls = new AtomicInteger();
        var service = new SpeechTranscriptionService(inspector, prepared -> {
            calls.incrementAndGet();
            assertEquals("audio/mp4", prepared.mediaType());
            assertTrue(prepared.content().length < 10 * 1024 * 1024);
            assertFalse(Arrays.equals(original, prepared.content()));
            var metadata = inspector.inspect(prepared.content());
            assertTrue(metadata.durationSeconds() <= 60d);
            assertTrue(prepared.durationSeconds() <= 60d);
            assertTrue(prepared.durationSeconds() > Math.min(duration, 59.9d) - 0.1d);
            try {
                Path output = workspace.resolve("prepared.mp4");
                Path decoded = workspace.resolve("verified.pcm");
                Files.write(output, prepared.content());
                execute(ffmpeg, "-v", "error", "-xerror", "-i", output.toString(), "-ac", "1", "-ar", "16000",
                        "-f", "s16le", decoded.toString());
                assertEquals(prepared.durationSeconds(), Files.size(decoded) / 32_000d, 0.001d);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            return "테스트 전사 결과";
        }, processor);
        var mvc = MockMvcBuilders.standaloneSetup(new SpeechTranscriptionController(service))
                .setControllerAdvice(new SpeechTranscriptionExceptionHandler()).build();
        var upload = new MockMultipartFile("audio", "voice." + format, "audio/" + format, original);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/v1/speech-transcriptions")
                .file(upload))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.transcript")
                                .value("테스트 전사 결과"));
        assertEquals(1, calls.get());
        assertClean(temporaryRoot);
    }

    @Test
    void rejectsUndecodableAudioAndCleansTemporaryFiles() throws Exception {
        Path temporaryRoot = Files.createDirectory(workspace.resolve("processing"));
        var processor = new SpeechAudioProcessor(ffmpeg, Duration.ofSeconds(15), inspector, temporaryRoot.toString());
        var failure = assertThrows(SpeechTranscriptionException.class,
                () -> processor.process(AudioMetadataInspectorTests.mp4(70_000), "audio/mp4"));
        assertEquals(400, failure.getStatus());
        assertClean(temporaryRoot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"encoding-failure", "invalid-output"})
    void returnsBadGatewayForServerFailureAfterValidInputAndDoesNotCallAi(String failureMode) throws Exception {
        byte[] original = generate("webm", 1);
        Path temporaryRoot = Files.createDirectory(workspace.resolve("processing"));
        Path wrapper = workspace.resolve("ffmpeg-wrapper");
        String encodingFailure = "encoding-failure".equals(failureMode)
                ? "exit 73"
                : "printf 'broken server output' > \"$argument\"; exit 0";
        Files.writeString(wrapper, "#!/bin/sh\n"
                + "for argument in \"$@\"; do\n"
                + "  case \"$argument\" in\n"
                + "    */audio.mp4) " + encodingFailure + ";;\n"
                + "  esac\n"
                + "done\n"
                + "exec '" + ffmpeg.replace("'", "'\\''") + "' \"$@\"\n");
        Files.setPosixFilePermissions(wrapper, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        var processor = new SpeechAudioProcessor(wrapper.toString(), Duration.ofSeconds(15), inspector,
                temporaryRoot.toString());
        AtomicInteger calls = new AtomicInteger();
        var service = new SpeechTranscriptionService(inspector, prepared -> {
            calls.incrementAndGet();
            return "사용되지 않는 전사";
        }, processor);
        var mvc = MockMvcBuilders.standaloneSetup(new SpeechTranscriptionController(service))
                .setControllerAdvice(new SpeechTranscriptionExceptionHandler()).build();
        var upload = new MockMultipartFile("audio", "voice.webm", "audio/webm", original);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/v1/speech-transcriptions").file(upload))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadGateway())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("음성 파일을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요."));
        assertEquals(0, calls.get());
        assertClean(temporaryRoot);
    }

    @Test
    void missingExecutableReturnsPreparationErrorAndCleansTemporaryFiles() throws Exception {
        Path temporaryRoot = Files.createDirectory(workspace.resolve("processing"));
        var processor = new SpeechAudioProcessor(workspace.resolve("missing-ffmpeg").toString(),
                Duration.ofSeconds(15), inspector, temporaryRoot.toString());
        var failure = assertThrows(SpeechTranscriptionException.class,
                () -> processor.process(new byte[]{1}, "audio/webm"));
        assertEquals(502, failure.getStatus());
        assertClean(temporaryRoot);
    }

    @Test
    void timeoutKillsProcessingAndCleansTemporaryFiles() throws Exception {
        byte[] original = generate("webm", 70);
        Path temporaryRoot = Files.createDirectory(workspace.resolve("processing"));
        var processor = new SpeechAudioProcessor(ffmpeg, Duration.ofMillis(1), inspector, temporaryRoot.toString());
        var failure = assertThrows(SpeechTranscriptionException.class,
                () -> processor.process(original, "audio/webm"));
        assertEquals(504, failure.getStatus());
        assertClean(temporaryRoot);
    }

    private byte[] generate(String format, double duration) throws Exception {
        Path output = workspace.resolve("input." + format);
        execute(ffmpeg, "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000",
                "-t", Double.toString(duration), "-c:a", "webm".equals(format) ? "libopus" : "aac", "-b:a", "64k",
                output.toString());
        return Files.readAllBytes(output);
    }

    private void assertClean(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    private void execute(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }
}
