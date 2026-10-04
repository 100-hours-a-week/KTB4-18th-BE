package com.muse.meomuneum.recommendation.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.recommendation.dto.SpeechAudio;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;

@Component
public class SpeechAudioProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpeechAudioProcessor.class);
    private static final long MAX_OUTPUT_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PCM_BYTES = 60L * 16_000 * 2;
    private final Semaphore processingSlots = new Semaphore(2);
    private final String executable;
    private final Duration timeout;
    private final AudioMetadataInspector metadataInspector;
    private final Path temporaryDirectory;

    public SpeechAudioProcessor(@Value("${speech.transcription.audio.ffmpeg-path:ffmpeg}") String executable,
            @Value("${speech.transcription.audio.processing-timeout:15s}") Duration timeout,
            AudioMetadataInspector metadataInspector,
            @Value("${speech.transcription.audio.temporary-directory:${java.io.tmpdir}}") String temporaryDirectory) {
        this.executable = executable;
        this.timeout = timeout;
        this.metadataInspector = metadataInspector;
        this.temporaryDirectory = Path.of(temporaryDirectory);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Audio processing timeout must be positive");
        }
    }

    public SpeechAudio process(byte[] content, String mediaType) {
        if (!processingSlots.tryAcquire()) {
            throw new SpeechTranscriptionException(503, "음성 준비 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory(temporaryDirectory, "speech-audio-");
            Path input = directory.resolve("input");
            Path output = directory.resolve("audio.mp4");
            Path decoded = directory.resolve("decoded.pcm");
            Files.write(input, content);
            // Decode the entire input to detect corruption, retaining only its beginning.
            // AAC padding needs a small margin so decoded audio also remains below 60 seconds.
            run(List.of(executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror", "-y",
                    "-threads", "1", "-protocol_whitelist", "file,pipe", "-err_detect", "explode", "-f",
                    "audio/webm".equals(mediaType) ? "matroska" : "mov", "-i", input.toString(), "-map", "0:a:0",
                    "-vn", "-af", "aresample=16000,atrim=end_sample=958400,asetpts=N/SR/TB", "-ac", "1",
                    "-c:a", "aac", "-b:a", "64k", "-threads", "1", "-map_metadata", "-1", "-movflags",
                    "+faststart", output.toString()), true);
            if (!Files.exists(output) || Files.size(output) == 0 || Files.size(output) > MAX_OUTPUT_BYTES) {
                throw preparationFailed();
            }
            byte[] prepared = Files.readAllBytes(output);
            var metadata = metadataInspector.inspect(prepared);
            if (!"audio/mp4".equals(metadata.mediaType()) || metadata.durationSeconds() > 60d) {
                throw preparationFailed();
            }
            run(List.of(executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror", "-y",
                    "-threads", "1", "-protocol_whitelist", "file,pipe", "-i", output.toString(), "-map",
                    "0:a:0", "-ac", "1", "-ar", "16000", "-f", "s16le", "-fs",
                    Long.toString(MAX_PCM_BYTES + 1), decoded.toString()), false);
            long decodedBytes = Files.size(decoded);
            if (decodedBytes == 0 || decodedBytes > MAX_PCM_BYTES || decodedBytes % 2 != 0) {
                throw preparationFailed();
            }
            return new SpeechAudio(prepared, "audio/mp4", "audio.mp4", decodedBytes / 32_000d);
        } catch (IOException exception) {
            throw preparationFailed();
        } finally {
            if (directory != null) {
                clean(directory);
            }
            processingSlots.release();
        }
    }

    private void run(List<String> command, boolean inputValidation) throws IOException {
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new SpeechTranscriptionException(504, "음성 파일 준비 시간이 초과됐습니다. 다시 시도해 주세요.");
            }
            if (process.exitValue() != 0) {
                if (!inputValidation) {
                    throw preparationFailed();
                }
                throw new SpeechTranscriptionException(400,
                        "음성 파일이 손상되었거나 재생 가능한 음성이 없습니다. 다시 녹음해 주세요.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw preparationFailed();
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void clean(Path directory) {
        try {
            Files.deleteIfExists(directory.resolve("input"));
            Files.deleteIfExists(directory.resolve("audio.mp4"));
            Files.deleteIfExists(directory.resolve("decoded.pcm"));
            Files.deleteIfExists(directory);
        } catch (IOException exception) {
            LOGGER.warn("Could not remove temporary speech audio files");
        }
    }

    private SpeechTranscriptionException preparationFailed() {
        return new SpeechTranscriptionException(502, "음성 파일을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
