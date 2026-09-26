package com.muse.meomuneum.recommendation.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.recommendation.dto.response.RecommendationHistoryResponse;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.recommendation.service.RecommendationService;

@RestController
@RequestMapping("/api/v1/users/me/recommendations")
public class UserRecommendationController {
    private final RecommendationService service;
    private final CurrentUserResolver users;

    public UserRecommendationController(RecommendationService service, CurrentUserResolver users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<RecommendationHistoryResponse> list(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size, Authentication authentication) {
        Long userId = users.resolve(authentication);
        if (userId == null) {
            throw new RecommendationException(401, "unauthorized");
        }
        return ApiResponse.of("recommendation history retrieved", service.listHistory(userId, cursor, size));
    }
}
