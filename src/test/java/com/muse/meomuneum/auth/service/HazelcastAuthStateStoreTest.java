package com.muse.meomuneum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

class HazelcastAuthStateStoreTest {
    private HazelcastInstance member;
    private HazelcastAuthStateStore store;
    private AuthStateProperties properties;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.setClusterName("auth-session-test-" + UUID.randomUUID());
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        config.getNetworkConfig().setPort(5901);
        config.getNetworkConfig().setPortAutoIncrement(true);
        member = Hazelcast.newHazelcastInstance(config);
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        properties = new AuthStateProperties("01234567890123456789012345678901",
                Base64.getEncoder().encodeToString(new byte[32]), "MEO_QUEUE_V2", 1209600, 7776000,
                900, 60, 900, 20, 10, 8, 4, 4, 120, 1800, 5, 2000, 4000, 86400, 1, 1000, 300, 60);
        store = new HazelcastAuthStateStore(provider, properties);
    }

    @AfterEach
    void tearDown() {
        if (member != null) {
            member.shutdown();
        }
    }

    @Test
    void preservesTheBoundedFreshSessionLookupSignatureAndSemantics() throws Exception {
        assertThat(HazelcastAuthStateStore.class.getMethod("getSessions", Set.class).getReturnType())
                .isEqualTo(Map.class);
        String sid = UUID.randomUUID().toString();
        var created = createSession(42L, sid);

        Map<String, HazelcastAuthStateStore.AuthSession> sessions = store.getSessions(
                Set.of(sid, UUID.randomUUID().toString()));

        assertThat(sessions).containsOnlyKeys(sid);
        assertThat(sessions.get(sid).generation()).isEqualTo(created.generation());
        assertThat(sessions.get(sid).status()).isEqualTo(HazelcastAuthStateStore.SessionStatus.ACTIVE);
    }

    @Test
    void rejectsUnboundedOrInvalidSessionLookupBatches() {
        assertThatThrownBy(() -> store.getSessions(java.util.stream.IntStream.range(0, 101)
                .mapToObj(ignored -> UUID.randomUUID().toString()).collect(java.util.stream.Collectors.toSet())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.getSessions(Set.of("not-a-session-id")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesSessionCreationAndRevocationProjection() {
        String sid = UUID.randomUUID().toString();
        createSession(43L, sid);

        var revoked = store.revokeSession(sid, 43L);

        assertThat(revoked.status()).isEqualTo(HazelcastAuthStateStore.SessionStatus.REVOKED);
        assertThat(store.getSessions(Set.of(sid)).get(sid).status())
                .isEqualTo(HazelcastAuthStateStore.SessionStatus.REVOKED);
        assertThat(store.revokeSession(sid, 99L)).isNull();
    }

    @Test
    void configuredSessionCapacityStillFailsClosed() {
        AuthStateProperties bounded = new AuthStateProperties(properties.hmacKey(), properties.replayEncryptionKey(),
                properties.queueCookieName(), properties.sessionIdleSeconds(), properties.sessionAbsoluteSeconds(),
                properties.queueTicketSeconds(), properties.readyTicketSeconds(), properties.queueTombstoneSeconds(),
                properties.queueCapacity(), properties.maxDeviceSessions(), properties.localAuthConcurrency(),
                properties.localBcryptConcurrency(), properties.globalBcryptPermits(),
                properties.bcryptPermitLeaseSeconds(), properties.queueCookieSeconds(),
                properties.statusMinimumIntervalSeconds(), properties.queueCreateRequestsPerMinute(),
                properties.operationRecordCapacity(), properties.idempotencyIdleHorizonSeconds(),
                properties.expectedHazelcastMembers(), 1, properties.revokedSessionRetentionSeconds(),
                properties.refreshReplayWindowSeconds());
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        HazelcastAuthStateStore boundedStore = new HazelcastAuthStateStore(provider, bounded);
        String first = UUID.randomUUID().toString();
        createSession(boundedStore, 51L, first);

        assertThatThrownBy(() -> createSession(boundedStore, 52L, UUID.randomUUID().toString()))
                .isInstanceOf(AuthStateUnavailableException.class);
    }

    private HazelcastAuthStateStore.AuthSession createSession(Long userId, String sid) {
        return createSession(store, userId, sid);
    }

    private HazelcastAuthStateStore.AuthSession createSession(HazelcastAuthStateStore target, Long userId, String sid) {
        return target.createSession(userId, sid, UUID.randomUUID().toString(), "refresh-hash",
                UUID.randomUUID().toString(), "encrypted-refresh", Instant.now().plusSeconds(3600),
                target.newAbsoluteSessionExpiry());
    }
}
