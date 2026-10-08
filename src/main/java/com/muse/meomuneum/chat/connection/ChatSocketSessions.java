package com.muse.meomuneum.chat.connection;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
public class ChatSocketSessions {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void add(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public void close(Set<String> sessionIds, int code, String reason) {
        for (String id : sessionIds) {
            WebSocketSession session = sessions.remove(id);
            if (session != null) {
                try {
                    session.close(new CloseStatus(code, reason));
                } catch (IOException ignored) {
                    // Rights were revoked before closing; a broken transport cannot restore them.
                }
            }
        }
    }
}
