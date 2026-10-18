package com.muse.meomuneum.auth.config;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.QueueConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

final class LocalHazelcastMemberLifecycle implements AutoCloseable {

    private static final Pattern LOOPBACK_ADDRESS = Pattern.compile("127\\.0\\.0\\.1:([0-9]{1,5})");

    private final Function<Config, HazelcastInstance> memberFactory;
    private final Function<ClientConfig, HazelcastInstance> clientFactory;
    private HazelcastInstance ownedMember;

    LocalHazelcastMemberLifecycle() {
        this(Hazelcast::newHazelcastInstance, HazelcastClient::newHazelcastClient);
    }

    LocalHazelcastMemberLifecycle(Function<Config, HazelcastInstance> memberFactory,
            Function<ClientConfig, HazelcastInstance> clientFactory) {
        this.memberFactory = memberFactory;
        this.clientFactory = clientFactory;
    }

    synchronized HazelcastInstance connect(ClientConfig clientConfig, AuthStateProperties properties) {
        int port = localPort(clientConfig.getNetworkConfig().getAddresses(), properties.expectedHazelcastMembers());
        if (port == -1) {
            return clientFactory.apply(clientConfig);
        }
        int queueCapacity = Math.addExact(properties.queueCapacity(), properties.localBcryptConcurrency());
        Config memberConfig = memberConfig(clientConfig.getClusterName(), port, queueCapacity);
        clientConfig.getNetworkConfig().setConnectionTimeout(1_000);
        clientConfig.getConnectionStrategyConfig().getConnectionRetryConfig()
                .setClusterConnectTimeoutMillis(5_000).setInitialBackoffMillis(250)
                .setMaxBackoffMillis(1_000).setMultiplier(1).setJitter(0);
        clientConfig.setProperty("hazelcast.client.heartbeat.timeout", "3000");
        clientConfig.setProperty("hazelcast.client.heartbeat.interval", "1000");
        try {
            if (ownedMember == null && isPortAvailable(port)) {
                ownedMember = memberFactory.apply(memberConfig);
            }
            return clientFactory.apply(clientConfig);
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private int localPort(List<String> addresses, int expectedMembers) {
        if (expectedMembers != 1 || addresses.size() != 1) {
            return -1;
        }
        Matcher matcher = LOOPBACK_ADDRESS.matcher(addresses.getFirst());
        if (!matcher.matches()) {
            return -1;
        }
        int port = Integer.parseInt(matcher.group(1));
        return port >= 1 && port <= 65_535 ? port : -1;
    }

    private Config memberConfig(String clusterName, int port, int queueCapacity) {
        Config config = new Config().setClusterName(clusterName);
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
        config.addQueueConfig(new QueueConfig("login-queue-order-v2").setMaxSize(queueCapacity));
        return config;
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            return true;
        } catch (BindException occupied) {
            return false;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot check the local Hazelcast port", failure);
        }
    }

    @Override
    public synchronized void close() {
        if (ownedMember == null) {
            return;
        }
        try {
            ownedMember.shutdown();
        } catch (RuntimeException | Error shutdownFailure) {
            try {
                ownedMember.getLifecycleService().terminate();
            } catch (RuntimeException | Error terminateFailure) {
                if (terminateFailure != shutdownFailure) {
                    shutdownFailure.addSuppressed(terminateFailure);
                }
                throw shutdownFailure;
            }
        }
        ownedMember = null;
    }
}
