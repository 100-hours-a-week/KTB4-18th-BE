package com.muse.meomuneum.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

class HazelcastAuthStateConfigurationTest {

    @Test
    void refusesDeploymentConfiguredForMultipleBackendReplicas() {
        HazelcastAuthStateConfiguration configuration = new HazelcastAuthStateConfiguration();

        assertThatThrownBy(() -> configuration.authHazelcastClient(
                "meomuneum-auth", "localhost:5701", Duration.ofSeconds(1), "2", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_BACKEND_REPLICA_COUNT must be explicitly set to 1");
    }

    @Test
    void validatesAllConfigurationBeforeCallingLocalLifecycle() throws Exception {
        HazelcastAuthStateConfiguration configuration = new HazelcastAuthStateConfiguration();
        LocalHazelcastMemberLifecycle owner = mock(LocalHazelcastMemberLifecycle.class);
        int port = LocalHazelcastMemberLifecycleTest.freePort();
        String address = "127.0.0.1:" + port;
        AuthStateProperties valid = LocalHazelcastMemberLifecycleTest.properties(Map.of());
        for (String replicas : new String[]{"", "2"}) {
            assertThatThrownBy(() -> configuration.createAuthHazelcastClient("cluster", address,
                    Duration.ofSeconds(1), replicas, valid, Optional.of(owner)))
                    .hasMessageContaining("AUTH_BACKEND_REPLICA_COUNT");
        }
        for (String hmac : new String[]{"", "x".repeat(31)}) {
            AuthStateProperties invalid = LocalHazelcastMemberLifecycleTest
                    .properties(Map.of("auth.state.hmac-key", hmac));
            assertThatThrownBy(() -> configuration.createAuthHazelcastClient("cluster", address,
                    Duration.ofSeconds(1), "1", invalid, Optional.of(owner)))
                    .hasMessageContaining("AUTH_STATE_HMAC_KEY");
        }
        for (String replay : new String[]{"", "invalid-base64!",
                Base64.getEncoder().encodeToString(new byte[31])}) {
            AuthStateProperties invalid = LocalHazelcastMemberLifecycleTest.properties(
                    Map.of("auth.state.replay-encryption-key", replay));
            assertThatThrownBy(() -> configuration.createAuthHazelcastClient("cluster", address,
                    Duration.ofSeconds(1), "1", invalid, Optional.of(owner)))
                    .hasMessageContaining("AUTH_STATE_REPLAY_ENCRYPTION_KEY");
        }
        assertThatThrownBy(() -> configuration.createAuthHazelcastClient("cluster", " , ",
                Duration.ofSeconds(1), "1", valid, Optional.of(owner)))
                .hasMessageContaining("AUTH_STATE_HAZELCAST_MEMBERS");
        assertThatThrownBy(() -> configuration.createAuthHazelcastClient("cluster", address,
                Duration.ofMillis(999), "1", valid, Optional.of(owner)))
                .hasMessageContaining("invocation timeout must be at least 1 second");
        for (String cluster : new String[]{null, "", " "}) {
            assertThatThrownBy(() -> configuration.createAuthHazelcastClient(cluster, address,
                    Duration.ofSeconds(1), "1", valid, Optional.of(owner)))
                    .hasMessageContaining("cluster name must not be blank");
        }
        verifyNoInteractions(owner);
        LocalHazelcastMemberLifecycleTest.assertPortAvailable(port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "dev,test", "test,dev"})
    void exposesOnlyExistingClientBeanAndReleasesOwnedMemberAcrossContextRestart(String profiles) throws Exception {
        int port = LocalHazelcastMemberLifecycleTest.freePort();
        String cluster = LocalHazelcastMemberLifecycleTest.clusterName();
        HazelcastInstance unrelated = LocalHazelcastMemberLifecycleTest.fixtureMember(
                LocalHazelcastMemberLifecycleTest.clusterName(), LocalHazelcastMemberLifecycleTest.freePort());
        Set<HazelcastInstance> before = Set.copyOf(Hazelcast.getAllHazelcastInstances());
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                AtomicReference<HazelcastInstance> client = new AtomicReference<>();
                runner(profiles, cluster, port, Map.of()).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(HazelcastInstance.class))
                            .containsExactly("authHazelcastClient");
                    assertThat(context).hasSingleBean(LocalHazelcastMemberLifecycle.class);
                    client.set(context.getBean("authHazelcastClient", HazelcastInstance.class));
                    client.get().<String, String>getMap("context-restart-probe").put("key", "value");
                    assertThat(client.get().<String, String>getMap("context-restart-probe").get("key"))
                            .isEqualTo("value");
                    assertThat(Hazelcast.getAllHazelcastInstances()).hasSize(before.size() + 1);
                });
                assertThat(client.get().getLifecycleService().isRunning()).isFalse();
                LocalHazelcastMemberLifecycleTest.assertPortAvailable(port);
                assertThat(Hazelcast.getAllHazelcastInstances()).containsExactlyInAnyOrderElementsOf(before);
                assertThat(unrelated.getLifecycleService().isRunning()).isTrue();
            }
        } finally {
            unrelated.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void rollsBackOwnedMemberWhenALaterBeanFailsContextRefresh(String profiles) throws Exception {
        int port = LocalHazelcastMemberLifecycleTest.freePort();
        Set<HazelcastInstance> before = Set.copyOf(Hazelcast.getAllHazelcastInstances());
        runner(profiles, LocalHazelcastMemberLifecycleTest.clusterName(), port, Map.of())
                .withBean("lateFailure", Object.class, () -> {
                    throw new IllegalStateException("intentional late startup failure");
                }, definition -> definition.setDependsOn("authHazelcastClient"))
                .run(context -> assertThat(context).hasFailed());
        LocalHazelcastMemberLifecycleTest.assertPortAvailable(port);
        assertThat(Hazelcast.getAllHazelcastInstances()).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void excludesHelperOutsideLocalProfilesAndEveryProdOrderEvenWhenFlagIsForcedTrue() throws Exception {
        int port = LocalHazelcastMemberLifecycleTest.freePort();
        String cluster = LocalHazelcastMemberLifecycleTest.clusterName();
        HazelcastInstance external = LocalHazelcastMemberLifecycleTest.fixtureMember(cluster, port);
        Set<HazelcastInstance> before = Set.copyOf(Hazelcast.getAllHazelcastInstances());
        try {
            for (String profiles : new String[]{"", "other", "prod", "dev,prod", "prod,dev", "test,prod", "prod,test",
                    "dev,test,prod", "dev,prod,test", "prod,dev,test",
                    "test,dev,prod", "test,prod,dev", "prod,test,dev"}) {
                runner(profiles, cluster, port, Map.of()).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LocalHazelcastMemberLifecycle.class);
                    assertThat(context.getBean("authHazelcastClient", HazelcastInstance.class)
                            .getCluster().getMembers()).hasSize(1);
                    assertThat(Hazelcast.getAllHazelcastInstances()).containsExactlyInAnyOrderElementsOf(before);
                });
                assertThat(external.getLifecycleService().isRunning()).isTrue();
            }
        } finally {
            external.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void explicitOptOutUsesExistingExternalMemberWithoutOwningIt(String profiles) throws Exception {
        int port = LocalHazelcastMemberLifecycleTest.freePort();
        String cluster = LocalHazelcastMemberLifecycleTest.clusterName();
        HazelcastInstance external = LocalHazelcastMemberLifecycleTest.fixtureMember(cluster, port);
        try {
            runner(profiles, cluster, port, Map.of("auth.state.hazelcast.local-auto-start", "false"))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(LocalHazelcastMemberLifecycle.class);
                        assertThat(context.getBeanNamesForType(HazelcastInstance.class))
                                .containsExactly("authHazelcastClient");
                    });
            assertThat(external.getLifecycleService().isRunning()).isTrue();
        } finally {
            external.shutdown();
        }
    }

    @Test
    void disabledAuthenticationStateNeverCreatesMemberOrClientInAnyProfile() throws Exception {
        int port = LocalHazelcastMemberLifecycleTest.freePort();
        for (String profiles : new String[]{"", "other", "dev", "test", "dev,test", "test,dev", "prod",
                "dev,prod", "prod,dev", "test,prod", "prod,test", "dev,test,prod", "dev,prod,test",
                "prod,dev,test", "test,dev,prod", "test,prod,dev", "prod,test,dev"}) {
            runner(profiles, LocalHazelcastMemberLifecycleTest.clusterName(), port,
                    Map.of("auth.state.hazelcast.enabled", "false")).run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(LocalHazelcastMemberLifecycle.class);
                        assertThat(context).doesNotHaveBean(HazelcastInstance.class);
                    });
            LocalHazelcastMemberLifecycleTest.assertPortAvailable(port);
        }
    }

    private ApplicationContextRunner runner(String profiles, String cluster, int port, Map<String, Object> overrides) {
        Map<String, Object> values = new HashMap<>();
        values.put("auth.state.hazelcast.enabled", "true");
        values.put("auth.state.hazelcast.local-auto-start", "true");
        values.put("auth.state.hazelcast.cluster-name", cluster);
        values.put("auth.state.hazelcast.members", "127.0.0.1:" + port);
        values.put("auth.state.hazelcast.invocation-timeout", "1s");
        values.put("auth.state.backend-replica-count", "1");
        values.put("auth.state.hmac-key", "test-only-context-hmac-key-at-least-32-bytes");
        values.put("auth.state.replay-encryption-key", Base64.getEncoder().encodeToString(new byte[32]));
        values.putAll(overrides);
        return new ApplicationContextRunner().withUserConfiguration(HazelcastAuthStateConfiguration.class)
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources()
                            .addFirst(new MapPropertySource("isolated-fixture", values));
                    context.getEnvironment()
                            .setActiveProfiles(profiles.isEmpty() ? new String[0] : profiles.split(","));
                    context.getBeanFactory().setConversionService(new ApplicationConversionService());
                });
    }

}
