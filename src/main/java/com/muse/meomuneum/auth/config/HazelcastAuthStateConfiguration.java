package com.muse.meomuneum.auth.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;

@Configuration
@ConditionalOnProperty(prefix = "auth.state.hazelcast", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AuthStateProperties.class)
public class HazelcastAuthStateConfiguration {

    @Bean(destroyMethod = "close")
    @Profile("(dev | test) & !prod")
    @ConditionalOnProperty(prefix = "auth.state.hazelcast", name = "local-auto-start", havingValue = "true")
    LocalHazelcastMemberLifecycle localHazelcastMemberLifecycle() {
        return new LocalHazelcastMemberLifecycle();
    }

    @Bean(name = "authHazelcastClient", destroyMethod = "shutdown")
    public HazelcastInstance createAuthHazelcastClient(
            @Value("${auth.state.hazelcast.cluster-name:meomuneum-auth}") String clusterName,
            @Value("${auth.state.hazelcast.members}") String memberAddresses,
            @Value("${auth.state.hazelcast.invocation-timeout:1s}") Duration invocationTimeout,
            @Value("${auth.state.backend-replica-count:}") String backendReplicaCount,
            AuthStateProperties authStateProperties,
            Optional<LocalHazelcastMemberLifecycle> localMember) {
        validateSingletonBackend(backendReplicaCount);
        validateSecurityKeys(authStateProperties);
        String[] addresses = Arrays.stream(memberAddresses.split(","))
                .map(String::trim).filter(address -> !address.isEmpty()).toArray(String[]::new);
        if (addresses.length == 0) {
            throw new IllegalStateException("AUTH_STATE_HAZELCAST_MEMBERS must include at least one member address");
        }
        long invocationTimeoutSeconds = invocationTimeout.toSeconds();
        if (invocationTimeoutSeconds < 1) {
            throw new IllegalStateException("Hazelcast client invocation timeout must be at least 1 second");
        }
        if (clusterName == null || clusterName.isBlank()) {
            throw new IllegalStateException("Hazelcast cluster name must not be blank");
        }
        ClientConfig config = new ClientConfig();
        config.setClusterName(clusterName);
        config.getNetworkConfig().addAddress(addresses);
        config.setProperty("hazelcast.client.invocation.timeout.seconds",
                Long.toString(invocationTimeoutSeconds));
        config.setProperty("hazelcast.client.statistics.enabled", "true");
        return localMember.map(member -> member.connect(config, authStateProperties))
                .orElseGet(() -> HazelcastClient.newHazelcastClient(config));
    }

    public HazelcastInstance authHazelcastClient(String clusterName, String memberAddresses,
            Duration invocationTimeout, String backendReplicaCount, AuthStateProperties authStateProperties) {
        return createAuthHazelcastClient(clusterName, memberAddresses, invocationTimeout, backendReplicaCount,
                authStateProperties, Optional.empty());
    }

    private void validateSingletonBackend(String configuredReplicaCount) {
        if (!"1".equals(configuredReplicaCount == null ? "" : configuredReplicaCount.trim())) {
            throw new IllegalStateException(
                    "AUTH_BACKEND_REPLICA_COUNT must be explicitly set to 1; AP-1 queue and BCrypt limits are local");
        }
    }

    private void validateSecurityKeys(AuthStateProperties properties) {
        if (properties.hmacKey() == null
                || properties.hmacKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("AUTH_STATE_HMAC_KEY must be at least 32 bytes");
        }
        try {
            byte[] replayKey = Base64.getDecoder().decode(properties.replayEncryptionKey());
            if (replayKey.length != 16 && replayKey.length != 24 && replayKey.length != 32) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("AUTH_STATE_REPLAY_ENCRYPTION_KEY must be a base64 AES key");
        }
    }
}
