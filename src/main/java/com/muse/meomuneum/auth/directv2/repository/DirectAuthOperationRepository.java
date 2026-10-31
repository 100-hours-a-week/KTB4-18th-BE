package com.muse.meomuneum.auth.directv2.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.muse.meomuneum.auth.directv2.model.DirectAuthOperationState;

@Repository
public class DirectAuthOperationRepository {
    private final JdbcTemplate jdbc;
    private final DirectAuthFlowRepository flows;

    public DirectAuthOperationRepository(JdbcTemplate jdbc, DirectAuthFlowRepository flows) {
        this.jdbc = jdbc;
        this.flows = flows;
    }

    public Optional<DirectAuthOperationState> lockOwnedLogin(String flowId, String operationId,
            long startingEpoch, long proofGeneration, byte[] ownerToken) {
        var flow = flows.lock(flowId).orElse(null);
        if (flow == null) {
            return Optional.empty();
        }
        var operation = lockLogin(flowId, operationId).orElse(null);
        if (operation == null) {
            return Optional.empty();
        }
        var databaseTime = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        if (!operationId.equals(flow.activeLoginOperationId())
                || flow.epoch() != startingEpoch || flow.flowCookieGeneration() != proofGeneration
                || !"ANONYMOUS".equals(flow.lifecycle())
                || !operation.ownedBy(ownerToken, startingEpoch, proofGeneration, databaseTime)) {
            return Optional.empty();
        }
        return Optional.of(operation);
    }

    private Optional<DirectAuthOperationState> lockLogin(String flowId, String operationId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A transaction is required");
        }
        return jdbc.query("""
                SELECT * FROM auth_login_operations
                WHERE flow_id = ? AND operation_id = ? FOR UPDATE
                """, this::mapLogin, flowId, operationId).stream().findFirst();
    }

    private DirectAuthOperationState mapLogin(ResultSet row, int index) throws SQLException {
        return new DirectAuthOperationState(row.getString("flow_id"), row.getString("operation_id"),
                row.getLong("starting_epoch"), row.getString("status"), row.getObject("proof_generation", Long.class),
                row.getBytes("owner_token"), row.getObject("lease_expires_at", LocalDateTime.class),
                row.getObject("result_epoch", Long.class), row.getObject("result_revision", Long.class),
                row.getString("result_sid"), row.getObject("receipt_expires_at", LocalDateTime.class));
    }
}
