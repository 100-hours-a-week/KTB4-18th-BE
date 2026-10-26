package com.muse.meomuneum.chat.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.global.security.SessionPair;
import com.muse.meomuneum.global.security.TokenClaims;

public final class ChatCoreAuthFilter extends OncePerRequestFilter {
    private final ChatSessionVerifier verifier;
    private final ObjectMapper json = new ObjectMapper();

    public ChatCoreAuthFilter(ChatSessionVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (request.getServletPath().startsWith("/api/") && authentication != null
                && authentication.getDetails() instanceof TokenClaims claims) {
            response.setHeader("Cache-Control", "no-store");
            try {
                if (claims.expiresAt() == null || !claims.expiresAt().isAfter(java.time.Instant.now())) {
                    reject(response, 401, "chat authentication session required");
                    return;
                }
                verifier.fresh(claims.userId(), claims.sessionId());
                try (var scope = new ChatAuthOperation(verifier,
                        new SessionPair(claims.userId(), claims.sessionId()), claims.expiresAt())) {
                    chain.doFilter(request, response);
                }
            } catch (ChatRoomException inactive) {
                if (inactive.getErrorCode() != com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode.AUTH_REQUIRED) {
                    throw inactive;
                }
                reject(response, 401, "chat authentication session required");
            } catch (ChatAuthUnavailableException unavailable) {
                reject(response, 503, "chat authentication service unavailable");
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        if (!response.isCommitted()) {
            response.resetBuffer();
            response.setStatus(status);
            response.setHeader("Cache-Control", "no-store");
            if (status == 503) {
                response.setHeader("Retry-After", "2");
            }
            response.setContentType("application/json");
            json.writeValue(response.getWriter(), ApiResponse.failure(message));
        }
    }
}
