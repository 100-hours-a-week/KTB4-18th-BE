package com.muse.meomuneum.chat.message.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;
import com.muse.meomuneum.chat.message.policy.ChatContentPolicy;
import com.muse.meomuneum.chat.message.policy.ChatMessageMasking;
import com.muse.meomuneum.chat.message.policy.ChatTransmissionPolicy;
import com.muse.meomuneum.chat.message.repository.ChatRoomMessageRepository;
import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;

class ChatMessageDeliveryServiceTest {
    @Test
    void distributedSaveAndAcknowledgementNeverHoldTheGlobalPresenceMonitor() throws Exception {
        var presence = new ChatPresenceRegistry(Clock.systemUTC());
        presence.connect(1L, 10L, 100L, 1000, "socket");
        presence.subscribed("socket", "room", "/topic/chat-rooms/10");
        var policy = mock(ChatContentPolicy.class);
        when(policy.validateText(anyString())).thenAnswer(call -> call.getArgument(0));
        var masking = mock(ChatMessageMasking.class);
        when(masking.mask(anyString())).thenAnswer(call -> call.getArgument(0));
        var broker = mock(SimpMessagingTemplate.class);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("broker", broker);
        var service = new ChatMessageDeliveryService(presence, mock(ChatRoomEntryService.class),
                mock(ChatSocketSessions.class), policy, masking, mock(ChatTransmissionPolicy.class),
                mock(ChatMessageStorageService.class), mock(ChatRoomMessageRepository.class), new ObjectMapper(),
                beans.getBeanProvider(SimpMessagingTemplate.class), mock(PlatformTransactionManager.class));
        var distributed = mock(DistributedChatMessageService.class);
        service.setDistributed(distributed);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(distributed.send(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    assertFalse(Thread.holdsLock(presence));
                    entered.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                    return new DistributedChatMessageService.Saved(new ChatMessageResponse(1L, call.getArgument(4),
                            10L, 1L, "sender", "text", OffsetDateTime.now()), false);
                });
        org.mockito.Mockito.doAnswer(call -> {
            assertFalse(Thread.holdsLock(presence));
            return null;
        }).when(broker).convertAndSendToUser(anyString(), anyString(), any(Object.class),
                org.mockito.ArgumentMatchers.<String, Object>anyMap());
        byte[] body = new ObjectMapper().writeValueAsBytes(Map.of(
                "client_message_id", UUID.randomUUID().toString(), "content", "text"));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = workers.submit(() -> service.send(1L, 10L, 100L, "socket",
                    "/app/chat-rooms/10/messages", body));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(workers.submit(() -> presence.exclusive(() -> true)).get(500, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            pending.get(1, TimeUnit.SECONDS);
        }
        org.mockito.Mockito.verify(broker).convertAndSendToUser(anyString(), anyString(), any(Object.class),
                org.mockito.ArgumentMatchers.<String, Object>anyMap());
    }
}
