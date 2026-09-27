package com.muse.meomuneum.recommendation.resolver;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.recommendation.exception.RecommendationException;

/** 로그인 연동 지점. 클라이언트가 보내는 user_id는 신뢰하지 않습니다. */
@Component
public class CurrentUserResolver {
    public Long resolve(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        if (authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        throw new RecommendationException(503, "로그인 사용자 연결을 확인할 수 없습니다.");
    }
}
