package com.muse.meomuneum.location.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;
import com.muse.meomuneum.location.config.ReverseGeocodingProperties;
import com.muse.meomuneum.location.exception.LocationErrorCode;
import com.muse.meomuneum.location.exception.LocationException;

class KakaoRegionCoordinateResolverTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void usesLegalRegionCodeToResolveSidoAndSigungu() throws IOException {
        startServer("""
                {
                  "documents": [
                    {"region_type":"H","code":"4113565500"},
                    {"region_type":"B","code":"4113511000"}
                  ]
                }
                """, 200);
        KakaoRegionCoordinateResolver resolver = resolver("test-rest-api-key");

        ResolvedRegionCode result = resolver.resolve(37.3595704, 127.105399);

        assertThat(result).isEqualTo(new ResolvedRegionCode("41", "41135"));
    }

    @Test
    void mapsMissingLegalRegionToReverseGeocodingFailure() throws IOException {
        startServer("{\"documents\":[]}", 200);

        assertReverseGeocodingFailed(() -> resolver("test-rest-api-key").resolve(37.0, 127.0));
    }

    @Test
    void mapsUpstreamFailureToReverseGeocodingFailure() throws IOException {
        startServer("{}", 503);

        assertReverseGeocodingFailed(() -> resolver("test-rest-api-key").resolve(37.0, 127.0));
    }

    @Test
    void rejectsCallWhenApiKeyIsMissing() {
        assertReverseGeocodingFailed(() -> resolver("").resolve(37.0, 127.0));
    }

    private KakaoRegionCoordinateResolver resolver(String restApiKey) {
        ReverseGeocodingProperties properties = new ReverseGeocodingProperties(
                baseUrl(),
                restApiKey,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
        RestClient restClient = RestClient.builder().baseUrl(properties.baseUrl()).build();
        return new KakaoRegionCoordinateResolver(restClient, properties);
    }

    private void startServer(String body, int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/local/geo/coord2regioncode.json", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String query = exchange.getRequestURI().getRawQuery();
            if (!"KakaoAK test-rest-api-key".equals(authorization)
                    || query == null
                    || !query.contains("input_coord=WGS84")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
    }

    private String baseUrl() {
        if (server == null) {
            return "http://127.0.0.1:1";
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void assertReverseGeocodingFailed(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(LocationException.class)
                .extracting(exception -> ((LocationException) exception).getErrorCode())
                .isEqualTo(LocationErrorCode.REVERSE_GEOCODING_FAILED);
    }
}
