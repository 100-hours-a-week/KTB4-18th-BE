package com.muse.meomuneum.recommendation.service;

import java.time.Instant;
import java.util.Objects;

import com.muse.meomuneum.recommendation.provider.RecommendationProvider;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.dto.response.RecommendationResponse;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationService {
    private final RecommendationProvider provider;
    private final RecommendationRepository repository;

    public RecommendationService(RecommendationProvider provider, RecommendationRepository repository) {
        this.provider = provider;
        this.repository = repository;
    }

    // 세션, 음악, 추천 순서를 모두 저장하거나 실패 시 전부 롤백합니다.
    @Transactional
    public RecommendationResponse create(RecommendationRequest request, String guestSessionId, Long userId) {
        var tracks = provider.recommend(request.prompt().trim());
        if (tracks.size() != 5 || tracks.stream().map(t -> t.provider() + ":" + t.externalId()).distinct().count() != 5) {
            throw new RecommendationException(503, "추천 제공자가 서로 다른 5곡을 반환해야 합니다.");
        }
        long id = repository.createSession(request, userId == null ? guestSessionId : null, userId, Instant.now());
        for (int i = 0; i < tracks.size(); i++) repository.saveItem(id, repository.saveMusic(tracks.get(i)), i + 1);
        repository.complete(id, Instant.now());
        return get(id, guestSessionId, userId);
    }

    @Transactional(readOnly = true)
    public RecommendationResponse get(long id, String guestSessionId, Long userId) {
        var saved = repository.findSession(id);
        boolean ownsResult = saved.userId() != null
                ? Objects.equals(saved.userId(), userId)
                : guestSessionId != null && Objects.equals(saved.guestSessionId(), guestSessionId);
        if (!ownsResult) throw new RecommendationException(403, "이 추천 결과에 접근할 수 없습니다.");
        return new RecommendationResponse(id, saved.status(), saved.conversationKey(),
                repository.findItems(id), saved.completedAt());
    }
}
