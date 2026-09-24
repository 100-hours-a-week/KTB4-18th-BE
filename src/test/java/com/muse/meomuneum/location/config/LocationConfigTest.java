package com.muse.meomuneum.location.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class LocationConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LocationConfig.class)
            .withPropertyValues(
                    "location.token.secret=test-only-location-token-secret-at-least-32-bytes",
                    "location.reverse-geocoding.base-url=https://dapi.kakao.com",
                    "location.reverse-geocoding.rest-api-key=test-only-key",
                    "location.reverse-geocoding.connect-timeout=2s",
                    "location.reverse-geocoding.read-timeout=3s"
            );

    @Test
    void createsRestClientWithoutAutoConfiguredBuilderBean() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RestClient.class);
            assertThat(context).doesNotHaveBean(RestClient.Builder.class);
        });
    }
}
