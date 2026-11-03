package com.muse.meomuneum.locationrecommendation.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LocationRecommendationConfig {
    private static final Duration MAX_AI_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration BACKEND_TIMEOUT = Duration.ofSeconds(130);

    @Bean
    public LocationRecommendationSettings locationRecommendationSettings(
            @Value("${location-recommendation.ai.base-url}") String baseUrl,
            @Value("${location-recommendation.ai.allowed-origins}") String allowedOrigins,
            @Value("${location-recommendation.ai.connect-timeout:3s}") Duration connectTimeout,
            @Value("${location-recommendation.ai.timeout:120s}") Duration configuredAiTimeout,
            @Value("${location-recommendation.enabled:true}") boolean enabled) {
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || configuredAiTimeout.isZero() || configuredAiTimeout.isNegative()) {
            throw new IllegalStateException("location recommendation timeouts must be positive");
        }
        Origin baseOrigin = origin(baseUrl);
        Set<Origin> permittedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(LocationRecommendationConfig::origin)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (permittedOrigins.isEmpty() || !permittedOrigins.contains(baseOrigin)) {
            throw new IllegalStateException("location recommendation AI origin is not allowlisted");
        }
        if (permittedOrigins.stream().anyMatch(value -> "http".equals(value.scheme())
                && !isInternalDockerHost(value.host()))) {
            throw new IllegalStateException("HTTP location recommendation AI origins must be internal services");
        }
        URI endpoint = URI.create(baseOrigin.uri() + "/v1/location/recommendations");
        return new LocationRecommendationSettings(endpoint, configuredAiTimeout.compareTo(MAX_AI_TIMEOUT) > 0
                ? MAX_AI_TIMEOUT
                : configuredAiTimeout, connectTimeout, BACKEND_TIMEOUT, enabled);
    }

    private static Origin origin(String value) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("location recommendation AI origin is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        if (!("https".equals(scheme) || "http".equals(scheme)) || host.isEmpty() || isIpLiteral(host)
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))
                || uri.getPort() < -1 || uri.getPort() > 65_535) {
            throw new IllegalStateException("location recommendation AI origin is invalid");
        }
        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        if (port == 0) {
            throw new IllegalStateException("location recommendation AI origin is invalid");
        }
        return new Origin(scheme, host, port);
    }

    private static boolean isIpLiteral(String host) {
        return host.contains(":") || host.matches("[0-9.]+");
    }

    private static boolean isInternalDockerHost(String host) {
        return "ai-server".equals(host) || host.endsWith(".internal") || host.endsWith(".docker.internal");
    }

    private record Origin(String scheme, String host, int port) {
        private String uri() {
            int defaultPort = "https".equals(scheme) ? 443 : 80;
            return scheme + "://" + host + (port == defaultPort ? "" : ":" + port);
        }
    }

    @Bean(name = "locationRecommendationHttpExecutor", destroyMethod = "shutdownNow")
    public ExecutorService locationRecommendationHttpExecutor() {
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "location-recommendation-http");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
                threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "locationRecommendationContextExecutor", destroyMethod = "shutdownNow")
    public ExecutorService locationRecommendationContextExecutor() {
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "location-recommendation-context");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "locationRecommendationHttpClient")
    public HttpClient locationRecommendationHttpClient(LocationRecommendationSettings settings,
            @org.springframework.beans.factory.annotation.Qualifier("locationRecommendationHttpExecutor") ExecutorService executor) {
        return HttpClient.newBuilder().executor(executor).connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
