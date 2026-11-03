package com.muse.meomuneum.chat.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.auth.ChatCoreSessionVerifier;
import com.muse.meomuneum.chat.message.relay.ChatMessageRelay;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.global.web.ApplicationBoundaryFilter;

class ChatRuntimeAvailabilityTest {
    @Test
    void productionNeedsEveryImplementationEvenWhenFlagsClaimItIsEnabled() {
        for (String missing : List.of("lifecycle", "verifier", "participation", "redis", "relay")) {
            try (var fixture = new Fixture("prod", missing)) {
                assertEquals("DOWN", fixture.availability.health().getStatus().getCode(), missing);
                assertFalse(fixture.availability.accepting(), missing);
            }
        }
    }

    @Test
    void explicitlyEnabledDependenciesCannotBeAbsentInIsolatedTests() {
        List<String> names = List.of("lifecycle", "verifier", "participation", "redis", "relay");
        List<String> flags = List.of("chat.lifecycle.enabled", "chat.core-auth.enabled",
                "chat.distributed-presence.enabled", "chat.redis.enabled", "chat.message-relay.enabled");
        for (int i = 0; i < names.size(); i++) {
            try (var fixture = new Fixture("chat-isolated", names.get(i))) {
                fixture.environment.withProperty(flags.get(i), "true");
                assertEquals("DOWN", fixture.availability.health().getStatus().getCode());
                assertFalse(fixture.availability.accepting());
            }
        }
    }

    @Test
    void isolatedPartialRuntimeRemainsSupportedAndDormantRuntimeStaysDown() {
        try (var fixture = new Fixture("chat-isolated", "all")) {
            assertEquals("UP", fixture.availability.health().getStatus().getCode());
            assertTrue(fixture.availability.accepting());
            fixture.environment.withProperty("chat.runtime.enabled", "false");
            assertEquals("DOWN", fixture.availability.health().getStatus().getCode());
            assertFalse(fixture.availability.accepting());
        }
    }

    @Test
    void validPermitAloneDoesNotHideMissingRedisSubscriptionOrRelayReadiness() {
        try (var fixture = new Fixture("prod", "none")) {
            assertEquals("UP", fixture.availability.health().getStatus().getCode());
            when(fixture.redis.areSubscriptionsReady()).thenReturn(false);
            assertEquals("DOWN", fixture.availability.health().getStatus().getCode());
            when(fixture.redis.areSubscriptionsReady()).thenReturn(true);
            when(fixture.relay.ready()).thenReturn(false);
            assertEquals("DOWN", fixture.availability.health().getStatus().getCode());
            when(fixture.relay.ready()).thenReturn(true);
            when(fixture.redis.isConnected()).thenReturn(false);
            assertEquals("DOWN", fixture.availability.health().getStatus().getCode());
            verify(fixture.redis, never()).commands();
        }
    }

    @Test
    void coreOutageChangesReadinessWithoutOverridingUserAuthorizationPermits() {
        try (var fixture = new Fixture("prod", "none")) {
            when(fixture.verifier.isAvailable()).thenReturn(false);
            assertEquals("DOWN", fixture.availability.health().getStatus().getCode());
            assertTrue(fixture.availability.accepting());
            when(fixture.verifier.isAvailable()).thenReturn(true);
            assertEquals("UP", fixture.availability.health().getStatus().getCode());
            when(fixture.participation.available()).thenReturn(false);
            assertEquals("DOWN", fixture.availability.health().getStatus().getCode());
            verify(fixture.verifier, never()).serviceAvailable(true);
        }
    }

    @Test
    void expiredOrLatchedPermitBlocksRestAndWebSocketButLeavesLivenessReachable() throws Exception {
        try (var fixture = new Fixture("prod", "none")) {
            when(fixture.lifecycle.permitted()).thenReturn(false);
            var filter = new ApplicationBoundaryFilter(true, fixture.availability::accepting, new ObjectMapper());
            for (String path : List.of("/ws", "/api/v1/regions/1/chat-room")) {
                var request = new MockHttpServletRequest("GET", path);
                request.setServletPath(path);
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (incoming, outgoing) -> {
                    throw new AssertionError("blocked requests must not reach security or controllers");
                });
                assertEquals(503, response.getStatus());
                assertEquals("5", response.getHeader("Retry-After"));
            }
            var liveness = new MockHttpServletRequest("GET", "/actuator/health/liveness");
            liveness.setServletPath("/actuator/health/liveness");
            var response = new MockHttpServletResponse();
            filter.doFilter(liveness, response, (incoming, outgoing) -> response.setStatus(200));
            assertEquals(200, response.getStatus());
            assertEquals("DOWN", fixture.availability.health().getStatus().getCode());
            when(fixture.redis.isConnected()).thenReturn(true);
            when(fixture.redis.areSubscriptionsReady()).thenReturn(true);
            assertEquals("DOWN", fixture.availability.health().getStatus().getCode());
            verify(fixture.lifecycle, never()).start();
            verify(fixture.lifecycle, never()).check();
        }
    }

    @Test
    void dependencyExceptionsAreDownWithoutDetailsOrSecrets() {
        try (var fixture = new Fixture("prod", "none")) {
            when(fixture.lifecycle.permitted()).thenThrow(new IllegalStateException("synthetic-private-endpoint"));
            assertFalse(fixture.availability.accepting());
            var health = fixture.availability.health();
            assertEquals("DOWN", health.getStatus().getCode());
            assertTrue(health.getDetails().isEmpty());
            assertFalse(health.toString().contains("synthetic-private-endpoint"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final GenericApplicationContext context = new GenericApplicationContext();
        private final MockEnvironment environment = new MockEnvironment().withProperty("chat.runtime.enabled", "true");
        private final ChatRuntimeLifecycle lifecycle = mock(ChatRuntimeLifecycle.class);
        private final ChatCoreSessionVerifier verifier = mock(ChatCoreSessionVerifier.class);
        private final ChatParticipationService participation = mock(ChatParticipationService.class);
        private final ChatRedisConnection redis = mock(ChatRedisConnection.class);
        private final ChatMessageRelay relay = mock(ChatMessageRelay.class);
        private final ChatRuntimeAvailability availability;

        private Fixture(String profile, String missing) {
            environment.setActiveProfiles(profile);
            when(lifecycle.permitted()).thenReturn(true);
            when(verifier.isAvailable()).thenReturn(true);
            when(participation.available()).thenReturn(true);
            when(redis.isConnected()).thenReturn(true);
            when(redis.areSubscriptionsReady()).thenReturn(true);
            when(relay.ready()).thenReturn(true);
            var components = java.util.Map.of("lifecycle", lifecycle, "verifier", verifier,
                    "participation", participation, "redis", redis, "relay", relay);
            components.forEach((name, bean) -> {
                if (!name.equals(missing) && !missing.equals("all")) {
                    context.getBeanFactory().registerSingleton(name, bean);
                }
            });
            context.refresh();
            availability = new ChatRuntimeAvailability(environment, context.getBeanProvider(ChatRuntimeLifecycle.class),
                    context.getBeanProvider(ChatCoreSessionVerifier.class),
                    context.getBeanProvider(ChatParticipationService.class),
                    context.getBeanProvider(ChatRedisConnection.class),
                    context.getBeanProvider(ChatMessageRelay.class));
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
