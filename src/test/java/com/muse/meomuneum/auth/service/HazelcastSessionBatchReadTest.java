package com.muse.meomuneum.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.hazelcast.cluster.Cluster;
import com.hazelcast.cluster.Member;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

class HazelcastSessionBatchReadTest {
    @Test
    void freshBulkReadIsBoundedReadOnlyAndFailsClosedOnClusterOrMapFailure() {
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        var hz = mock(HazelcastInstance.class);
        var lifecycle = mock(LifecycleService.class);
        var cluster = mock(Cluster.class);
        @SuppressWarnings("unchecked")
        IMap<String, HazelcastAuthStateStore.AuthSession> sessions = mock(IMap.class);
        when(provider.getIfAvailable()).thenReturn(hz);
        when(hz.getLifecycleService()).thenReturn(lifecycle);
        when(lifecycle.isRunning()).thenReturn(true);
        when(hz.getCluster()).thenReturn(cluster);
        when(cluster.getMembers()).thenReturn(Set.of(mock(Member.class)));
        when(hz.<String, HazelcastAuthStateStore.AuthSession>getMap("auth-sessions-v2")).thenReturn(sessions);
        var properties = new AuthStateProperties("01234567890123456789012345678901",
                Base64.getEncoder().encodeToString(new byte[32]), "MEO_QUEUE_V2", 1209600, 7776000,
                900, 60, 900, 20, 1, 64, 4, 4, 120, 1800, 1, 20, 4000, 86400, 1, 100000, 300, 45);
        var store = new HazelcastAuthStateStore(provider, properties);
        String sid = UUID.randomUUID().toString();
        var active = mock(HazelcastAuthStateStore.AuthSession.class);
        when(sessions.getAll(Set.of(sid))).thenReturn(Map.of(sid, active), Map.of());
        assertEquals(Map.of(sid, active), store.getSessions(Set.of(sid)));
        assertEquals(Map.of(), store.getSessions(Set.of(sid)));
        verify(sessions, org.mockito.Mockito.times(2)).getAll(Set.of(sid));
        verifyNoMoreInteractions(sessions);
        assertThrows(IllegalArgumentException.class, () -> store.getSessions(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> store.getSessions(Set.of("invalid")));
        var oversized = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.toSet());
        assertThrows(IllegalArgumentException.class, () -> store.getSessions(oversized));
        when(cluster.getMembers()).thenReturn(Set.of());
        assertThrows(AuthStateUnavailableException.class, () -> store.getSessions(Set.of(sid)));
        when(cluster.getMembers()).thenReturn(Set.of(mock(Member.class)));
        when(sessions.getAll(Set.of(sid))).thenThrow(new IllegalStateException("synthetic-unavailable"));
        assertThrows(AuthStateUnavailableException.class, () -> store.getSessions(Set.of(sid)));
    }
}
