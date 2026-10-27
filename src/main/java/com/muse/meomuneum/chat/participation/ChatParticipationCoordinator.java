package com.muse.meomuneum.chat.participation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.control.ChatControlEventConsumer;

@Component
@ConditionalOnProperty(name = "chat.distributed-presence.enabled", havingValue = "true")
public final class ChatParticipationCoordinator {
    private ChatControlEventConsumer controls;

    @Autowired(required = false)
    public void setControls(ChatControlEventConsumer controls) {
        this.controls = controls;
    }

    private final ChatParticipationService participation;
    private final ChatAuthSessionRegistry registry;
    private final ChatSocketSessions sockets;

    public ChatParticipationCoordinator(ChatParticipationService participation, ChatAuthSessionRegistry registry,
            ChatSocketSessions sockets) {
        this.participation = participation;
        this.registry = registry;
        this.sockets = sockets;
    }

    @Scheduled(fixedDelay = 5000)
    public void heartbeat() {
        participation.heartbeat();
    }

    @Scheduled(fixedDelay = 1000)
    public void sweep() {
        participation.sweep();
    }

    @Scheduled(fixedDelay = 5000)
    public void project() {
        try {
            participation.refreshProjections();
        } catch (RuntimeException unavailable) {
            participation.failClosed();
        }
    }

    @Scheduled(fixedDelay = 250)
    public void closeInvalid() {
        try {
            if (participation.isDraining()) {
                return;
            }
            if (!participation.available()) {
                participation.failClosed();
                return;
            }
            if (controls != null) {
                controls.reconcile();
                return;
            }
            var valid = participation.validSockets();
            registry.snapshot().keySet().stream().filter(socket -> !valid.contains(socket))
                    .forEach(socket -> sockets.close(java.util.Set.of(socket), 4100, "CHAT_LEFT"));
        } catch (RuntimeException unavailable) {
            participation.failClosed();
        }
    }
}
