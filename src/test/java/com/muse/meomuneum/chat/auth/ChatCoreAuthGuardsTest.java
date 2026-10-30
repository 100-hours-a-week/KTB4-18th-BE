package com.muse.meomuneum.chat.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatMessageOutboundGuard;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;

class ChatCoreAuthGuardsTest {
    @Test
    void expirationAtCommitRollsBackAndQueuedOutboundIsCheckedAgain() {
        var pair = new SessionPair(1, UUID.randomUUID().toString());
        AtomicLong time = new AtomicLong();
        var verifier = new ChatCoreSessionVerifier(pairs -> List.of(new SessionDecision(pair, true, 1, 10000)),
                time::get);
        verifier.fresh(1, pair.sid());
        AtomicBoolean committed = new AtomicBoolean();
        AtomicBoolean rolledBack = new AtomicBoolean();
        var manager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }
            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }
            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                committed.set(true);
            }
            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                rolledBack.set(true);
            }
        };
        ChatCoreAuthConfiguration.chatAuthenticationTransactionGuards().postProcessAfterInitialization(manager, "test");
        try (var scope = new ChatAuthOperation(verifier, pair)) {
            assertThrows(ChatAuthUnavailableException.class, () -> new TransactionTemplate(manager).execute(status -> {
                time.set(TimeUnit.SECONDS.toNanos(4));
                return null;
            }));
        }
        assertFalse(committed.get());
        assertTrue(rolledBack.get());
        time.set(0);
        var registry = new ChatAuthSessionRegistry();
        registry.register("socket", new ChatAuthSessionRegistry.Context(1L, pair.sid()));
        var guard = new ChatMessageOutboundGuard(mock(ChatPresenceRegistry.class));
        guard.setCoreVerifier(verifier, registry);
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId("socket");
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        assertTrue(guard.preSend(message, null) != null);
        time.set(TimeUnit.SECONDS.toNanos(4));
        assertNull(guard.beforeHandle(message, null, null));
    }
}
