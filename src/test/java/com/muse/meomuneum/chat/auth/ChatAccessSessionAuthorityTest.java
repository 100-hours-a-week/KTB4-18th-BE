package com.muse.meomuneum.chat.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.global.security.AccessSessionAuthority;
import com.muse.meomuneum.global.security.ActiveUserLookup;
import com.muse.meomuneum.global.security.JwtAuthenticationFilter;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.global.security.TokenClaims;

class ChatAccessSessionAuthorityTest {
    private final ChatSessionVerifier verifier = mock(ChatSessionVerifier.class);
    private final String sid = UUID.randomUUID().toString();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void remoteDecisionPreservesActiveInactiveAndUnavailableMeanings() {
        var authority = new ChatAccessSessionAuthority(verifier);
        assertEquals(AccessSessionAuthority.Decision.ACTIVE, authority.verifySession(1L, sid));
        doThrow(new ChatRoomException(ChatRoomErrorCode.AUTH_REQUIRED)).when(verifier).fresh(1L, sid);
        assertEquals(AccessSessionAuthority.Decision.REJECTED, authority.verifySession(1L, sid));
        doThrow(new ChatAuthUnavailableException()).when(verifier).fresh(1L, sid);
        assertEquals(AccessSessionAuthority.Decision.UNAVAILABLE, authority.verifySession(1L, sid));
    }

    @Test
    void bearerAndOperationFiltersUseOneFreshLookupAndKeepCommitGuard() throws Exception {
        var tokens = mock(JwtTokenProvider.class);
        when(tokens.parseAccessToken("synthetic")).thenReturn(new TokenClaims(1L, List.of("USER"),
                Instant.now().plusSeconds(60), sid, "test-token"));
        var bearer = new JwtAuthenticationFilter(tokens, new SecurityErrorResponseWriter(new ObjectMapper()),
                mock(ActiveUserLookup.class), new ChatAccessSessionAuthority(verifier));
        var operation = new ChatCoreAuthFilter(verifier);
        var request = new MockHttpServletRequest("GET", "/api/v1/regions/1/chat-room");
        request.setServletPath("/api/v1/regions/1/chat-room");
        request.addHeader("Authorization", "Bearer synthetic");
        var response = new MockHttpServletResponse();
        var handled = new AtomicInteger();
        bearer.doFilter(request, response, (req, res) -> operation.doFilter(req, res, (innerReq, innerRes) -> {
            ChatAuthOperation.check();
            handled.incrementAndGet();
        }));
        assertEquals(1, handled.get());
        verify(verifier, times(1)).fresh(1L, sid);
        verify(verifier, times(2)).assertValid(1L, sid);
        ChatAuthOperation.check();
    }

    @Test
    void remoteFailureNeverReachesProtectedRestHandler() throws Exception {
        var tokens = mock(JwtTokenProvider.class);
        when(tokens.parseAccessToken("synthetic")).thenReturn(new TokenClaims(1L, List.of("USER"),
                Instant.now().plusSeconds(60), sid, "test-token"));
        var bearer = new JwtAuthenticationFilter(tokens, new SecurityErrorResponseWriter(new ObjectMapper()),
                mock(ActiveUserLookup.class), new ChatAccessSessionAuthority(verifier));
        var handled = new AtomicInteger();
        for (boolean unavailable : List.of(false, true)) {
            SecurityContextHolder.clearContext();
            RuntimeException failure = unavailable
                    ? new ChatAuthUnavailableException()
                    : new ChatRoomException(ChatRoomErrorCode.AUTH_REQUIRED);
            doThrow(failure).when(verifier).fresh(1L, sid);
            var request = new MockHttpServletRequest("GET", "/api/v1/regions/1/chat-room");
            request.addHeader("Authorization", "Bearer synthetic");
            var response = new MockHttpServletResponse();
            bearer.doFilter(request, response, (req, res) -> handled.incrementAndGet());
            assertEquals(unavailable ? 503 : 401, response.getStatus());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
        assertEquals(0, handled.get());
    }
}
