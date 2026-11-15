package com.muse.meomuneum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.muse.meomuneum.auth.config.AuthStateProperties;

@SpringBootApplication
@EnableConfigurationProperties(AuthStateProperties.class)
@EnableScheduling
public class MeomuneumBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeomuneumBackendApplication.class, args);
    }

}
