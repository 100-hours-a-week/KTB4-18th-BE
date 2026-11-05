package com.muse.meomuneum.musicrecord;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.musicrecord.controller.SongController;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.Request;
import com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.Response;
import com.muse.meomuneum.musicrecord.resolver.CurrentUserResolver;
import com.muse.meomuneum.musicrecord.service.SongRegistrationService;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@WebMvcTest(value = SongController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes"})
@Import({SecurityConfig.class, CurrentUserResolver.class,
        SongRegistrationSecurityIntegrationTest.SecurityTestConfiguration.class})
class SongRegistrationSecurityIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private SongRegistrationService service;
    @Autowired
    private JwtTokenProvider jwt;

    @BeforeEach
    void setup() {
        reset(service);
    }

    @Test
    void validJwtCsrfAndOriginAllowRegistration() throws Exception {
        when(service.register(new Request("123"))).thenReturn(new Response(new MusicItem(42L, "ITUNES", "123",
                "song", "artist", null, null, null, false), true));
        mvc.perform(post("/api/v1/songs").with(csrf().asHeader())
                .header("Origin", "http://localhost:5173").header("Authorization", bearer())
                .contentType("application/json").content("{\"externalTrackId\":\"123\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void anonymousMissingCsrfAndUntrustedOriginNeverReachRegistration() throws Exception {
        mvc.perform(post("/api/v1/songs").with(csrf().asHeader()).header("Origin", "http://localhost:5173")
                .contentType("application/json").content("{\"externalTrackId\":\"123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/songs").header("Origin", "http://localhost:5173")
                .header("Authorization", bearer()).contentType("application/json")
                .content("{\"externalTrackId\":\"123\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/songs").with(csrf().asHeader()).header("Origin", "https://untrusted.example")
                .header("Authorization", bearer()).contentType("application/json")
                .content("{\"externalTrackId\":\"123\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private String bearer() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(11L);
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
        SongRegistrationService songRegistrationService() {
            return mock(SongRegistrationService.class);
        }
        @Bean
        UserAuthenticationService userAuthenticationService() {
            var service = mock(UserAuthenticationService.class);
            when(service.findActiveUser(anyLong())).thenReturn(mock(User.class));
            return service;
        }
    }
}
