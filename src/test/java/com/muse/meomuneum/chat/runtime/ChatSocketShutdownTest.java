package com.muse.meomuneum.chat.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import com.muse.meomuneum.chat.connection.ChatSocketSessions;

class ChatSocketShutdownTest {
    @Test
    void oneBrokenCloseDoesNotKeepTheOtherTransportsOpen() throws Exception {
        var sockets = new ChatSocketSessions();
        var broken = mock(WebSocketSession.class);
        var healthy = mock(WebSocketSession.class);
        when(broken.getId()).thenReturn("broken");
        when(healthy.getId()).thenReturn("healthy");
        doThrow(new IllegalStateException("already closing")).when(broken).close(any());
        sockets.add(broken);
        sockets.add(healthy);
        sockets.closeAll(1013, "CHAT_SERVICE_UNAVAILABLE");
        verify(healthy).close(any());
        assertEquals(0, sockets.size());
        assertEquals(1, sockets.closeFailures());
    }
}
