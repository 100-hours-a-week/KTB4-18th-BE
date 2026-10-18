package com.muse.meomuneum.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;

class LocalHazelcastMemberLifecycleTest {

    @Test
    void createsFixedIsolatedMemberWithConfiguredCapacityAndRealClientOperations() throws Exception {
        int port = freePort();
        String cluster = clusterName();
        AtomicReference<Config> createdConfig = new AtomicReference<>();
        Set<HazelcastInstance> before = members();
        try (var owner = new LocalHazelcastMemberLifecycle(config -> {
            createdConfig.set(config);
            return Hazelcast.newHazelcastInstance(config);
        }, HazelcastClient::newHazelcastClient)) {
            HazelcastInstance client = owner.connect(clientConfig(cluster, port), properties(Map.of(
                    "auth.state.queue-capacity", 3_000, "auth.state.local-bcrypt-concurrency", 200)));
            try {
                assertThat(((InetSocketAddress) client.getLocalEndpoint().getSocketAddress())
                        .getAddress().isLoopbackAddress()).isTrue();
                client.<String, String>getMap("local-lifecycle-probe").put("key", "value");
                assertThat(client.<String, String>getMap("local-lifecycle-probe").get("key")).isEqualTo("value");
                assertThat(client.<String>getQueue("login-queue-order-v2").offer("ticket")).isTrue();
                assertThat(client.<String>getQueue("login-queue-order-v2").poll()).isEqualTo("ticket");
                Config config = createdConfig.get();
                assertThat(config.getClusterName()).isEqualTo(cluster);
                assertThat(config.getNetworkConfig().getPort()).isEqualTo(port);
                assertThat(config.getNetworkConfig().isPortAutoIncrement()).isFalse();
                assertThat(config.getNetworkConfig().getInterfaces().isEnabled()).isTrue();
                assertThat(config.getNetworkConfig().getInterfaces().getInterfaces()).containsExactly("127.0.0.1");
                assertThat(config.getNetworkConfig().getJoin().getAutoDetectionConfig().isEnabled()).isFalse();
                assertThat(config.getNetworkConfig().getJoin().getMulticastConfig().isEnabled()).isFalse();
                assertThat(config.getNetworkConfig().getJoin().getTcpIpConfig().isEnabled()).isFalse();
                assertThat(config.getJetConfig().isEnabled()).isFalse();
                assertThat(config.getQueueConfig("login-queue-order-v2").getMaxSize()).isEqualTo(3_200);
                assertThat(config.getProperty("hazelcast.socket.server.bind.any")).isEqualTo("false");
                assertThat(config.getProperty("hazelcast.shutdownhook.enabled")).isEqualTo("false");
                assertThat(client.getCluster().getMembers()).hasSize(1);
            } finally {
                client.shutdown();
            }
        }
        assertPortAvailable(port);
        assertThat(members()).isEqualTo(before);
    }

    @Test
    void authenticatesAndReusesExistingMemberWithoutOwningIt() throws Exception {
        int port = freePort();
        String cluster = clusterName();
        HazelcastInstance member = fixtureMember(cluster, port);
        UUID memberId = member.getCluster().getLocalMember().getUuid();
        try {
            try (var owner = new LocalHazelcastMemberLifecycle(config -> {
                throw new AssertionError("An occupied port must not create another member");
            }, HazelcastClient::newHazelcastClient)) {
                HazelcastInstance client = owner.connect(clientConfig(cluster, port), properties(Map.of()));
                try {
                    client.<String, String>getMap("reuse-probe").put("key", "preserved");
                    assertThat(client.getCluster().getMembers()).extracting(com.hazelcast.cluster.Member::getUuid)
                            .containsExactly(memberId);
                } finally {
                    client.shutdown();
                }
            }
            assertThat(member.getLifecycleService().isRunning()).isTrue();
            assertThat(member.<String, String>getMap("reuse-probe").get("key")).isEqualTo("preserved");
            try (var secondOwner = new LocalHazelcastMemberLifecycle()) {
                HazelcastInstance second = secondOwner.connect(clientConfig(cluster, port), properties(Map.of()));
                try {
                    assertThat(second.<String, String>getMap("reuse-probe").get("key")).isEqualTo("preserved");
                } finally {
                    second.shutdown();
                }
            }
        } finally {
            member.shutdown();
        }
    }

    @Test
    @Timeout(25)
    void rejectsWrongClusterWithinTwentySecondsWithoutReplacingOccupiedMember() throws Exception {
        int port = freePort();
        HazelcastInstance member = fixtureMember(clusterName(), port);
        Set<HazelcastInstance> beforeMembers = members();
        Set<HazelcastInstance> beforeClients = clients();
        try (var owner = new LocalHazelcastMemberLifecycle(config -> {
            throw new AssertionError("Authentication failure must not create a fallback member");
        }, HazelcastClient::newHazelcastClient)) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> owner.connect(clientConfig(clusterName(), port), properties(Map.of())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
            assertThat(member.getLifecycleService().isRunning()).isTrue();
            assertThat(members()).isEqualTo(beforeMembers);
            assertThat(clients()).isEqualTo(beforeClients);
        } finally {
            member.shutdown();
        }
    }

    @Test
    @Timeout(25)
    void rejectsSilentTcpListenerWithinTwentySecondsWithoutFallbackOrClientLeak() throws Exception {
        Set<HazelcastInstance> beforeMembers = members();
        Set<HazelcastInstance> beforeClients = clients();
        try (ServerSocket listener = loopbackSocket(0)) {
            AtomicReference<Socket> accepted = new AtomicReference<>();
            Thread acceptor = Thread.ofPlatform().start(() -> {
                try {
                    accepted.set(listener.accept());
                } catch (IOException ignored) {
                    // Closing this fixture's listener releases an accept blocked during a failed assertion.
                }
            });
            try (var owner = new LocalHazelcastMemberLifecycle(config -> {
                throw new AssertionError("An occupied TCP listener must never be replaced");
            }, HazelcastClient::newHazelcastClient)) {
                long started = System.nanoTime();
                assertThatThrownBy(() -> owner.connect(clientConfig(clusterName(), listener.getLocalPort()),
                        properties(Map.of()))).isInstanceOf(IllegalStateException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
                assertThat(listener.isClosed()).isFalse();
                assertThat(accepted.get()).isNotNull();
                assertThat(members()).isEqualTo(beforeMembers);
                assertThat(clients()).isEqualTo(beforeClients);
            } finally {
                listener.close();
                acceptor.join(1_000);
                if (accepted.get() != null) {
                    accepted.get().close();
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"192.0.2.1:51031,1", "localhost:51031,1", "127.0.0.1,1", "[::1]:51031,1",
            "127.0.0.1:0,1", "127.0.0.1:65536,1", "127.0.0.1:51031,2"})
    void preservesExternalClientConfigurationForIneligibleAddresses(String address, int expectedMembers) {
        ClientConfig config = new ClientConfig().setClusterName("custom-external-cluster");
        config.getNetworkConfig().addAddress(address);
        assertExternalConfigUntouched(config, expectedMembers);
    }

    @Test
    void preservesMultipleExplicitAddressesForExternalClient() {
        ClientConfig config = new ClientConfig().setClusterName("multiple-external-members");
        config.getNetworkConfig().addAddress("127.0.0.1:51001", "127.0.0.1:51002");
        assertExternalConfigUntouched(config, 1);
    }

    @Test
    void rejectsQueueCapacityOverflowBeforeCreatingClientOrMember() throws Exception {
        int port = freePort();
        try (var owner = new LocalHazelcastMemberLifecycle(config -> {
            throw new AssertionError("Invalid capacity must not start a member");
        }, config -> {
            throw new AssertionError("Invalid capacity must not start a client");
        })) {
            assertThatThrownBy(() -> owner.connect(clientConfig(clusterName(), port), properties(Map.of(
                    "auth.state.queue-capacity", Integer.MAX_VALUE, "auth.state.local-bcrypt-concurrency", 1))))
                    .isInstanceOf(ArithmeticException.class);
            assertPortAvailable(port);
        }
    }

    @Test
    void rollsBackRealOwnedMemberWhenClientCreationFails() throws Exception {
        int port = freePort();
        Set<HazelcastInstance> before = members();
        IllegalStateException original = new IllegalStateException("client factory failure");
        try (var owner = new LocalHazelcastMemberLifecycle(Hazelcast::newHazelcastInstance, config -> {
            throw original;
        })) {
            assertThatThrownBy(() -> owner.connect(clientConfig(clusterName(), port), properties(Map.of())))
                    .isSameAs(original);
            assertPortAvailable(port);
            assertThat(members()).isEqualTo(before);
        }
    }

    @Test
    void neverIncrementsPortAfterBindRace() throws Exception {
        int port = freePort();
        Set<HazelcastInstance> before = members();
        AtomicReference<ServerSocket> competingListener = new AtomicReference<>();
        try (var owner = new LocalHazelcastMemberLifecycle(config -> {
            try {
                competingListener.set(loopbackSocket(port));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            return Hazelcast.newHazelcastInstance(config);
        }, config -> {
            throw new AssertionError("Failed member must not proceed to client creation");
        })) {
            assertThatThrownBy(() -> owner.connect(clientConfig(clusterName(), port), properties(Map.of())))
                    .isInstanceOf(RuntimeException.class);
            assertThat(competingListener.get().isClosed()).isFalse();
            assertThat(members()).isEqualTo(before);
        } finally {
            if (competingListener.get() != null) {
                competingListener.get().close();
            }
        }
        assertPortAvailable(port);
    }

    @Test
    void terminatesOnlyOwnedReferenceIfGracefulShutdownFails() throws Exception {
        HazelcastInstance member = mock(HazelcastInstance.class);
        LifecycleService lifecycle = mock(LifecycleService.class);
        when(member.getLifecycleService()).thenReturn(lifecycle);
        doThrow(new IllegalStateException("graceful failure")).when(member).shutdown();
        try (var owner = new LocalHazelcastMemberLifecycle(config -> member, config -> mock(HazelcastInstance.class))) {
            owner.connect(clientConfig(clusterName(), freePort()), properties(Map.of()));
            owner.close();
            owner.close();
            verify(member, times(1)).shutdown();
            verify(lifecycle, times(1)).terminate();
        }
    }

    @Test
    void preservesOriginalFailureAndRetainsOwnedReferenceWhenBothCleanupAttemptsFail() throws Exception {
        HazelcastInstance member = mock(HazelcastInstance.class);
        LifecycleService lifecycle = mock(LifecycleService.class);
        when(member.getLifecycleService()).thenReturn(lifecycle);
        IllegalStateException original = new IllegalStateException("original client failure");
        IllegalStateException graceful = new IllegalStateException("graceful cleanup failure");
        IllegalStateException forced = new IllegalStateException("forced cleanup failure");
        doThrow(graceful).doNothing().when(member).shutdown();
        doThrow(forced).when(lifecycle).terminate();
        try (var owner = new LocalHazelcastMemberLifecycle(config -> member, config -> {
            throw original;
        })) {
            assertThatThrownBy(() -> owner.connect(clientConfig(clusterName(), freePort()), properties(Map.of())))
                    .isSameAs(original);
            assertThat(original.getSuppressed()).containsExactly(graceful);
            assertThat(graceful.getSuppressed()).containsExactly(forced);
            owner.close();
            verify(member, times(2)).shutdown();
            verify(lifecycle, times(1)).terminate();
        }
    }

    static AuthStateProperties properties(Map<String, Object> overrides) {
        Map<String, Object> values = new HashMap<>();
        values.put("auth.state.hmac-key", "test-only-local-hmac-key-at-least-32-bytes");
        values.put("auth.state.replay-encryption-key", Base64.getEncoder().encodeToString(new byte[32]));
        values.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("auth.state", Bindable.of(AuthStateProperties.class)).get();
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = loopbackSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static String clusterName() {
        return "local-lifecycle-" + UUID.randomUUID();
    }

    static void assertPortAvailable(int port) throws IOException {
        try (ServerSocket socket = loopbackSocket(port)) {
            assertThat(socket.isBound()).isTrue();
        }
    }

    static HazelcastInstance fixtureMember(String cluster, int port) {
        Config config = new Config().setClusterName(cluster);
        config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        config.getJetConfig().setEnabled(false);
        config.setProperty("hazelcast.socket.server.bind.any", "false");
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        config.setProperty("hazelcast.operation.thread.count", "2");
        config.setProperty("hazelcast.operation.generic.thread.count", "2");
        config.setProperty("hazelcast.io.input.thread.count", "2");
        config.setProperty("hazelcast.io.output.thread.count", "2");
        return Hazelcast.newHazelcastInstance(config);
    }

    private static ServerSocket loopbackSocket(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        return socket;
    }

    private ClientConfig clientConfig(String cluster, int port) {
        ClientConfig config = new ClientConfig().setClusterName(cluster);
        config.getNetworkConfig().addAddress("127.0.0.1:" + port);
        return config;
    }

    private Set<HazelcastInstance> members() {
        return Set.copyOf(Hazelcast.getAllHazelcastInstances());
    }

    private Set<HazelcastInstance> clients() {
        return HazelcastClient.getAllHazelcastClients().stream().collect(Collectors.toSet());
    }

    private void assertExternalConfigUntouched(ClientConfig config, int expectedMembers) {
        List<String> addresses = List.copyOf(config.getNetworkConfig().getAddresses());
        int timeout = config.getNetworkConfig().getConnectionTimeout();
        long retryTimeout = config.getConnectionStrategyConfig().getConnectionRetryConfig()
                .getClusterConnectTimeoutMillis();
        HazelcastInstance client = mock(HazelcastInstance.class);
        try (var owner = new LocalHazelcastMemberLifecycle(ignored -> {
            throw new AssertionError("External addresses must not start a local member");
        }, captured -> {
            assertThat(captured).isSameAs(config);
            return client;
        })) {
            assertThat(
                    owner.connect(config, properties(Map.of("auth.state.expected-hazelcast-members", expectedMembers))))
                    .isSameAs(client);
            assertThat(config.getNetworkConfig().getAddresses()).containsExactlyElementsOf(addresses);
            assertThat(config.getNetworkConfig().getConnectionTimeout()).isEqualTo(timeout);
            assertThat(config.getConnectionStrategyConfig().getConnectionRetryConfig().getClusterConnectTimeoutMillis())
                    .isEqualTo(retryTimeout);
            assertThat(config.getProperty("hazelcast.client.heartbeat.timeout")).isNull();
            assertThat(config.getProperty("hazelcast.client.heartbeat.interval")).isNull();
        }
    }
}
