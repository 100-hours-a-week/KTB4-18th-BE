package com.muse.apps.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.muse.meomuneum.chat.runtime.ChatStartupValidator;

@SpringBootApplication(scanBasePackages = {"com.muse.apps.chat", "com.muse.meomuneum.global.logging",
        "com.muse.meomuneum.global.exception", "com.muse.meomuneum.global.security"})
@EntityScan({"com.muse.meomuneum.chat", "com.muse.meomuneum.user.domain"})
@EnableJpaRepositories({"com.muse.meomuneum.chat", "com.muse.meomuneum.user.repository"})
public class ChatApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ChatApplication.class);
        app.addInitializers(new ChatStartupValidator());
        app.run(args);
    }
}
