package com.muse.meomuneum.recommendation.service;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.dto.response.RecommendationResponse;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.provider.RecommendationCommand;
import com.muse.meomuneum.recommendation.provider.RecommendationProvider;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class RecommendationService {
    private static final int MAX_CONTEXT_LENGTH = 200;
    private static final int MAX_HISTORY_COUNT = 10;

    private final RecommendationProvider provider;
    private final RecommendationRepository repository;
    private final MeterRegistry meterRegistry;

    public RecommendationService(RecommendationProvider provider, RecommendationRepository repository,
            MeterRegistry meterRegistry) {
        this.provider = provider;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    public RecommendationResponse create(RecommendationRequest request, String guestSessionId, Long userId) {
        var requestTimer = Timer.start(meterRegistry);
        String outcome = "failure";
        try {
            var prompts = buildProviderPrompts(request, guestSessionId, userId);
            var providerTimer = Timer.start(meterRegistry);
            String providerOutcome = "failure";
            List<TrackData> tracks;
            try {
                var command = new RecommendationCommand(UUID.fromString(request.conversation_key()),
                        UUID.randomUUID(), String.join("\n", prompts));
                tracks = provider.recommend(command);
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
            if (tracks.isEmpty() || tracks.size() > 5
                    || tracks.stream().map(t -> t.provider() + ":" + t.externalId()).distinct().count() != tracks.size()
                    || tracks.stream().map(t -> (t.artistName() + ":" + t.title()).trim().toLowerCase(Locale.ROOT))
                            .distinct().count() != tracks.size()) {
                throw new RecommendationException(503, "조건에 맞는 추천곡을 찾지 못했습니다. 다른 조건으로 다시 요청해 주세요.");
            }
            var result = repository.saveCompleted(request, guestSessionId, userId, tracks);
            outcome = "success";
            return result;
        } catch (RecommendationException exception) {
            if (exception.getStatus() == 504) {
                outcome = "timeout";
            }
            throw exception;
        } finally {
            requestTimer.stop(
                    Timer.builder("recommendation.request.duration").tag("outcome", outcome).register(meterRegistry));
        }
    }

    private List<String> buildProviderPrompts(RecommendationRequest request, String guestSessionId, Long userId) {
        String currentPrompt = takeLast(request.prompt().trim(), MAX_CONTEXT_LENGTH);
        var prompts = new ArrayDeque<String>();
        prompts.addFirst(currentPrompt);

        int remainingLength = MAX_CONTEXT_LENGTH - currentPrompt.length();
        if (remainingLength <= 1) {
            return List.copyOf(prompts);
        }

        var recentPrompts = repository.findRecentPrompts(request.conversation_key(), guestSessionId, userId,
                MAX_HISTORY_COUNT);
        for (String prompt : recentPrompts) {
            String trimmedPrompt = prompt.trim();
            if (trimmedPrompt.isEmpty()) {
                continue;
            }

            int availableLength = remainingLength - 1;
            if (availableLength <= 0) {
                break;
            }

            String includedPrompt = takeLast(trimmedPrompt, availableLength);
            prompts.addFirst(includedPrompt);
            remainingLength -= includedPrompt.length() + 1;
        }
        return List.copyOf(prompts);
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
}
