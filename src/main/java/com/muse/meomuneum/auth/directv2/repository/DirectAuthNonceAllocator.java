package com.muse.meomuneum.auth.directv2.repository;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

@Repository
public class DirectAuthNonceAllocator {
    public interface RestoreSafetyGate {
        void requireSafe(String keyId);
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RestoreSafetyGate safetyGate;

    @Autowired
    public DirectAuthNonceAllocator(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ObjectProvider<RestoreSafetyGate> provider) {
        this(jdbc, manager, provider.getIfUnique());
    }

    public DirectAuthNonceAllocator(JdbcTemplate jdbc, PlatformTransactionManager manager, RestoreSafetyGate gate) {
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

    public byte[] reserve(String keyId) {
        try {
            if (safetyGate == null) {
                throw new AuthStateUnavailableException();
            }
            safetyGate.requireSafe(keyId);
            byte[] nonce = transactions.execute(status -> {
                verifyKey(keyId, true);
                List<Allocation> rows = jdbc.query(
                        "SELECT next_iv, exhausted FROM auth_replay_iv_allocators WHERE key_id = ? FOR UPDATE",
                        (result, row) -> new Allocation(result.getBytes(1), result.getBoolean(2)), keyId);
                if (rows.size() != 1 || rows.getFirst().exhausted() || rows.getFirst().next().length != 12) {
                    throw new AuthStateUnavailableException();
                }
                byte[] issued = rows.getFirst().next();
                byte[] next = issued.clone();
                boolean exhausted = true;
                for (int index = next.length - 1; index >= 0; index--) {
                    next[index]++;
                    if (next[index] != 0) {
                        exhausted = false;
                        break;
                    }
                }
                if (jdbc.update("UPDATE auth_replay_iv_allocators SET next_iv = ?, exhausted = ? WHERE key_id = ?",
                        exhausted ? issued : next, exhausted, keyId) != 1) {
                    throw new AuthStateUnavailableException();
                }
                return issued;
            });
            if (nonce == null) {
                throw new AuthStateUnavailableException();
            }
            return nonce;
        } catch (AuthStateUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthStateUnavailableException(failure);
        }
    }

    private record Allocation(byte[] next, boolean exhausted) {
    }
}
