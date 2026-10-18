package com.muse.meomuneum.chat.connection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.global.config.JwtProperties;

class ChatSessionAccessPolicyTest {
    @Test
    void logoutOnlyBlocksTheMatchingUserAndSession() {
        var jwt = mock(JwtProperties.class);
        when(jwt.accessTokenExpirationSeconds()).thenReturn(900L);
        var policy = new ChatSessionAccessPolicy(Clock.systemUTC(), jwt);
        String sid = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        policy.revoke(1L, sid);
        policy.revoke(1L, sid);
        assertThrows(ChatRoomException.class, () -> policy.authorize(1L, sid));
        assertDoesNotThrow(() -> policy.authorize(2L, sid));
        assertDoesNotThrow(() -> policy.authorize(1L, other));
        assertThrows(ChatRoomException.class, () -> policy.authorize(1L, null));
    }

    @Test
    void revocationRemainsUntilAllPreviouslyIssuedAccessTokensExpire() {
        var jwt = mock(JwtProperties.class);
        when(jwt.accessTokenExpirationSeconds()).thenReturn(900L);
        var clock = mock(Clock.class);
        Instant now = Instant.parse("2026-10-18T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        var policy = new ChatSessionAccessPolicy(clock, jwt);
        String sid = UUID.randomUUID().toString();
        policy.revoke(1L, sid);
        when(clock.instant()).thenReturn(now.plusSeconds(959));
        policy.purgeExpiredRevocations();
        assertThrows(ChatRoomException.class, () -> policy.authorize(1L, sid));
        when(clock.instant()).thenReturn(now.plusSeconds(960));
        policy.purgeExpiredRevocations();
        assertDoesNotThrow(() -> policy.authorize(1L, sid));
    }

    @Test
    void logoutNotificationRequiresAnExplicitUserAndSession() {
        assertThrows(IllegalArgumentException.class, () -> new ChatSessionLogoutEvent(1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ChatSessionLogoutEvent(null, UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class, () -> new ChatSessionLogoutEvent(1L, "invalid"));
    }
}
