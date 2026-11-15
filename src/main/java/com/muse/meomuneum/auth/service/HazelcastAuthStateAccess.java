package com.muse.meomuneum.auth.service;

import org.springframework.beans.factory.ObjectProvider;

import com.hazelcast.core.HazelcastInstance;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

final class HazelcastAuthStateAccess {
    private final ObjectProvider<HazelcastInstance> hazelcastProvider;
    private final AuthStateProperties properties;

    HazelcastAuthStateAccess(ObjectProvider<HazelcastInstance> hazelcastProvider, AuthStateProperties properties) {
        this.hazelcastProvider = hazelcastProvider;
        this.properties = properties;
    }

    HazelcastInstance requireHazelcast() {
        HazelcastInstance instance = hazelcastProvider.getIfAvailable();
        if (instance == null || !instance.getLifecycleService().isRunning()) {
            throw new AuthStateUnavailableException();
        }
        try {
            int members = instance.getCluster().getMembers().size();
            if (members < properties.minimumHazelcastMembers()
                    || members > properties.expectedHazelcastMembers()) {
                throw new AuthStateUnavailableException();
            }
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        return instance;
    }

    RuntimeException unavailable(RuntimeException exception) {
        if (exception instanceof AuthStateUnavailableException) {
            return exception;
        }
        return new AuthStateUnavailableException(exception);
    }
}
