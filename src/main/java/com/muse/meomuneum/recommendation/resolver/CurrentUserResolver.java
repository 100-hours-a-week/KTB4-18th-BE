package com.muse.meomuneum.recommendation.resolver;

import com.muse.meomuneum.recommendation.exception.RecommendationException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** 로그인 연동 지점. 클라이언트가 보내는 user_id는 신뢰하지 않습니다. */
@Component
public class CurrentUserResolver {
    public Long resolve(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) return null;
        // 로그인 구현 후 검증된 Authentication의 principal에서 실제 users.id를 반환하세요.
        throw new RecommendationException(503, "로그인 사용자 연결이 아직 구현되지 않았습니다.");
    }
}
