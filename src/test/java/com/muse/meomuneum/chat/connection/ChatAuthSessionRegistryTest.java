package com.muse.meomuneum.chat.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;

class ChatAuthSessionRegistryTest {
    private static final String FIRST = "550e8400-e29b-41d4-a716-446655440000";
    private static final String SECOND = "550e8400-e29b-41d4-a716-446655440001";
    private final ChatAuthSessionRegistry registry = new ChatAuthSessionRegistry();

    @Test
    void signedAccessTokenRotationKeepsSessionWhileAnotherLoginHasAnotherSession() {
        var tokens = new JwtTokenProvider(new JwtProperties("test", "test",
                "test-only-auth-session-secret-at-least-32-bytes", 3600, 86400));
        User user = org.springframework.beans.BeanUtils.instantiateClass(User.class);
        ReflectionTestUtils.setField(user, "id", 7L);
        ReflectionTestUtils.setField(user, "role", UserRole.USER);
        String first = tokens.createAccessToken(user, FIRST);
        String rotated = tokens.createAccessToken(user, FIRST);
        var original = registry.resolve(tokens.parseAccessToken(first), FIRST);
        assertEquals(original, registry.resolve(tokens.parseAccessToken(rotated), FIRST));
        assertThrows(IllegalArgumentException.class,
                () -> registry.resolve(tokens.parseAccessToken(tokens.createAccessToken(user, SECOND)), FIRST));
        var wrongSigner = new JwtTokenProvider(new JwtProperties("test", "test",
                "another-test-only-secret-at-least-32-bytes", 3600, 86400));
        assertThrows(RuntimeException.class, () -> tokens.parseAccessToken(wrongSigner.createAccessToken(user, FIRST)));
    }

    @Test
    void groupsTabsBySignedSessionWithoutCombiningOtherSessionsOrUsers() {
        var first = registry.resolve(claims(7L, FIRST), FIRST);
        registry.register("tab-a", first);
        registry.register("tab-b", registry.resolve(claims(7L, FIRST), null));
        registry.register("device-b", registry.resolve(claims(7L, SECOND), SECOND));
        registry.register("other-user", registry.resolve(claims(8L, FIRST), null));
        assertEquals(Set.of("tab-a", "tab-b"), registry.connections(7L, FIRST));
        assertEquals(Set.of("device-b"), registry.connections(7L, SECOND));
        registry.remove("tab-a");
        registry.remove("tab-a");
        assertTrue(registry.context("tab-a").isEmpty());
        assertEquals(Set.of("tab-b"), registry.connections(7L, FIRST));
        assertEquals(Set.of("other-user"), registry.connections(8L, FIRST));
    }

    @Test
    void rejectsMissingMalformedOrSpoofedSessionAndDuplicateConnectionRegistration() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(claims(7L, null), FIRST));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(claims(7L, "device"), null));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(claims(7L, FIRST), SECOND));
        registry.register("tab", registry.resolve(claims(7L, FIRST), null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("tab", registry.resolve(claims(7L, SECOND), null)));
        assertEquals(FIRST, registry.context("tab").orElseThrow().authSessionId());
    }

    private TokenClaims claims(Long user, String sid) {
        return new TokenClaims(user, List.of("USER"), Instant.now().plusSeconds(60), sid, "test-token");
    }
}
