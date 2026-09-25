package com.muse.meomuneum.chat.room.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.room.dto.ChatRoomMembershipResponse;
import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;
import com.muse.meomuneum.chat.room.service.ChatRoomJoinResult;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;

@WebMvcTest(value = ChatRoomController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes", "recommendation.allow-guests=false"})
@Import({SecurityConfig.class, ChatRoomSecurityIntegrationTest.SecurityTestConfiguration.class})
class ChatRoomSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ChatRoomEntryService chatRoomEntryService;

    @Test
    void rejectsAnonymousJoinRequests() throws Exception {
        mockMvc.perform(
                post("/api/v1/chat-rooms/700/members").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("unauthorized"));
    }

    @Test
    void acceptsAuthenticatedBearerJoinWithoutCsrfToken() throws Exception {
        when(chatRoomEntryService.join(any(), any(), any())).thenReturn(new ChatRoomJoinResult(
                new ChatRoomMembershipResponse(900L, 700L, 25L, OffsetDateTime.parse("2026-09-25T00:00:00Z")), true));

        mockMvc.perform(post("/api/v1/chat-rooms/700/members")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + createAccessToken())
                .contentType(MediaType.APPLICATION_JSON).content(requestBody())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("chat room joined"));
    }

    private String createAccessToken() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(user.getRole()).thenReturn(UserRole.USER);
        return jwtTokenProvider.createAccessToken(user);
    }

    private String requestBody() {
        return "{\"location_resolution_token\":\"loc_token\"}";
    }

    @TestConfiguration
    static class SecurityTestConfiguration {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return new JwtTokenProvider(new JwtProperties("project-api", "project-api",
                    "development-only-secret-with-at-least-32-bytes", 3600, 1209600));
        }

        @Bean
        SecurityErrorResponseWriter securityErrorResponseWriter(ObjectMapper objectMapper) {
            return new SecurityErrorResponseWriter(objectMapper);
        }

        @Bean
        ChatRoomEntryService chatRoomEntryService() {
            return mock(ChatRoomEntryService.class);
        }
    }
}
