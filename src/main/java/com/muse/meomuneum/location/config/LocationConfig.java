package com.muse.meomuneum.location.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({LocationTokenProperties.class, ReverseGeocodingProperties.class})
public class LocationConfig {

    @Bean
    public Clock locationClock() {
        return Clock.systemUTC();
    }

    @Bean("kakaoReverseGeocodingRestClient")
    public RestClient kakaoReverseGeocodingRestClient(ReverseGeocodingProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }
}
