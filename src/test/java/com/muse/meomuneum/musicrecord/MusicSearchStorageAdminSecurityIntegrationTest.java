package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.musicrecord.controller.MusicSearchStorageAdminController;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService.RequeueResult;
import com.muse.meomuneum.support.BearerSecurityTestSupport;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.muse.meomuneum.user.service.UserAuthenticationService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@WebMvcTest(value = MusicSearchStorageAdminController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes",
        "auth.cors.allowed-origins=http://localhost:5173"
})
@Import({SecurityConfig.class, BearerSecurityTestSupport.class,
        MusicSearchStorageAdminSecurityIntegrationTest.SecurityTestConfiguration.class})
class MusicSearchStorageAdminSecurityIntegrationTest {
    private static final String ORIGIN = "http://localhost:5173";
    @Autowired
    private MockMvc mvc;
    @Autowired
    private MusicSearchIngestJobService jobs;
    @Autowired
    private JwtTokenProvider jwt;

    @BeforeEach
    void setUp() {
        reset(jobs);
    }

    @Test
    void anonymousRequestCannotReachAdminRequeue() throws Exception {
        mvc.perform(post("/api/v1/admin/music-search-storage/7/requeue")
                .header("Origin", ORIGIN).with(csrf()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(jobs);
    }

    @Test
    void userRoleCannotReachAdminRequeue() throws Exception {
        mvc.perform(post("/api/v1/admin/music-search-storage/7/requeue")
                .header("Origin", ORIGIN).header("Authorization", bearer(UserRole.USER)).with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(jobs);
    }

    @Test
    void adminRequestWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/api/v1/admin/music-search-storage/7/requeue")
                .header("Origin", ORIGIN).header("Authorization", bearer(UserRole.ADMIN)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(jobs);
    }

    @Test
    void adminWithCsrfAndAllowedOriginCanRequeue() throws Exception {
        when(jobs.requeue(7L)).thenReturn(new RequeueResult(7L, "QUEUED"));

        mvc.perform(post("/api/v1/admin/music-search-storage/7/requeue")
                .header("Origin", ORIGIN).header("Authorization", bearer(UserRole.ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job_id").value(7))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(jobs).requeue(7L);
    }

    @Test
    void requeueDatabaseFailureReturnsStable503AndDoesNotLogExceptionMessage() throws Exception {
        doThrow(new DataAccessResourceFailureException("private SQL and password detail"))
                .when(jobs).requeue(7L);
        var logger = (Logger) LoggerFactory.getLogger(MusicSearchStorageAdminController.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            mvc.perform(post("/api/v1/admin/music-search-storage/7/requeue")
                    .header("Origin", ORIGIN).header("Authorization", bearer(UserRole.ADMIN)).with(csrf()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("music search storage unavailable"));

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("event=music_search_job_requeue_unavailable",
                        "jobId=7", "exception=DataAccessResourceFailureException");
                assertThat(event.getFormattedMessage()).doesNotContain("private SQL and password detail");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void untrustedOriginIsRejectedBeforeRequeue() throws Exception {
        mvc.perform(post("/api/v1/admin/music-search-storage/7/requeue")
                .header("Origin", "https://untrusted.example")
                .header("Authorization", bearer(UserRole.ADMIN)).with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(jobs);
    }

    private String bearer(UserRole role) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(11L);
        when(user.getRole()).thenReturn(role);
        return "Bearer " + jwt.createAccessToken(user, "550e8400-e29b-41d4-a716-446655440000");
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
        MusicSearchIngestJobService musicSearchIngestJobService() {
            return mock(MusicSearchIngestJobService.class);
        }

        @Bean
        UserAuthenticationService userAuthenticationService() {
            UserAuthenticationService service = mock(UserAuthenticationService.class);
            when(service.findActiveUser(anyLong())).thenReturn(mock(User.class));
            return service;
        }
    }
}
