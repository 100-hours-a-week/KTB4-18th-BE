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
    void coreReadsActiveOwnerUserAndBothExpirationsWithoutRotatingSessions() {
        var store = mock(HazelcastAuthStateStore.class);
        var users = mock(UserRepository.class);
        var session = mock(HazelcastAuthStateStore.AuthSession.class);
        when(store.getSession(pair.sid())).thenReturn(session);
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
            when(session.refreshAbsoluteExpiresAt()).thenReturn(Instant.now().minusMillis(1));
            assertFalse(service.validate(List.of(pair)).getFirst().active());
            when(users.findActiveIds(List.of(1L))).thenThrow(new IllegalStateException("database unavailable"));
            assertThrows(IllegalStateException.class, () -> service.validate(List.of(pair)));
        }
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

    private MockHttpServletResponse invoke(CoreInternalAuthFilter filter, String credential, byte[] body)
            throws Exception {
        var request = new MockHttpServletRequest("POST", CoreInternalAuthFilter.PATH);
        request.setServletPath(CoreInternalAuthFilter.PATH);
        request.addHeader("Authorization", "Bearer " + credential);
        request.setContentType("application/json");
        request.setContent(body);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("internal endpoint escaped");
        });
        return response;
    }
}
