package com.muse.apps.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.core.env.Profiles;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.muse.apps.chat", "com.muse.meomuneum.global.logging",
        "com.muse.meomuneum.global.exception", "com.muse.meomuneum.global.security"})
@EntityScan({"com.muse.meomuneum.chat", "com.muse.meomuneum.user.domain"})
@EnableJpaRepositories({"com.muse.meomuneum.chat", "com.muse.meomuneum.user.repository"})
public class ChatApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ChatApplication.class);
        app.addInitializers(context -> {
            var environment = context.getEnvironment();
            if (environment.getProperty("chat.runtime.enabled", Boolean.class, false)
                    && (!environment.acceptsProfiles(Profiles.of("chat-isolated"))
                            || environment.acceptsProfiles(Profiles.of("prod")))) {
                throw new IllegalStateException(
                        "Chat runtime requires isolated verification until Core auth is integrated");
            }
        });
        app.run(args);
    }
}
