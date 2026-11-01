package com.muse.meomuneum.chat.message.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.chat.connection.ChatMessageOutboundGuard;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ChatDeliveryMetricsTest {
    @Test
    void nestedHandlerCallbacksReleaseTimingStateAndKeepFixedMetricDimensions() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ChatDeliveryMetrics(registry);
        var presence = new ChatPresenceRegistry(Clock.systemUTC());
        presence.setDeliveryMetrics(metrics);
        var guard = new ChatMessageOutboundGuard(presence);
        guard.setDeliveryMetrics(metrics);
        var headers = org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(
                org.springframework.messaging.simp.SimpMessageType.MESSAGE);
        var message = org.springframework.messaging.support.MessageBuilder.createMessage(new byte[0],
                headers.getMessageHeaders());
        assertThat(guard.beforeHandle(message, null, null)).isSameAs(message);
        assertThat(guard.beforeHandle(message, null, null)).isSameAs(message);
        var connected = org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(
                org.springframework.messaging.simp.SimpMessageType.CONNECT_ACK);
        guard.afterMessageHandled(org.springframework.messaging.support.MessageBuilder.createMessage(new byte[0],
                connected.getMessageHeaders()), null, null, null);
        guard.afterMessageHandled(message, null, null, new IllegalStateException("secret-canary"));
        guard.afterMessageHandled(message, null, null, null);
        guard.afterMessageHandled(message, null, null, null);
        assertThat(registry.get("chat.delivery.stage.duration").tag("stage", "outbound_handler").timer().count())
                .isEqualTo(2);
        assertThrows(IllegalArgumentException.class, () -> presence.exclusive(() -> {
            throw new IllegalArgumentException("synthetic");
        }));
        assertThat(registry.get("chat.delivery.stage.duration").tag("stage", "presence_transition_hold")
                .timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("stage"));
            assertThat(meter.getId().toString()).doesNotContain("secret-canary", "synthetic");
        });
    }
}
