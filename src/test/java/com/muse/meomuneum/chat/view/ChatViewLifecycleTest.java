package com.muse.meomuneum.chat.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;

class ChatViewLifecycleTest {
    private final ChatPresenceRegistry presence = new ChatPresenceRegistry(Clock.systemUTC());
    private final ChatViewStorageService storage = mock(ChatViewStorageService.class);
    private final ChatViewLifecycle views = new ChatViewLifecycle(presence, storage);

    @Test
    void connectAndPrivateSubscriptionDoNotStartAViewAndCannotAuthorizeRecovery() {
        connect("one", null);
        views.subscribed("one", "private", "/queue/chat-events-userone");
        verify(storage, never()).subscribed(1L, 2L, 3L, null);
        assertThrows(ChatRoomException.class, () -> views.authorize(1L, 2L, 3L, "view"));
    }

    @Test
    void twoRoomSubscriptionsHaveOneRangeAndOnlyLastUnsubscribeEndsIt() {
        connect("one", null);
        when(storage.subscribed(1L, 2L, 3L, null)).thenReturn("view");
        assertEquals("view", views.subscribed("one", "first", "/topic/chat-rooms/2"));
        views.subscribed("one", "second", "/topic/chat-rooms/2");
        verify(storage, times(1)).subscribed(1L, 2L, 3L, null);
        views.unsubscribed("one", "first");
        verify(storage, never()).unsubscribed("view", 2L);
        views.unsubscribed("one", "second");
        verify(storage).unsubscribed("view", 2L);
        assertThrows(ChatRoomException.class, () -> views.authorize(1L, 2L, 3L, "view"));
    }

    @Test
    void anotherLiveSocketCannotClaimTheSameViewAndDisconnectIsIdempotent() {
        connect("one", null);
        when(storage.subscribed(1L, 2L, 3L, null)).thenReturn("view");
        views.subscribed("one", "room", "/topic/chat-rooms/2");
        presence.connect(1L, 2L, 3L, 25, "two");
        assertThrows(ChatRoomException.class, () -> views.connected(1L, 2L, 3L, "two", "view"));
        views.disconnected(new ChatViewConnectionClosedEvent("one"));
        views.disconnected(new ChatViewConnectionClosedEvent("one"));
        verify(storage, times(1)).disconnected("view");
        assertThrows(ChatRoomException.class, () -> views.authorize(1L, 2L, 3L, "view"));
    }

    @Test
    void onlyBoundLiveViewIsAuthorizedAndEndInvalidatesEverySocket() {
        connect("one", null);
        when(storage.subscribed(1L, 2L, 3L, null)).thenReturn("view");
        views.subscribed("one", "room", "/topic/chat-rooms/2");
        when(storage.authorizedIntervals(1L, 2L, 3L, "view")).thenReturn(List.of());
        assertEquals(List.of(), views.authorize(1L, 2L, 3L, "view"));
        assertThrows(ChatRoomException.class, () -> views.authorize(4L, 2L, 3L, "view"));
        assertThrows(ChatRoomException.class, () -> views.authorize(1L, 5L, 3L, "view"));
        assertThrows(ChatRoomException.class, () -> views.authorize(1L, 2L, 6L, "view"));
        views.ended(new ChatViewMembershipEndedEvent(3L));
        verify(storage).ended(3L);
        assertThrows(ChatRoomException.class, () -> views.authorize(1L, 2L, 3L, "view"));
        views.disconnected(new ChatViewConnectionClosedEvent("one"));
        verify(storage, never()).disconnected("view");
    }

    private void connect(String socket, String view) {
        presence.connect(1L, 2L, 3L, 25, socket);
        views.connected(1L, 2L, 3L, socket, view);
    }
}
