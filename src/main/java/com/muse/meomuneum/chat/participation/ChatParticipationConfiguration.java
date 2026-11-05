package com.muse.meomuneum.chat.participation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.muse.meomuneum.chat.connection.ChatSessionAccessPolicy;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.runtime.ChatTaskIdentity;
import com.muse.meomuneum.chat.view.ChatViewStorageService;

@Configuration(proxyBeanMethods = false)
@Import(ChatTaskIdentity.class)
@ConditionalOnProperty(name = "chat.distributed-presence.enabled", havingValue = "true")
public class ChatParticipationConfiguration {
    @Bean
    public ChatParticipationProjection chatParticipationProjection(ChatRedisConnection connection,
            ChatRedisChannels channels) {
        return new RedisChatParticipationProjection(connection, channels);
    }

    @Bean(initMethod = "start")
    public ChatParticipationService chatParticipationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ChatViewStorageService storage, ChatSessionAccessPolicy access, ChatParticipationProjection projection,
            ApplicationEventPublisher events, ChatSocketSessions sockets,
            @Value("${chat.redis.environment}") String environment,
            @Value("${chat.core-auth.enabled:false}") boolean coreAuth, ChatTaskIdentity identity) {
        if (!coreAuth) {
            throw new IllegalArgumentException("Distributed participation requires Core session verification");
        }
        return new ChatParticipationService(jdbc, manager, storage, access, projection, events, sockets, environment,
                identity.value());
    }
}
