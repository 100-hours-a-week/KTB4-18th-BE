package com.muse.meomuneum.chat.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.global.security.InternalSessionProtocol;
import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;
import com.sun.net.httpserver.HttpServer;

class CoreSessionHttpClientTest {
    @Test
    void totalDeadlineStatusSchemaBodyLimitsAndRedirectsFailWithoutUserUnauthorized() throws Exception {
        String credential = "synthetic-http-service-secret-at-least-32";
        var pair = new SessionPair(1, UUID.randomUUID().toString());
        var mode = new AtomicInteger();
        var forwarded = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/internal/v1/auth/sessions/validate", exchange -> {
                assertEquals("Bearer " + credential, exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] response = InternalSessionProtocol
                        .encodeResponse(List.of(new SessionDecision(pair, true, 1, 10000)));
                int status = 200;
                if (mode.get() == 1) {
                    status = 401;
                }
                if (mode.get() == 2) {
                    status = 503;
                }
                if (mode.get() == 3) {
                    response = "x".repeat(65537).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                if (mode.get() == 4) {
                    status = 302;
                    exchange.getResponseHeaders().set("Location", "/redirect");
                }
                if (mode.get() == 5) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                try {
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/redirect", exchange -> {
                forwarded.incrementAndGet();
                exchange.close();
            });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            try (var client = new CoreSessionHttpClient(url, credential, true)) {
                assertTrue(client.validate(List.of(pair)).getFirst().active());
                for (int value : List.of(1, 2, 3, 4, 5)) {
                    mode.set(value);
                    long started = System.nanoTime();
                    assertThrows(ChatAuthUnavailableException.class, () -> client.validate(List.of(pair)));
                    assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1400));
                }
                assertEquals(0, forwarded.get());
            } finally {
                server.stop(0);
            }
            assertThrows(IllegalArgumentException.class, () -> new CoreSessionHttpClient(url, credential, false));
            assertThrows(IllegalArgumentException.class,
                    () -> new CoreSessionHttpClient("http://example.test", credential, true));
        }
    }
}
