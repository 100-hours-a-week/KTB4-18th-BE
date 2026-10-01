package com.muse.meomuneum.musicrecord;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.musicrecord.controller.MusicRecordController;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.resolver.CurrentUserResolver;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@WebMvcTest(value = MusicRecordController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes"})
@Import({SecurityConfig.class, CurrentUserResolver.class,
        MusicRecordDeleteSecurityIntegrationTest.SecurityTestConfiguration.class})
class MusicRecordDeleteSecurityIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private MusicRecordService service;
    @Autowired
    private JwtTokenProvider jwt;

    @BeforeEach
    void setUp() {
        reset(service);
    }

    @Test
    void anonymousDeleteIsUnauthorizedAndDoesNotReachService() throws Exception {
        mvc.perform(delete("/api/v1/music-records/7").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("unauthorized"));
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedDeleteUsesTheTokenOwnerAndReturns204() throws Exception {
        mvc.perform(delete("/api/v1/music-records/7")
                .header("Origin", "http://localhost:5173")
                .header("Authorization", bearer(11L)))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).delete(11L, 7L);
    }

    @Test
    void forbiddenOwnerResponseKeepsTheSharedErrorContract() throws Exception {
        doThrow(new MusicRecordException("music_record_not_owned", HttpStatus.FORBIDDEN, "forbidden"))
                .when(service).delete(12L, 7L);
        mvc.perform(delete("/api/v1/music-records/7")
                .header("Origin", "http://localhost:5173")
                .header("Authorization", bearer(12L)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("forbidden"));
    }

    @Test
    void untrustedOriginIsRejectedBeforeDeletion() throws Exception {
        mvc.perform(delete("/api/v1/music-records/7")
                .header("Origin", "https://untrusted.example")
                .header("Authorization", bearer(11L))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private String bearer(long userId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getRole()).thenReturn(UserRole.USER);
        return "Bearer " + jwt.createAccessToken(user);
    }

    @TestConfiguration
    static class SecurityTestConfiguration {
        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return new JwtTokenProvider(new JwtProperties("project-api", "project-api",
                    "development-only-secret-with-at-least-32-bytes", 3600, 1209600));
        }

        @Bean
        SecurityErrorResponseWriter securityErrorResponseWriter(ObjectMapper mapper) {
            return new SecurityErrorResponseWriter(mapper);
        }

        @Bean
        MusicRecordService musicRecordService() {
            return mock(MusicRecordService.class);
        }

        @Bean
        UserAuthenticationService userAuthenticationService() {
            UserAuthenticationService service = mock(UserAuthenticationService.class);
            when(service.findActiveUser(anyLong())).thenReturn(mock(User.class));
            return service;
        }
    }
}
