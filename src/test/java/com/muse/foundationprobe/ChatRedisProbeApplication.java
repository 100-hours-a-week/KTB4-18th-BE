package com.muse.foundationprobe;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.MeomuneumBackendApplication;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisSubscription;

import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.micrometer.core.instrument.MeterRegistry;

/** Test-classpath-only entry point. Never included in bootJar or discovered by the product component scan. */
public class ChatRedisProbeApplication {
    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[]{MeomuneumBackendApplication.class, ProbeConfiguration.class}, args);
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        @Order(0)
        SecurityFilterChain probeSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher("/foundation-probe/**", "/actuator/health/**")
                    .csrf(csrf -> csrf.disable()).authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }

        @Bean(destroyMethod = "close")
        ProbeController probeController(ChatRedisConnection connection, ChatRedisChannels channels,
                JdbcTemplate jdbc, MeterRegistry meters) {
            return new ProbeController(connection, channels, jdbc, meters);
        }
    }

    @RestController
    @RequestMapping("/foundation-probe")
    static class ProbeController implements AutoCloseable {
        private final ChatRedisConnection connection;
        private final ChatRedisChannels channels;
        private final JdbcTemplate jdbc;
        private final MeterRegistry meters;
        private final ChatRedisSubscription subscription;
        private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();

        ProbeController(ChatRedisConnection connection, ChatRedisChannels channels, JdbcTemplate jdbc,
                MeterRegistry meters) {
            this.connection = connection;
            this.channels = channels;
            this.jdbc = jdbc;
            this.meters = meters;
            subscription = connection.openSubscription(channels, new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    received.add(Map.of("channel", channel, "body", message));
                }

                @Override
                public void message(String pattern, String channel, String message) {
                    received.add(Map.of("channel", channel, "body", message));
                }
            });
        }

        @GetMapping("/status")
        Map<String, Object> status() {
            return Map.of("pid", ProcessHandle.current().pid(), "database",
                    jdbc.queryForObject("SELECT DATABASE()", String.class), "received", List.copyOf(received),
                    "connected", meters.get("chat.redis.connected").gauge().value(),
                    "subscriptionsReady", subscription.isReady());
        }

        @PostMapping("/publish")
        Map<String, Long> publish(@RequestBody Map<String, String> body) {
            String channel = "control".equals(body.get("kind")) ? channels.controlChannel() : channels.roomChannel(1);
            return Map.of("subscribers", connection.commands().sync().publish(channel, body.get("body")));
        }

        @PostMapping("/marker")
        void marker(@RequestBody String value) {
            jdbc.update("INSERT INTO foundation_probe (marker) VALUES (?)", value);
        }

        @GetMapping("/markers")
        List<String> markers() {
            return jdbc.queryForList("SELECT marker FROM foundation_probe", String.class);
        }

        @Override
        public void close() {
            subscription.close();
        }
    }
}
