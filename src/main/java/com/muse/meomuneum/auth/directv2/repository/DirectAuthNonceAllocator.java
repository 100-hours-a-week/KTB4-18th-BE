package com.muse.meomuneum.auth.directv2.repository;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.auth.directv2.model.DirectAuthNonceTicket;
import com.muse.meomuneum.auth.directv2.security.DirectAuthNonceAdmissionGuard;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

@Repository
public class DirectAuthNonceAllocator {
    public interface RestoreSafetyGate {
        void requireSafe(String keyId);

        default long lockSafe(String keyId) {
            requireSafe(keyId);
            return 1;
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RestoreSafetyGate safetyGate;
    private final DirectAuthNonceAdmissionGuard admission;

    @Autowired
    public DirectAuthNonceAllocator(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ObjectProvider<RestoreSafetyGate> provider, DirectAuthNonceAdmissionGuard admission) {
        this(jdbc, manager, provider.getIfUnique(), admission);
    }

    public DirectAuthNonceAllocator(JdbcTemplate jdbc, PlatformTransactionManager manager, RestoreSafetyGate gate) {
        this(jdbc, manager, gate, new DirectAuthNonceAdmissionGuard());
    }

    public DirectAuthNonceAllocator(JdbcTemplate jdbc, PlatformTransactionManager manager, RestoreSafetyGate gate,
            DirectAuthNonceAdmissionGuard admission) {
        this.admission = admission;
        this.jdbc = jdbc;
        this.safetyGate = gate;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void verifyKey(String keyId) {
        verifyKey(keyId, false);
    }

    private void verifyKey(String keyId, boolean lock) {
        try {
            List<String> pins = jdbc.queryForList(
                    "SELECT key_id FROM auth_replay_key_pin WHERE singleton_id = 1" + (lock ? " FOR SHARE" : ""),
                    String.class);
            if (pins.size() != 1 || !pins.getFirst().equals(keyId)) {
                throw new AuthStateUnavailableException();
            }
        } catch (AuthStateUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthStateUnavailableException(failure);
        }
    }

    public DirectAuthNonceTicket reserve(String keyId, int count, Runnable proof) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || count < 1 || count > 3) {
            throw new AuthStateUnavailableException();
        }
        try (var permit = admission.acquire()) {
            proof.run();
            if (safetyGate == null) {
                throw new AuthStateUnavailableException();
            }
            DirectAuthNonceTicket ticket = transactions.execute(status -> {
                long generation = safetyGate.lockSafe(keyId);
                verifyKey(keyId, true);
                List<Allocation> rows = jdbc.query(
                        "SELECT next_iv, exhausted FROM auth_replay_iv_allocators WHERE key_id = ? FOR UPDATE",
                        (result, row) -> new Allocation(result.getBytes(1), result.getBoolean(2)), keyId);
                if (rows.size() != 1 || rows.getFirst().exhausted() || rows.getFirst().next().length != 12) {
                    throw new AuthStateUnavailableException();
                }
                byte[] next = rows.getFirst().next().clone();
                List<byte[]> issued = new ArrayList<>();
                boolean exhausted = false;
                for (int nonce = 0; nonce < count; nonce++) {
                    if (exhausted) {
                        throw new AuthStateUnavailableException();
                    }
                    issued.add(next.clone());
                    exhausted = true;
                    for (int index = next.length - 1; index >= 0; index--) {
                        next[index]++;
                        if (next[index] != 0) {
                            exhausted = false;
                            break;
                        }
                    }
                }
                if (jdbc.update("UPDATE auth_replay_iv_allocators SET next_iv = ?, exhausted = ? WHERE key_id = ?",
                        exhausted ? issued.getLast() : next, exhausted, keyId) != 1) {
                    throw new AuthStateUnavailableException();
                }
                return new DirectAuthNonceTicket(keyId, generation, issued);
            });
            if (ticket == null) {
                throw new AuthStateUnavailableException();
            }
            return ticket;
        } catch (org.springframework.dao.DataAccessException failure) {
            throw new AuthStateUnavailableException(failure);
        }
    }

    private record Allocation(byte[] next, boolean exhausted) {
    }
}
