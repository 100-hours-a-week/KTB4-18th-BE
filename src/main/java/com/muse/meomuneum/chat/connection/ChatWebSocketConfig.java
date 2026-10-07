package com.muse.meomuneum.chat.connection;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Configuration
@EnableWebSocketMessageBroker
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final ChatStompInterceptor interceptor;
    private final ChatSubscriptionReceipts receipts;
    private final ChatPresenceRegistry presence;
    private final ChatSocketSessions sockets;
    private final String[] origins;
    private final ThreadPoolTaskScheduler scheduler;

    public ChatWebSocketConfig(ChatStompInterceptor interceptor, ChatPresenceRegistry presence,
            ChatSocketSessions sockets, ChatSubscriptionReceipts receipts,
            @Value("${auth.cors.allowed-origins}") String allowedOrigins,
            ThreadPoolTaskScheduler chatHeartbeatScheduler) {
        this.interceptor = interceptor;
        this.receipts = receipts;
        this.presence = presence;
        this.sockets = sockets;
        this.origins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).toArray(String[]::new);
        this.scheduler = chatHeartbeatScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(new ChatStompErrorHandler());
        registry.addEndpoint("/ws").setAllowedOrigins(origins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setPreservePublishOrder(true);
        registry.enableSimpleBroker("/topic", "/queue").setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(scheduler);
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(interceptor, receipts);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setTimeToFirstMessage(10000);
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sockets.add(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sockets.remove(session.getId());
                presence.disconnect(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        });
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        presence.disconnect(event.getSessionId());
    }
}
