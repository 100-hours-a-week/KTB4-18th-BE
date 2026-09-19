package com.muse.meomuneum.recommendation.controller;

import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.dto.response.RecommendationResponse;
import com.muse.meomuneum.recommendation.service.RecommendationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {
    private final RecommendationService service;
    private final CurrentUserResolver users;

    public RecommendationController(RecommendationService service, CurrentUserResolver users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RecommendationResponse>> create(@Valid @RequestBody RecommendationRequest body,
                                                                      HttpServletRequest request, Authentication authentication) {
        var result = service.create(body, request.getSession().getId(), users.resolve(authentication));
        // 샘플은 즉시 완료됩니다. 실제 비동기 AI 연동 시 PROCESSING 반환 및 작업 분리를 구현합니다.
        return ResponseEntity.accepted().body(new ApiResponse<>("recommendation completed", result));
    }

    @GetMapping("/{recommendation_id}")
    public ApiResponse<RecommendationResponse> get(@PathVariable("recommendation_id") long recommendationId, HttpServletRequest request,
            Authentication authentication) {
        var session = request.getSession(false);
        return new ApiResponse<>("recommendation retrieved",
                service.get(recommendationId, session == null ? null : session.getId(), users.resolve(authentication)));
    }

    public record ApiResponse<T>(String message, T data) {}
}
