package com.muse.meomuneum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.muse.meomuneum.auth.exception.LocalMockLoginUnavailableException;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.muse.meomuneum.user.repository.UserRepository;

class LocalMockLoginServiceTest {

    private static final Long TEST_USER_ID = 42L;

    private UserRepository userRepository;
    private JwtTokenProvider jwtTokenProvider;
    private User testUser;
    private LocalMockLoginService localMockLoginService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        testUser = mock(User.class);
        when(testUser.getRole()).thenReturn(UserRole.USER);
        when(userRepository.findByIdAndDeletedAtIsNull(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        when(jwtTokenProvider.createAccessToken(testUser)).thenReturn("local-test-access-token");
        JwtProperties jwtProperties = new JwtProperties("project-api", "project-api",
                "development-only-secret-with-at-least-32-bytes", 3600, 1209600);
        localMockLoginService = new LocalMockLoginService(userRepository, jwtTokenProvider, jwtProperties,
                TEST_USER_ID);
    }

    @Test
    void issuesOnlyStandardAccessTokenForConfiguredActiveUserRole() {
        assertThat(localMockLoginService.issueToken())
                .isEqualTo(new LocalMockLoginService.LocalMockLoginToken("local-test-access-token", 3600));

        verify(userRepository).findByIdAndDeletedAtIsNull(TEST_USER_ID);
        verify(jwtTokenProvider).createAccessToken(testUser);
    }

    @Test
    void rejectsUnavailableOrNonUserConfiguredAccountWithoutDisclosingItsState() {
        when(testUser.getRole()).thenReturn(UserRole.ADMIN);

        assertThatThrownBy(() -> localMockLoginService.issueToken())
                .isInstanceOf(LocalMockLoginUnavailableException.class);
    }

    @Test
    void rejectsMissingConfiguredAccount() {
        when(userRepository.findByIdAndDeletedAtIsNull(TEST_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> localMockLoginService.issueToken())
                .isInstanceOf(LocalMockLoginUnavailableException.class);
    }

    @Test
    void rejectsNonPositiveConfiguredUserIdAtStartup() {
        JwtProperties jwtProperties = new JwtProperties("project-api", "project-api",
                "development-only-secret-with-at-least-32-bytes", 3600, 1209600);

        assertThatThrownBy(() -> new LocalMockLoginService(userRepository, jwtTokenProvider, jwtProperties, 0L))
                .isInstanceOf(IllegalStateException.class);
    }
}
