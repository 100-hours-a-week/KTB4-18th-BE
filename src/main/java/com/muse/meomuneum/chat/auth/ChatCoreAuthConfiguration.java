package com.muse.meomuneum.chat.auth;

import java.util.ArrayList;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

import com.muse.meomuneum.chat.runtime.ChatTaskIdentity;

@Configuration(proxyBeanMethods = false)
@Import(ChatTaskIdentity.class)
@ConditionalOnProperty(name = "chat.core-auth.enabled", havingValue = "true")
public class ChatCoreAuthConfiguration {
    @Bean(destroyMethod = "close")
    public CoreSessionHttpClient coreSessionHttpClient(@Value("${chat.core-auth.base-url}") String baseUrl,
            @Value("${chat.core-auth.service-token}") String credential, Environment environment,
            @Value("${auth.jwt.secret}") String jwt, @Value("${location.token.secret}") String location,
            @Value("${chat.redis.password:}") String redis, ChatTaskIdentity identity) {
        if (credential.equals(jwt) || credential.equals(location) || credential.equals(redis)) {
            throw new IllegalArgumentException("Core service credential must be separate from user and Redis secrets");
        }
        return new CoreSessionHttpClient(baseUrl, credential,
                environment.acceptsProfiles(Profiles.of("chat-isolated & !prod")), identity.value());
    }

    @Bean
    public ChatCoreSessionVerifier chatCoreSessionVerifier(CoreSessionGateway gateway) {
        return new ChatCoreSessionVerifier(gateway);
    }

    @Bean
    public static BeanPostProcessor chatAuthenticationTransactionGuards() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof AbstractPlatformTransactionManager manager) {
                    var listeners = new ArrayList<>(manager.getTransactionExecutionListeners());
                    listeners.add(new TransactionExecutionListener() {
                        @Override
                        public void beforeBegin(TransactionExecution transaction) {
                            ChatAuthOperation.check();
                        }

                        @Override
                        public void beforeCommit(TransactionExecution transaction) {
                            ChatAuthOperation.check();
                        }
                    });
                    manager.setTransactionExecutionListeners(listeners);
                }
                return bean;
            }
        };
    }
}
