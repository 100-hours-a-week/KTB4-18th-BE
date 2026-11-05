package com.muse.meomuneum.auth.directv2.model;

import java.util.List;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class DirectAuthNonceTicket {
    private final String keyId;
    private final long generation;
    private final List<byte[]> nonces;
    private final Thread owner = Thread.currentThread();
    private int consumed;
    private boolean bound;
    private boolean active;
    private TransactionSynchronization authorization;

    public DirectAuthNonceTicket(String keyId, long generation, List<byte[]> nonces) {
        if (keyId == null || generation <= 0 || nonces.isEmpty() || nonces.size() > 3
                || nonces.stream().anyMatch(iv -> iv == null || iv.length != 12)
                || nonces.stream().map(java.util.HexFormat.of()::formatHex).distinct().count() != nonces.size()) {
            throw DirectAuthFailure.unavailable();
        }
        this.keyId = keyId;
        this.generation = generation;
        this.nonces = nonces.stream().map(byte[]::clone).toList();
    }

    public String keyId() {
        return keyId;
    }

    public synchronized void check(long currentGeneration, String currentKey) {
        if (owner != Thread.currentThread() || bound || currentGeneration != generation || !keyId.equals(currentKey)) {
            throw DirectAuthFailure.unavailable();
        }
    }

    public synchronized void authorize(long currentGeneration, String currentKey) {
        if (owner != Thread.currentThread() || bound || currentGeneration != generation || !keyId.equals(currentKey)
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw DirectAuthFailure.unavailable();
        }
        bound = true;
        active = true;
        authorization = new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                synchronized (DirectAuthNonceTicket.this) {
                    active = false;
                }
            }
        };
        TransactionSynchronizationManager.registerSynchronization(authorization);
    }

    public synchronized byte[] consume(String currentKey) {
        if (owner != Thread.currentThread() || !active || !keyId.equals(currentKey)
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.getSynchronizations().contains(authorization)
                || consumed >= nonces.size()) {
            throw DirectAuthFailure.unavailable();
        }
        return nonces.get(consumed++).clone();
    }
}
