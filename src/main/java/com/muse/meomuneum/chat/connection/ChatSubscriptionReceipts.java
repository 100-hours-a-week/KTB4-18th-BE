package com.muse.meomuneum.chat.connection;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/** The simple broker has no native receipts; acknowledge only after it registers the subscription. */
@Component
public class ChatSubscriptionReceipts implements ExecutorChannelInterceptor {
    private final ObjectProvider<MessageChannel> outbound;

    public ChatSubscriptionReceipts(@Qualifier("clientOutboundChannel") ObjectProvider<MessageChannel> outbound) {
        this.outbound = outbound;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
            Exception exception) {
        if (exception != null || !(handler instanceof SimpleBrokerMessageHandler)) {
            return;
        }
        StompHeaderAccessor incoming = StompHeaderAccessor.wrap(message);
        if (incoming.getCommand() != StompCommand.SUBSCRIBE || incoming.getReceipt() == null
                || incoming.getDestination() == null
                || !(incoming.getDestination().startsWith("/topic/")
                        || incoming.getDestination().startsWith("/queue/"))) {
            return;
        }
        StompHeaderAccessor receipt = StompHeaderAccessor.create(StompCommand.RECEIPT);
        receipt.setSessionId(incoming.getSessionId());
        receipt.setReceiptId(incoming.getReceipt());
        outbound.getObject().send(MessageBuilder.createMessage(new byte[0], receipt.getMessageHeaders()));
    }
}
