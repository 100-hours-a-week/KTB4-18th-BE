package com.muse.meomuneum.auth.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.user.repository.UserRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "core.chat-auth.enabled", havingValue = "true")
public class CoreInternalAuthConfiguration {
    @Bean(destroyMethod = "close")
    public CoreSessionValidationService coreSessionValidationService(HazelcastAuthStateStore sessions,
            UserRepository users) {
        return new CoreSessionValidationService(sessions, users);
    }

    @Bean
    public FilterRegistrationBean<CoreInternalAuthFilter> coreInternalAuthFilter(
            CoreSessionValidationService validation,
            @Value("${core.chat-auth.service-token-current}") String current,
            @Value("${core.chat-auth.service-token-next:}") String next,
            @Value("${auth.jwt.secret}") String jwt, @Value("${location.token.secret}") String location,
            @Value("${chat.redis.password:}") String redis) {
        for (String key : java.util.List.of(current, next)) {
            if (!key.isEmpty() && (key.equals(jwt) || key.equals(location) || key.equals(redis))) {
                throw new IllegalArgumentException(
                        "Chat service credential must be separate from user and Redis secrets");
            }
        }
        var registration = new FilterRegistrationBean<>(new CoreInternalAuthFilter(current, next, validation));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
