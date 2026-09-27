package com.muse.meomuneum.recommendation.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.recommendation.dto.SpeechAudio;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "speech.transcription", name = "provider", havingValue = "ai")
public class AiSpeechToTextProvider implements SpeechToTextProvider {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final String authToken;
    private final Duration readTimeout;

    public AiSpeechToTextProvider(ObjectMapper mapper,
            @Value("${speech.transcription.ai.base-url}") String baseUrl,
            @Value("${speech.transcription.ai.auth-token:}") String authToken,
            @Value("${speech.transcription.ai.connect-timeout:3s}") Duration connectTimeout,
            @Value("${speech.transcription.ai.read-timeout:15s}") Duration readTimeout) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/transcriptions");
        this.authToken = authToken;
        this.readTimeout = readTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public String transcribe(SpeechAudio audio) {
        MultipartRequest multipart = multipartRequest(audio);
        var requestBuilder = HttpRequest.newBuilder(endpoint).timeout(readTimeout)
                .header("Content-Type", multipart.contentType()).POST(multipart.bodyPublisher());
        if (!authToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + authToken);
        }

        try {
            var response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw mapError(response.statusCode(), response.body());
            }
            JsonNode transcriptNode = mapper.readTree(response.body()).path("transcript");
            if (!transcriptNode.isTextual() || transcriptNode.asText().isBlank()) {
                throw serviceUnavailable();
            }
            return transcriptNode.asText().trim();
        } catch (HttpTimeoutException exception) {
            throw new SpeechTranscriptionException(504, "음성 변환 시간이 초과됐습니다. 다시 시도해 주세요.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SpeechTranscriptionException(502, "음성 변환 요청이 중단됐습니다. 다시 시도해 주세요.");
        } catch (JacksonException exception) {
            throw serviceUnavailable();
        } catch (IOException exception) {
            throw new SpeechTranscriptionException(502, "음성 변환 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private MultipartRequest multipartRequest(SpeechAudio audio) {
        String boundary = "----MeomuneumBoundary" + UUID.randomUUID();
        String filename = "audio/mp4".equalsIgnoreCase(audio.mediaType()) ? "audio.mp4" : "audio.webm";
        byte[] preamble = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"audio\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + audio.mediaType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] closing = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(preamble),
                HttpRequest.BodyPublishers.ofByteArray(audio.content()),
                HttpRequest.BodyPublishers.ofByteArray(closing));
        return new MultipartRequest("multipart/form-data; boundary=" + boundary, body);
    }

    private SpeechTranscriptionException mapError(int status, String body) {
        String reason = errorReason(body);
        if (status == 413) {
            return new SpeechTranscriptionException(413, "음성 파일은 최대 10MB까지 전송할 수 있습니다.");
        }
        if (status == 400 && "AUDIO_TOO_LONG".equals(reason)) {
            return new SpeechTranscriptionException(400, "음성 녹음 길이가 AI 전사 제한을 초과했습니다. 더 짧게 녹음해 주세요.");
        }
        if (status == 400 || status == 415) {
            return new SpeechTranscriptionException(400, "WebM 또는 MP4 형식의 음성 파일로 다시 녹음해 주세요.");
        }
        return serviceUnavailable();
    }

    private String errorReason(String body) {
        try {
            JsonNode reason = mapper.readTree(body).path("details").path("reason");
            return reason.isTextual() ? reason.asText() : "";
        } catch (JacksonException exception) {
            return "";
        }
    }

    private SpeechTranscriptionException serviceUnavailable() {
        return new SpeechTranscriptionException(502, "음성 변환 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    private record MultipartRequest(String contentType, HttpRequest.BodyPublisher bodyPublisher) {
    }
}
