package com.muse.meomuneum.recommendation.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.ObjectMapper;

@Component
public class RecommendationEventStream {
    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(3).toMillis();
    private static final long RETAIN_STREAM_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final int MAX_IMAGE_EVENTS = 256;
    private static final int MAX_IMAGE_BYTES = 64 * 1024;
    private static final int COMPLETION_RESERVE_BYTES = 48 * 1024;
    private static final int MAX_IMAGE_STREAMS = 32;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<Long, StreamState> streams = new ConcurrentHashMap<>();
    private int imageReservations;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "recommendation-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public RecommendationEventStream() {
        heartbeat.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    public void register(long recommendationId) {
        streams.put(recommendationId, new StreamState(false));
    }

    public synchronized boolean reserveImageCapacity() {
        if (imageStreamCount() + imageReservations >= MAX_IMAGE_STREAMS) {
            return false;
        }
        imageReservations++;
        return true;
    }

    public synchronized void releaseImageCapacity() {
        if (imageReservations > 0) {
            imageReservations--;
        }
    }

    public synchronized void registerImage(long recommendationId) {
        if (imageStreamCount() >= MAX_IMAGE_STREAMS) {
            throw new IllegalStateException("image stream capacity exhausted");
        }
        if (imageReservations > 0) {
            imageReservations--;
        }
        streams.put(recommendationId, new StreamState(true));
    }

    private long imageStreamCount() {
        return streams.values().stream().filter(state -> state.image).count();
    }

    public SseEmitter connect(long recommendationId) {
        return connect(recommendationId, false);
    }

    public SseEmitter connect(long recommendationId, boolean recoveredImageFailure) {
        StreamState state = streams.get(recommendationId);
        if (state == null) {
            if (recoveredImageFailure) {
                SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                            Map.of("detail", "이미지 추천이 중단되어 완료되지 않았어요. 다시 시도해 주세요."),
                            MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (Exception exception) {
                    emitter.completeWithError(exception);
                }
                return emitter;
            }
            throw new IllegalStateException("추천 스트림을 찾을 수 없습니다.");
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        state.add(emitter);
        emitter.onCompletion(() -> state.remove(emitter));
        emitter.onTimeout(() -> state.remove(emitter));
        emitter.onError(exception -> state.remove(emitter));
        return emitter;
    }

    public void text(long recommendationId, String delta) {
        state(recommendationId).text(delta);
    }

    public void tracks(long recommendationId, Map<String, Object> payload) {
        state(recommendationId).tracks(payload);
    }

    public void verdict(long recommendationId, boolean usable) {
        state(recommendationId).imageEvent("verdict", Map.of("usable", usable));
    }

    public void rejected(long recommendationId, String reason, String message) {
        state(recommendationId).imageEvent("rejected", Map.of("reason", reason, "message", message));
    }

    public void ensureImageCompletionFits(long recommendationId, Map<String, Object> tracksPayload) {
        state(recommendationId).ensureCompletionFits(tracksPayload);
    }

    List<String> imageJournalEventNames(long recommendationId) {
        return state(recommendationId).imageJournalEventNames();
    }

    public void done(long recommendationId, Object payload) {
        state(recommendationId).finish("done", payload);
        removeAfterRetention(recommendationId);
    }

    public void error(long recommendationId, String detail) {
        state(recommendationId).finish("error", Map.of("detail", detail));
        removeAfterRetention(recommendationId);
    }

    private StreamState state(long recommendationId) {
        StreamState state = streams.get(recommendationId);
        if (state == null) {
            throw new IllegalStateException("추천 스트림을 찾을 수 없습니다.");
        }
        return state;
    }

    private void removeAfterRetention(long recommendationId) {
        heartbeat.schedule(() -> streams.remove(recommendationId), RETAIN_STREAM_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeats() {
        streams.values().forEach(StreamState::heartbeat);
    }

    @PreDestroy
    void stopHeartbeat() {
        heartbeat.shutdownNow();
    }

    private static final class StreamState {
        private final boolean image;
        private final List<SseEmitter> emitters = new ArrayList<>();
        private final List<StreamEvent> journal = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private Map<String, Object> tracks;
        private StreamEvent terminal;
        private int journalBytes;

        private StreamState(boolean image) {
            this.image = image;
        }

        synchronized void add(SseEmitter emitter) {
            emitters.add(emitter);
            if (image) {
                for (StreamEvent event : journal) {
                    if (!send(emitter, event.name(), event.payload())) {
                        emitters.remove(emitter);
                        return;
                    }
                }
                if (terminal != null) {
                    emitter.complete();
                    emitters.remove(emitter);
                }
                return;
            }
            if (!text.isEmpty()) {
                send(emitter, "text", Map.of("delta", text.toString()));
            }
            if (tracks != null) {
                send(emitter, "tracks", tracks);
            }
            if (terminal != null) {
                send(emitter, terminal.name(), terminal.payload());
                emitter.complete();
                emitters.remove(emitter);
            }
        }

        synchronized void remove(SseEmitter emitter) {
            emitters.remove(emitter);
        }

        synchronized List<String> imageJournalEventNames() {
            return journal.stream().map(StreamEvent::name).toList();
        }

        synchronized void ensureCompletionFits(Map<String, Object> tracksPayload) {
            if (!image || terminal != null) {
                throw new IllegalStateException("image stream is not active");
            }
            int bytes = journalBytes + serializedSize(new StreamEvent("tracks", tracksPayload))
                    + serializedSize(new StreamEvent("done", Map.of("status", "COMPLETED")));
            if (journal.size() + 2 > MAX_IMAGE_EVENTS || bytes > MAX_IMAGE_BYTES) {
                throw new IllegalStateException("image recommendation stream limit exceeded");
            }
        }

        synchronized void text(String delta) {
            if (image) {
                imageEvent("text", Map.of("delta", delta));
                return;
            }
            text.append(delta);
            emitters.removeIf(emitter -> !send(emitter, "text", Map.of("delta", delta)));
        }

        synchronized void tracks(Map<String, Object> payload) {
            if (image) {
                imageEvent("tracks", payload);
                return;
            }
            tracks = payload;
            emitters.removeIf(emitter -> !send(emitter, "tracks", payload));
        }

        synchronized void finish(String name, Object payload) {
            if (image) {
                imageEvent(name, payload);
                return;
            }
            terminal = new StreamEvent(name, payload);
            emitters.removeIf(emitter -> {
                send(emitter, name, payload);
                emitter.complete();
                return true;
            });
        }

        synchronized void imageEvent(String name, Object payload) {
            if (!image || terminal != null) {
                return;
            }
            StreamEvent event = new StreamEvent(name, payload);
            int payloadBytes = serializedSize(event);
            StreamEvent previousText = null;
            if ("text".equals(name) && !journal.isEmpty()
                    && "text".equals(journal.getLast().name())) {
                previousText = journal.removeLast();
                journalBytes -= serializedSize(previousText);
                @SuppressWarnings("unchecked")
                Map<String, Object> previousPayload = (Map<String, Object>) previousText.payload();
                @SuppressWarnings("unchecked")
                Map<String, Object> currentPayload = (Map<String, Object>) payload;
                event = new StreamEvent("text", Map.of("delta",
                        String.valueOf(previousPayload.get("delta")) + currentPayload.get("delta")));
                payloadBytes = serializedSize(event);
            }
            boolean isTerminal = "done".equals(name) || "error".equals(name);
            int eventLimit = isTerminal ? MAX_IMAGE_EVENTS : MAX_IMAGE_EVENTS - 1;
            int byteLimit = isTerminal ? MAX_IMAGE_BYTES : MAX_IMAGE_BYTES - COMPLETION_RESERVE_BYTES;
            if (journal.size() >= eventLimit || journalBytes + payloadBytes > byteLimit) {
                if (previousText != null) {
                    journal.add(previousText);
                    journalBytes += serializedSize(previousText);
                }
                throw new IllegalStateException("image recommendation stream limit exceeded");
            }
            journal.add(event);
            journalBytes += payloadBytes;
            if ("done".equals(name) || "error".equals(name)) {
                terminal = event;
            }
            StreamEvent emitted = event;
            emitters.removeIf(emitter -> !send(emitter, emitted.name(), emitted.payload()));
            if (terminal != null) {
                emitters.forEach(SseEmitter::complete);
                emitters.clear();
            }
        }

        private int serializedSize(StreamEvent event) {
            try {
                return MAPPER.writeValueAsBytes(event.payload()).length + event.name().length();
            } catch (RuntimeException exception) {
                return String.valueOf(event.payload()).getBytes(StandardCharsets.UTF_8).length + event.name().length();
            }
        }

        synchronized void heartbeat() {
            emitters.removeIf(emitter -> {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                    return false;
                } catch (Exception exception) {
                    return true;
                }
            });
        }

        private boolean send(SseEmitter emitter, String event, Object payload) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
                return true;
            } catch (Exception exception) {
                emitter.completeWithError(exception);
                return false;
            }
        }
    }

    private record StreamEvent(String name, Object payload) {
    }
}
