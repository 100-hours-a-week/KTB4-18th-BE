package com.muse.meomuneum.chat.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.muse.meomuneum.auth.internal.CoreInternalAuthFilter;
import com.muse.meomuneum.auth.internal.CoreSessionValidationService;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.security.AuthRevocationProtocol;
import com.muse.meomuneum.global.security.AuthSessionRevokedEvent;
import com.muse.meomuneum.global.security.InternalSessionProtocol;
import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;
import com.muse.meomuneum.user.repository.UserRepository;

class CoreAuthContractTest {
    private static final String CURRENT = "current-synthetic-service-secret-32-bytes";
    private static final String NEXT = "next-synthetic-service-secret-at-least-32";
    private final SessionPair pair = new SessionPair(1, UUID.randomUUID().toString());

    @Test
    void strictProtocolRejectsDuplicatesExtraFieldsWrongTypesAndResponseMismatches() {
        byte[] valid = InternalSessionProtocol.encodeRequest(List.of(pair));
        assertEquals(List.of(pair), InternalSessionProtocol.request(valid));
        for (String body : List.of("{}", "{\"sessions\":[]}", "{\"sessions\":[],\"extra\":1}",
                "{\"sessions\":[],\"sessions\":[]}", new String(valid, StandardCharsets.UTF_8) + "{}",
                new String(InternalSessionProtocol.encodeRequest(List.of(pair, pair)), StandardCharsets.UTF_8))) {
            assertThrows(IllegalArgumentException.class,
                    () -> InternalSessionProtocol.request(body.getBytes(StandardCharsets.UTF_8)));
        }
        var active = new SessionDecision(pair, true, 7, 3000);
        assertEquals(List.of(active), InternalSessionProtocol.response(
                InternalSessionProtocol.encodeResponse(List.of(active)), List.of(pair)));
        assertThrows(IllegalArgumentException.class, () -> InternalSessionProtocol.response(
                InternalSessionProtocol.encodeResponse(List.of(active)), List.of(new SessionPair(2, pair.sid()))));
        assertThrows(IllegalArgumentException.class, () -> InternalSessionProtocol.response(
                InternalSessionProtocol.encodeResponse(List.of(new SessionDecision(pair, true, 0, 0))), List.of(pair)));
        assertEquals(List.of(SessionDecision.inactive(pair)), InternalSessionProtocol.response(
                InternalSessionProtocol.encodeResponse(List.of(SessionDecision.inactive(pair))), List.of(pair)));
    }

    @Test
    void serviceKeysRotateWithoutAcceptingUserCredentialsAndBodyErrorsStayBounded() throws Exception {
        var validation = mock(CoreSessionValidationService.class);
        when(validation.validate(List.of(pair))).thenReturn(List.of(SessionDecision.inactive(pair)));
        var filter = new CoreInternalAuthFilter(CURRENT, NEXT, validation);
        for (String key : List.of(CURRENT, NEXT)) {
            var result = invoke(filter, key, InternalSessionProtocol.encodeRequest(List.of(pair)));
            assertEquals(200, result.getStatus());
            assertEquals("no-store", result.getHeader("Cache-Control"));
            assertFalse(result.getContentAsString().contains(key));
        }
        assertEquals(401, invoke(filter, "user-access-token", new byte[0]).getStatus());
        assertEquals(413, invoke(filter, CURRENT, new byte[32769]).getStatus());
        assertEquals(400, invoke(filter, CURRENT, "{}".getBytes(StandardCharsets.UTF_8)).getStatus());
        when(validation.validate(List.of(pair))).thenThrow(new IllegalStateException("synthetic-private-detail"));
        var unavailable = invoke(filter, CURRENT, InternalSessionProtocol.encodeRequest(List.of(pair)));
        assertEquals(503, unavailable.getStatus());
        assertFalse(unavailable.getContentAsString().contains("synthetic-private-detail"));
        assertEquals(401, invoke(new CoreInternalAuthFilter(CURRENT, "", validation), NEXT, new byte[0]).getStatus());
    }

    @Test
    void tasksBehindTheSameAddressHaveIndependentLimitsButCannotBypassTheUserLimit() throws Exception {
        var validation = mock(CoreSessionValidationService.class);
        when(validation.validate(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> List.of(
                SessionDecision.inactive(call.<List<SessionPair>>getArgument(0).getFirst())));
        var filter = new CoreInternalAuthFilter(CURRENT, "", validation, () -> 1L);
        String taskA = UUID.randomUUID().toString();
        String taskB = UUID.randomUUID().toString();
        for (int i = 1; i <= 100; i++) {
            for (String task : List.of(taskA, taskB)) {
                long user = task.equals(taskA) ? i : i + 100;
                byte[] body = InternalSessionProtocol.encodeRequest(
                        List.of(new SessionPair(user, UUID.randomUUID().toString())));
                assertEquals(200, invoke(filter, CURRENT, body, task).getStatus());
            }
        }
        byte[] body = InternalSessionProtocol.encodeRequest(List.of(pair));
        assertEquals(503, invoke(filter, CURRENT, body, taskA).getStatus());
        assertEquals(503, invoke(filter, CURRENT, body, taskB).getStatus());
        var userFilter = new CoreInternalAuthFilter(CURRENT, "", validation, () -> 1L);
        for (int i = 0; i < 100; i++) {
            assertEquals(200, invoke(userFilter, CURRENT, body, i % 2 == 0 ? taskA : taskB).getStatus());
        }
        assertEquals(503, invoke(userFilter, CURRENT, body, UUID.randomUUID().toString()).getStatus());
        var legacy = new CoreInternalAuthFilter(CURRENT, "", validation, () -> 1L);
        for (int i = 0; i < 100; i++) {
            assertEquals(200, invoke(legacy, CURRENT, body).getStatus());
        }
        assertEquals(503, invoke(legacy, CURRENT, body).getStatus());
        assertEquals(400, invoke(filter, CURRENT, body, "invalid").getStatus());
        assertEquals(400, invoke(filter, CURRENT, body, taskA, taskB).getStatus());
        assertEquals(401, invoke(filter, "invalid-service-key", body, taskA).getStatus());
    }

    @Test
    void coreReadsActiveOwnerUserAndBothExpirationsWithoutRotatingSessions() {
        var store = mock(HazelcastAuthStateStore.class);
        var users = mock(UserRepository.class);
        var session = mock(HazelcastAuthStateStore.AuthSession.class);
        when(store.getSessions(Set.of(pair.sid()))).thenReturn(java.util.Map.of(pair.sid(), session));
        when(session.sid()).thenReturn(pair.sid());
        when(users.findActiveIds(List.of(1L))).thenReturn(Set.of(1L));
        when(session.userId()).thenReturn(1L);
        when(session.generation()).thenReturn(7L);
        when(session.status()).thenReturn(HazelcastAuthStateStore.SessionStatus.ACTIVE);
        when(session.refreshIdleExpiresAt()).thenReturn(Instant.now().plusSeconds(10));
        when(session.refreshAbsoluteExpiresAt()).thenReturn(Instant.now().plusSeconds(20));
        try (var service = new CoreSessionValidationService(store, users)) {
            var active = service.validate(List.of(pair)).getFirst();
            assertTrue(active.active());
            assertEquals(7, active.generation());
            assertTrue(active.remainingMillis() > 0 && active.remainingMillis() <= 10000);
            for (var status : List.of(HazelcastAuthStateStore.SessionStatus.PROVISIONING,
                    HazelcastAuthStateStore.SessionStatus.REVOKED)) {
                when(session.status()).thenReturn(status);
                assertFalse(service.validate(List.of(pair)).getFirst().active());
            }
            when(session.status()).thenReturn(HazelcastAuthStateStore.SessionStatus.ACTIVE);
            when(session.sid()).thenReturn(UUID.randomUUID().toString());
            assertFalse(service.validate(List.of(pair)).getFirst().active());
            when(session.sid()).thenReturn(pair.sid());
            when(session.userId()).thenReturn(2L);
            assertFalse(service.validate(List.of(pair)).getFirst().active());
            when(session.userId()).thenReturn(1L);
            when(users.findActiveIds(List.of(1L))).thenReturn(Set.of());
            assertFalse(service.validate(List.of(pair)).getFirst().active());
            when(users.findActiveIds(List.of(1L))).thenReturn(Set.of(1L));
            when(session.refreshAbsoluteExpiresAt()).thenReturn(Instant.now().minusMillis(1));
            assertFalse(service.validate(List.of(pair)).getFirst().active());
            when(users.findActiveIds(List.of(1L))).thenThrow(new IllegalStateException("database unavailable"));
            assertThrows(IllegalStateException.class, () -> service.validate(List.of(pair)));
        }
    }

    @Test
    void hundredPairsUseOneFreshBulkReadInRequestOrderAndNeverFallBackToIndividualReads() {
        var store = mock(HazelcastAuthStateStore.class);
        var users = mock(UserRepository.class);
        var pairs = java.util.stream.LongStream.rangeClosed(1, 100)
                .mapToObj(id -> new SessionPair(id, UUID.randomUUID().toString())).toList();
        var ids = pairs.stream().map(SessionPair::sid).collect(java.util.stream.Collectors.toSet());
        var activeIds = pairs.stream().map(SessionPair::userId).collect(java.util.stream.Collectors.toSet());
        when(users.findActiveIds(pairs.stream().map(SessionPair::userId).toList())).thenReturn(activeIds);
        var values = new java.util.HashMap<String, HazelcastAuthStateStore.AuthSession>();
        for (var item : pairs) {
            var session = mock(HazelcastAuthStateStore.AuthSession.class);
            when(session.sid()).thenReturn(item.sid());
            when(session.userId()).thenReturn(item.userId());
            when(session.status()).thenReturn(HazelcastAuthStateStore.SessionStatus.ACTIVE);
            when(session.generation()).thenReturn(3L);
            when(session.refreshIdleExpiresAt()).thenReturn(Instant.now().plusSeconds(10));
            when(session.refreshAbsoluteExpiresAt()).thenReturn(Instant.now().plusSeconds(20));
            values.put(item.sid(), session);
        }
        when(store.getSessions(ids)).thenReturn(values, java.util.Map.of());
        try (var service = new CoreSessionValidationService(store, users)) {
            var first = service.validate(pairs);
            assertEquals(pairs, first.stream().map(SessionDecision::pair).toList());
            assertTrue(first.stream().allMatch(SessionDecision::active));
            assertTrue(service.validate(pairs).stream().noneMatch(SessionDecision::active));
        }
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(2)).getSessions(ids);
        org.mockito.Mockito.verifyNoMoreInteractions(store);
    }

    @Test
    void revocationEventsRequireExactSchemaProducerAndSafeIds() {
        String producer = "core:" + UUID.randomUUID();
        var event = new AuthSessionRevokedEvent(pair.userId(), pair.sid(), 9);
        String body = AuthRevocationProtocol.encode(event, producer);
        assertEquals(event, AuthRevocationProtocol.decode(body, Set.of(producer)));
        assertThrows(IllegalArgumentException.class, () -> AuthRevocationProtocol.decode(body, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> AuthRevocationProtocol.decode(body.replace(
                "\"schema_version\":1", "\"schema_version\":2"), Set.of(producer)));
        assertThrows(IllegalArgumentException.class,
                () -> AuthRevocationProtocol.decode("x".repeat(16385), Set.of(producer)));
        assertFalse(body.contains("refresh"));
    }

    private MockHttpServletResponse invoke(CoreInternalAuthFilter filter, String credential, byte[] body,
            String... tasks)
            throws Exception {
        var request = new MockHttpServletRequest("POST", CoreInternalAuthFilter.PATH);
        request.setServletPath(CoreInternalAuthFilter.PATH);
        request.addHeader("Authorization", "Bearer " + credential);
        request.setContentType("application/json");
        request.setContent(body);
        for (String task : tasks) {
            request.addHeader("X-Chat-Task-Id", task);
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("internal endpoint escaped");
        });
        return response;
    }
}
