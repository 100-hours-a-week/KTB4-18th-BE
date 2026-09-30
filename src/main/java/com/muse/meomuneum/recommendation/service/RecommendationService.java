package com.muse.meomuneum.recommendation.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.dto.response.RecommendationAcceptedResponse;
import com.muse.meomuneum.recommendation.dto.response.RecommendationHistoryResponse;
import com.muse.meomuneum.recommendation.dto.response.RecommendationResponse;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.provider.RecommendationCommand;
import com.muse.meomuneum.recommendation.provider.RecommendationProvider;
import com.muse.meomuneum.recommendation.provider.RecommendationStreamListener;
import com.muse.meomuneum.recommendation.provider.StreamingRecommendationProvider;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class RecommendationService {
    private static final int MAX_AI_MESSAGE_LENGTH = 200;
    private static final int HISTORY_PAGE_SIZE = 20;

    private final RecommendationProvider provider;
    private final RecommendationRepository repository;
    private final MeterRegistry meterRegistry;
    private final RecommendationEventStream eventStream;
    private final Executor recommendationTaskExecutor;

    public RecommendationService(RecommendationProvider provider, RecommendationRepository repository,
            MeterRegistry meterRegistry, RecommendationEventStream eventStream,
            @Qualifier("recommendationTaskExecutor") Executor recommendationTaskExecutor) {
        this.provider = provider;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.eventStream = eventStream;
        this.recommendationTaskExecutor = recommendationTaskExecutor;
    }

    public RecommendationAcceptedResponse accept(RecommendationRequest request, String guestSessionId, Long userId) {
        long recommendationId = repository.createSession(request, userId == null ? guestSessionId : null,
                userId, Instant.now());
        eventStream.register(recommendationId);
        try {
            recommendationTaskExecutor.execute(() -> process(recommendationId, request, guestSessionId, userId));
        } catch (RejectedExecutionException exception) {
            fail(recommendationId, "FAILED", "추천 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        return new RecommendationAcceptedResponse(recommendationId, "PROCESSING", request.conversation_key());
    }

    private void process(long recommendationId, RecommendationRequest request, String guestSessionId, Long userId) {
        var requestTimer = Timer.start(meterRegistry);
        String outcome = "failure";
        try {
            var providerTimer = Timer.start(meterRegistry);
            String providerOutcome = "failure";
            List<TrackData> tracks;
            try {
                var command = new RecommendationCommand(UUID.fromString(request.conversation_key()),
                        UUID.randomUUID(), takeLast(request.prompt().trim(), MAX_AI_MESSAGE_LENGTH));
                RecommendationStreamListener listener = new RecommendationStreamListener() {
                    @Override
                    public void onText(String delta) {
                        eventStream.text(recommendationId, delta);
                    }

                    @Override
                    public void onTracks(List<TrackData> values) {
                        validateTracks(values);
                        eventStream.tracks(recommendationId, streamPayload(values));
                    }
                };
                if (provider instanceof StreamingRecommendationProvider streamingProvider) {
                    tracks = streamingProvider.recommend(command, listener);
                } else {
                    tracks = provider.recommend(command);
                    listener.onTracks(tracks);
                }
                providerOutcome = "success";
            } catch (RecommendationException exception) {
                if (exception.getStatus() == 504) {
                    providerOutcome = "timeout";
                }
                throw exception;
            } finally {
                providerTimer.stop(Timer.builder("recommendation.provider.duration").tag("provider", providerName())
                        .tag("outcome", providerOutcome).register(meterRegistry));
            }
            validateTracks(tracks);
            var result = repository.completeSession(recommendationId, request, tracks);
            outcome = "success";
            eventStream.done(recommendationId, result);
        } catch (RecommendationException exception) {
            if (exception.getStatus() == 504) {
                outcome = "timeout";
            }
            fail(recommendationId, exception.getStatus() == 504 ? "TIMEOUT" : "FAILED",
                    exception.getStatus() == 504
                            ? "AI 추천 시간이 초과됐어요. 잠시 후 다시 시도해 주세요."
                            : "AI 추천 결과를 처리하지 못했어요. 다시 시도해 주세요.");
        } catch (RuntimeException exception) {
            fail(recommendationId, "FAILED", "AI 추천 중 오류가 발생했어요. 다시 시도해 주세요.");
        } finally {
            requestTimer.stop(
                    Timer.builder("recommendation.request.duration").tag("outcome", outcome).register(meterRegistry));
        }
    }

    private void fail(long recommendationId, String status, String detail) {
        try {
            repository.fail(recommendationId, status, Instant.now());
        } finally {
            eventStream.error(recommendationId, detail);
        }
    }

    private void validateTracks(List<TrackData> tracks) {
        if (tracks == null || tracks.size() > 5
                || tracks.stream().map(track -> track.provider() + ":" + track.externalId()).distinct()
                        .count() != tracks.size()
                || tracks.stream().map(track -> (track.artistName() + ":" + track.title()).trim()
                        .toLowerCase(Locale.ROOT)).distinct().count() != tracks.size()) {
            throw new RecommendationException(502, "AI 추천 서비스의 곡 목록을 처리하지 못했습니다.");
        }
    }

    private Map<String, Object> streamPayload(List<TrackData> tracks) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (TrackData track : tracks) {
            var item = new LinkedHashMap<String, Object>();
            item.put("track_id", track.externalId());
            item.put("title", track.title());
            item.put("artist", track.artistName());
            item.put("artwork_url", track.coverUrl());
            item.put("preview_url", track.previewUrl());
            item.put("store_url", track.storeUrl());
            item.put("reason", track.reason());
            items.add(item);
        }
        return Map.of("tracks", List.copyOf(items));
    }

    private String takeLast(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(value.length() - maxLength);
    }

    private String providerName() {
        String name = provider.providerName();
        return name == null || name.isBlank() ? "unknown" : name;
    }

    @Transactional(readOnly = true)
    public RecommendationResponse get(long id, String guestSessionId, Long userId) {
        var saved = repository.findSession(id);
        boolean ownsResult = saved.userId() != null
                ? Objects.equals(saved.userId(), userId)
                : guestSessionId != null && Objects.equals(saved.guestSessionId(), guestSessionId);
        if (!ownsResult) {
            throw new RecommendationException(403, "이 추천 결과에 접근할 수 없습니다.");
        }
        return new RecommendationResponse(id, saved.status(), saved.conversationKey(), repository.findItems(id),
                saved.completedAt());
    }

    @Transactional(readOnly = true)
    public SseEmitter stream(long id, String guestSessionId, Long userId) {
        var saved = repository.findSession(id);
        if (!owns(saved, guestSessionId, userId)) {
            throw new RecommendationException(403, "이 추천 결과에 접근할 수 없습니다.");
        }
        return eventStream.connect(id);
    }

    private boolean owns(RecommendationRepository.SavedSession saved, String guestSessionId, Long userId) {
        return saved.userId() != null
                ? Objects.equals(saved.userId(), userId)
                : guestSessionId != null && Objects.equals(saved.guestSessionId(), guestSessionId);
    }

    @Transactional(readOnly = true)
    public RecommendationHistoryResponse listHistory(long userId, String cursor, Integer size) {
        int pageSize = size == null ? HISTORY_PAGE_SIZE : Math.min(Math.max(size, 1), HISTORY_PAGE_SIZE);
        HistoryCursor position = decodeHistoryCursor(cursor);
        List<RecommendationRepository.HistorySession> rows = repository.findCompletedSessions(userId,
                position.completedAt(), position.id(), pageSize + 1);
        boolean hasNext = rows.size() > pageSize;
        List<RecommendationRepository.HistorySession> sessions = hasNext
                ? List.copyOf(rows.subList(0, pageSize))
                : List.copyOf(rows);
        Map<Long, List<RecommendationHistoryResponse.Item>> itemsBySession = itemsBySession(sessions);
        Map<String, List<RecommendationHistoryResponse.Recommendation>> byDate = new LinkedHashMap<>();
        for (RecommendationRepository.HistorySession session : sessions) {
            String date = session.completedAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
            byDate.computeIfAbsent(date, ignored -> new java.util.ArrayList<>()).add(
                    new RecommendationHistoryResponse.Recommendation(session.id(), session.status(),
                            itemsBySession.getOrDefault(session.id(), List.of())));
        }
        List<RecommendationHistoryResponse.Group> groups = byDate.entrySet().stream()
                .map(entry -> new RecommendationHistoryResponse.Group(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        String nextCursor = hasNext ? encodeHistoryCursor(sessions.getLast()) : null;
        return new RecommendationHistoryResponse(groups, nextCursor, hasNext);
    }

    private Map<Long, List<RecommendationHistoryResponse.Item>> itemsBySession(
            List<RecommendationRepository.HistorySession> sessions) {
        List<Long> ids = sessions.stream().map(RecommendationRepository.HistorySession::id).toList();
        Map<Long, List<RecommendationHistoryResponse.Item>> result = new LinkedHashMap<>();
        for (RecommendationRepository.HistoryItem item : repository.findHistoryItems(ids)) {
            result.computeIfAbsent(item.recommendationId(), ignored -> new java.util.ArrayList<>())
                    .add(new RecommendationHistoryResponse.Item(item.rankNo(), item.musicId(), item.title(),
                            item.artistName()));
        }
        result.replaceAll((id, items) -> List.copyOf(items));
        return result;
    }

    private String encodeHistoryCursor(RecommendationRepository.HistorySession session) {
        String value = session.completedAt() + ":" + session.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private HistoryCursor decodeHistoryCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new HistoryCursor(null, Long.MAX_VALUE);
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":");
            if (parts.length != 4) {
                throw new IllegalArgumentException("invalid cursor");
            }
            long id = Long.parseLong(parts[3]);
            if (id <= 0) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new HistoryCursor(Instant.parse(parts[0] + ":" + parts[1] + ":" + parts[2]), id);
        } catch (Exception exception) {
            throw new RecommendationException(400, "invalid cursor");
        }
    }

    private record HistoryCursor(Instant completedAt, long id) {
    }
}
