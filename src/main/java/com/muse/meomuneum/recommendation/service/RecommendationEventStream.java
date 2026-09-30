package com.muse.meomuneum.recommendation.service;

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

@Component
public class RecommendationEventStream {
    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(3).toMillis();
    private static final long RETAIN_STREAM_MILLIS = Duration.ofMinutes(10).toMillis();

    private final Map<Long, StreamState> streams = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "recommendation-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public RecommendationEventStream() {
        heartbeat.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    public void register(long recommendationId) {
        streams.put(recommendationId, new StreamState());
    }

    public SseEmitter connect(long recommendationId) {
        StreamState state = streams.get(recommendationId);
        if (state == null) {
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
        private final List<SseEmitter> emitters = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private Map<String, Object> tracks;
        private StreamEvent terminal;

        synchronized void add(SseEmitter emitter) {
            emitters.add(emitter);
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

        synchronized void text(String delta) {
            text.append(delta);
            emitters.removeIf(emitter -> !send(emitter, "text", Map.of("delta", delta)));
        }

        synchronized void tracks(Map<String, Object> payload) {
            tracks = payload;
            emitters.removeIf(emitter -> !send(emitter, "tracks", payload));
        }

        synchronized void finish(String name, Object payload) {
            terminal = new StreamEvent(name, payload);
            emitters.removeIf(emitter -> {
                send(emitter, name, payload);
                emitter.complete();
                return true;
            });
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
