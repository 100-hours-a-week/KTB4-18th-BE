package com.muse.meomuneum.chat.connection;

import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.message.dto.ChatMessageEvent;
import com.muse.meomuneum.chat.message.query.ChatMessageQueryService;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;

/** The simple broker has no native receipts; acknowledge only after it registers the subscription. */
@Component
public class ChatSubscriptionReceipts implements ExecutorChannelInterceptor {
    private final ObjectProvider<MessageChannel> outbound;
    private final ChatPresenceRegistry presence;
    private final ChatViewLifecycle views;
    private final ObjectMapper mapper;
    private final ChatMessageQueryService queries;

    public ChatSubscriptionReceipts(@Qualifier("clientOutboundChannel") ObjectProvider<MessageChannel> outbound,
            ChatPresenceRegistry presence, ChatViewLifecycle views, ObjectMapper mapper,
            ChatMessageQueryService queries) {
        this.outbound = outbound;
        this.presence = presence;
        this.views = views;
        this.mapper = mapper;
        this.queries = queries;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
            Exception exception) {
        if (exception != null || !(handler instanceof SimpleBrokerMessageHandler)) {
            return;
        }
        StompHeaderAccessor incoming = StompHeaderAccessor.wrap(message);
        if (incoming.getCommand() == StompCommand.UNSUBSCRIBE) {
            presence.exclusive(() -> {
                views.unsubscribed(incoming.getSessionId(), incoming.getSubscriptionId());
                presence.unsubscribed(incoming.getSessionId(), incoming.getSubscriptionId());
                return null;
            });
            return;
        }
        if (incoming.getCommand() != StompCommand.SUBSCRIBE
                || incoming.getDestination() == null
                || !(incoming.getDestination().startsWith("/topic/")
                        || incoming.getDestination().startsWith("/queue/"))) {
            return;
        }
        SimpleBrokerMessageHandler broker = (SimpleBrokerMessageHandler) handler;
        SimpMessageHeaderAccessor lookup = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        lookup.setDestination(incoming.getDestination());
        if (!broker.getSubscriptionRegistry().findSubscriptions(
                MessageBuilder.createMessage(new byte[0], lookup.getMessageHeaders()))
                .getOrDefault(incoming.getSessionId(), java.util.List.of()).contains(incoming.getSubscriptionId())) {
            return; // A duplicate id or rejected registration must not start a reading interval.
        }
        presence.exclusive(() -> {
            String viewId = views.subscribed(incoming.getSessionId(), incoming.getSubscriptionId(),
                    incoming.getDestination());
            presence.subscribed(incoming.getSessionId(), incoming.getSubscriptionId(), incoming.getDestination());
            presence.sendSnapshot(incoming.getSessionId(), incoming.getSubscriptionId(), incoming.getDestination());
            if (viewId != null) {
                sendViewReady(incoming, viewId);
            }
            return null;
        });
        if (incoming.getReceipt() == null) {
            return;
        }
        StompHeaderAccessor receipt = StompHeaderAccessor.create(StompCommand.RECEIPT);
        receipt.setSessionId(incoming.getSessionId());
        receipt.setReceiptId(incoming.getReceipt());
        outbound.getObject().send(MessageBuilder.createMessage(new byte[0], receipt.getMessageHeaders()));
    }

    private void sendViewReady(StompHeaderAccessor incoming, String viewId) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(incoming.getSessionId());
        headers.setSubscriptionId(incoming.getSubscriptionId());
        headers.setDestination(incoming.getDestination());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setHeader("chatRecipients", Set.of(incoming.getSessionId()));
        headers.setHeader("chatRoomId",
                Long.valueOf(incoming.getDestination().substring("/topic/chat-rooms/".length())));
        try {
            byte[] body = mapper.writeValueAsBytes(new ChatMessageEvent("CHAT_VIEW_READY",
                    Map.of("view_session_id", viewId)));
            outbound.getObject().send(MessageBuilder.createMessage(body, headers.getMessageHeaders()));
            Object last = incoming.getSessionAttributes() == null
                    ? null
                    : incoming.getSessionAttributes().get("chat.last_message_id");
            byte[] ready = mapper.writeValueAsBytes(new ChatMessageEvent("CHAT_READY",
                    queries.ready(incoming.getSessionId(), viewId, last instanceof Long id ? id : 0)));
            outbound.getObject().send(MessageBuilder.createMessage(ready, headers.getMessageHeaders()));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("chat view serialization failed");
        }
    }
}
