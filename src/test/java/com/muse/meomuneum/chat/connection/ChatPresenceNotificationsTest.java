package com.muse.meomuneum.chat.connection;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.participation.ChatParticipationService;

class ChatPresenceNotificationsTest {
    @Test
    void noSubscribersNeedNoDatabaseRead() {
        var presence = new ChatPresenceRegistry(Clock.systemUTC());
        var channel = mock(MessageChannel.class);
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("outbound", channel);
        var service = mock(ChatParticipationService.class);
        var notifications = new ChatPresenceNotifications(presence, factory.getBeanProvider(MessageChannel.class),
                new ObjectMapper());
        notifications.setParticipation(service);
        notifications.changed(new ChatPresenceChangedEvent(10L, 0, 1, null, null));
        org.mockito.Mockito.verifyNoInteractions(service, channel);
    }

    @Test
    void failedEnqueueReadClosesAuthorityAndDoesNotPublish() {
        var presence = new ChatPresenceRegistry(Clock.systemUTC());
        presence.connect(1L, 10L, 100L, 25, "observer");
        presence.subscribed("observer", "sub", "/topic/chat-rooms/10");
        var channel = mock(MessageChannel.class);
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("outbound", channel);
        var service = mock(ChatParticipationService.class);
        var unavailable = new IllegalStateException("database unavailable");
        when(service.issueSocketEnqueuePermissions(10L)).thenThrow(unavailable);
        var notifications = new ChatPresenceNotifications(presence, factory.getBeanProvider(MessageChannel.class),
                new ObjectMapper());
        notifications.setParticipation(service);
        notifications.changed(new ChatPresenceChangedEvent(10L, 2, 1, null, null));
        verify(service).failClosed(org.mockito.ArgumentMatchers.eq("presence_enqueue_permission"),
                org.mockito.ArgumentMatchers.same(unavailable), org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verifyNoInteractions(channel);
    }

    @Test
    void oneRoomReadIsSharedAcrossAllEnqueueFrames() {
        var presence = new ChatPresenceRegistry(Clock.systemUTC());
        presence.connect(1L, 10L, 100L, 25, "one");
        presence.connect(2L, 10L, 200L, 25, "two");
        presence.subscribed("one", "sub", "/topic/chat-rooms/10");
        presence.subscribed("two", "sub", "/topic/chat-rooms/10");
        var channel = mock(MessageChannel.class);
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("outbound", channel);
        var service = mock(ChatParticipationService.class);
        var proof = mock(ChatParticipationService.SocketEnqueuePermissions.class);
        when(service.issueSocketEnqueuePermissions(10L)).thenReturn(proof);
        var notifications = new ChatPresenceNotifications(presence, factory.getBeanProvider(MessageChannel.class),
                new ObjectMapper());
        notifications.setParticipation(service);
        notifications.changed(new ChatPresenceChangedEvent(10L, 2, 1, null, null));
        verify(service, times(1)).issueSocketEnqueuePermissions(10L);
        var frames = ArgumentCaptor.forClass(Message.class);
        verify(channel, times(2)).send(frames.capture());
        frames.getAllValues()
                .forEach(frame -> assertSame(proof, frame.getHeaders().get("chatSocketEnqueuePermissions")));
    }
}
