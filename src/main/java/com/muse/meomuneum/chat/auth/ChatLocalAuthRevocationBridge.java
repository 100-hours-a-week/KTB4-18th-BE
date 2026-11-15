package com.muse.meomuneum.chat.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.chat.connection.ChatSessionLogoutEvent;
import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;
import com.muse.meomuneum.global.security.AuthSessionRevokedEvent;

/** Sid-scoped compatibility for the existing single app; no cross-app Java events are assumed. */
@Component
@ConditionalOnProperty(name = "chat.core-auth.enabled", havingValue = "false", matchIfMissing = true)
public class ChatLocalAuthRevocationBridge {
    private final ChatRoomEntryService entry;

    public ChatLocalAuthRevocationBridge(ChatRoomEntryService entry) {
        this.entry = entry;
    }

    @EventListener
    public void revoked(AuthSessionRevokedEvent event) {
        entry.loggedOut(new ChatSessionLogoutEvent(event.userId(), event.sid()));
    }
}
