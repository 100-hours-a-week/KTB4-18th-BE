package com.muse.meomuneum.auth.queue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.security.AuthSessionRevokedEvent;

class AuthFlowOutboxProcessorTest {
    @Test
    void acknowledgesQueueEventsWithoutHazelcastOrFlowMaterializationAndContinuesToSessionEvents() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        HazelcastAuthStateStore store = mock(HazelcastAuthStateStore.class);
        AuthFlowService flowService = mock(AuthFlowService.class);
        org.springframework.context.ApplicationEventPublisher events = mock(
                org.springframework.context.ApplicationEventPublisher.class);
        when(transactions.getTransaction(any())).thenReturn(transactionStatus);
        doNothing().when(transactions).commit(any());
        doNothing().when(transactions).rollback(any());
        when(flowService.shouldApplyOutboxAction(anyString(), any(Long.class), any(Long.class), anyString(),
                anyString()))
                .thenReturn(true);
        String sessionId = java.util.UUID.randomUUID().toString();
        when(flowService.sessionOwnerUserId(sessionId)).thenReturn(42L);
        AtomicInteger reads = new AtomicInteger();
        when(jdbc.queryForObject(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            int index = reads.getAndIncrement();
            if (index > 1) {
                throw new EmptyResultDataAccessException(1);
            }
            String kind = index == 0 ? "QUEUE_CLEANUP" : "SESSION_REVOKED_LOGOUT";
            String target = index == 0 ? "ticket-1" : sessionId;
            ResultSet row = mock(ResultSet.class);
            when(row.getLong("event_id")).thenReturn((long) index + 1);
            when(row.getLong("flow_epoch")).thenReturn(1L);
            when(row.getLong("revision")).thenReturn(2L);
            when(row.getString("flow_id")).thenReturn("flow-1");
            when(row.getString("event_kind")).thenReturn(kind);
            when(row.getString("target_id")).thenReturn(target);
            when(row.getString("claim_id")).thenReturn(null);
            return ((RowMapper<?>) invocation.getArgument(1)).mapRow(row, 0);
        });

        new AuthFlowOutboxProcessor(jdbc, transactions, store, flowService, events).processPending();

        verify(flowService, never()).shouldApplyOutboxAction("flow-1", 1L, 2L, "QUEUE_CLEANUP", "ticket-1");
        verify(store, never()).getSession("ticket-1");
        verify(store).revokeSession(sessionId, null);
        verify(flowService).shouldApplyOutboxAction("flow-1", 1L, 2L, "SESSION_REVOKED_LOGOUT", sessionId);
        verify(events).publishEvent(AuthSessionRevokedEvent.invalidation(42L, sessionId));
    }
}
