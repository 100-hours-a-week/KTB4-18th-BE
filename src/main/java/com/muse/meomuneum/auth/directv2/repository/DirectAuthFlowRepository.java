package com.muse.meomuneum.auth.directv2.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;

@Repository
public class DirectAuthFlowRepository {
    private final JdbcTemplate jdbc;

    public DirectAuthFlowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DirectAuthFlowState> lock(String flowId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A transaction is required to lock a flow");
        }
        return jdbc.query("SELECT * FROM auth_browser_flows WHERE flow_id = ? FOR UPDATE", this::map, flowId)
                .stream().findFirst();
    }

    private DirectAuthFlowState map(ResultSet row, int index) throws SQLException {
        return new DirectAuthFlowState(row.getString("flow_id"), row.getString("flow_key_hash"),
                row.getLong("flow_epoch"), row.getLong("revision"), row.getString("lifecycle"),
                row.getString("active_session_sid"), row.getLong("flow_cookie_generation"),
                row.getLong("binding_cookie_generation"), row.getBytes("binding_proof_hmac"),
                row.getObject("binding_cookie_expires_at", java.time.LocalDateTime.class),
                row.getString("active_login_operation_id"), row.getString("current_direct_operation_kind"),
                row.getString("current_direct_operation_id"), row.getString("current_refresh_recovery_operation_id"),
                row.getString("current_refresh_recovery_source_operation_id"),
                row.getString("current_refresh_recovery_status"),
                row.getObject("current_refresh_recovery_lease_expires_at", java.time.LocalDateTime.class),
                row.getObject("current_refresh_recovery_claim_flow_cookie_generation", Long.class),
                row.getObject("current_refresh_recovery_claim_refresh_generation", Long.class),
                row.getObject("current_refresh_recovery_result_flow_cookie_generation", Long.class),
                row.getObject("current_refresh_recovery_result_refresh_generation", Long.class));
    }
}
