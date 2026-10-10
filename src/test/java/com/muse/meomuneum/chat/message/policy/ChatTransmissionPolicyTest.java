package com.muse.meomuneum.chat.message.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ChatTransmissionPolicyTest {
    @Test
    void limitsByUserAndCountsDuplicatesUntilActualMembershipEnd() {
        MutableClock clock = new MutableClock();
        ChatTransmissionPolicy policy = new ChatTransmissionPolicy(clock,
                new ChatMessagePolicyProperties.Rules(null, true));
        policy.check(1L, 100L, "hello");
        policy.accepted(1L, 100L, "first", "hello", 10L);
        assertEquals(1000, assertThrows(ChatMessageRejection.class,
                () -> policy.check(1L, 200L, "other room")).retryAfterMs());
        policy.check(2L, 101L, "hello");
        clock.advance(1);
        policy.check(1L, 100L, "hello");
        policy.accepted(1L, 100L, "second", "hello", 11L);
        clock.advance(31); // A runtime reconnect/grace expiry does not reset membership counts.
        assertEquals("DUPLICATE_CONTENT", assertThrows(ChatMessageRejection.class,
                () -> policy.check(1L, 100L, "hello")).reason());
        assertEquals(100L, policy.previous(1L, "first").membershipId());
        policy.ended(100L);
        policy.check(1L, 200L, "hello");
        assertEquals(100L, policy.previous(1L, "first").membershipId());
        clock.advance(86400);
        policy.purgeReceipts();
        assertNull(policy.previous(1L, "first"));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T00:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
