package com.muse.apps.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.muse.meomuneum.auth.config.AuthStateProperties;

@SpringBootApplication(scanBasePackages = {"com.muse.meomuneum", "com.muse.apps.core"})
@EntityScan("com.muse.meomuneum")
@EnableJpaRepositories("com.muse.meomuneum")
@EnableConfigurationProperties(AuthStateProperties.class)
@EnableScheduling
public class CoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
