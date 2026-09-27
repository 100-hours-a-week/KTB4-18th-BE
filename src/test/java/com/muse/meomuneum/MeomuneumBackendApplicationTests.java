package com.muse.meomuneum;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MeomuneumBackendApplicationTests {

    @Autowired
    private ServerProperties serverProperties;

    @Test
    void contextLoads() {
    }

    @Test
    void bindsSessionTimeoutToFifteenDays() {
        assertThat(serverProperties.getServlet().getSession().getTimeout())
                .isEqualTo(Duration.ofDays(15));
    }
}
