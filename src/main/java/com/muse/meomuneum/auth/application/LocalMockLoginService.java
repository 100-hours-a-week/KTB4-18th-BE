package com.muse.meomuneum.auth.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.exception.LocalMockLoginUnavailableException;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.muse.meomuneum.user.repository.UserRepository;

@Service
@Profile("local-mock & !prod")
@ConditionalOnProperty(prefix = "auth.local-mock-login", name = "enabled", havingValue = "true", matchIfMissing = false)
public class LocalMockLoginService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final Long configuredUserId;
    private final long accessTokenExpirationSeconds;

    public LocalMockLoginService(UserRepository userRepository, JwtTokenProvider jwtTokenProvider,
            JwtProperties jwtProperties,
            @Value("${auth.local-mock-login.user-id}") Long configuredUserId) {
        if (configuredUserId == null || configuredUserId <= 0) {
            throw new IllegalStateException("AUTH_LOCAL_MOCK_USER_ID must be a positive test user ID");
        }
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.configuredUserId = configuredUserId;
        this.accessTokenExpirationSeconds = jwtProperties.accessTokenExpirationSeconds();
    }

    public LocalMockLoginToken issueToken() {
        User user = userRepository.findByIdAndDeletedAtIsNull(configuredUserId)
                .filter(activeUser -> activeUser.getRole() == UserRole.USER)
                .orElseThrow(LocalMockLoginUnavailableException::new);
        return new LocalMockLoginToken(jwtTokenProvider.createAccessToken(user), accessTokenExpirationSeconds);
    }

    public record LocalMockLoginToken(String accessToken, long expiresIn) {
    }
}
