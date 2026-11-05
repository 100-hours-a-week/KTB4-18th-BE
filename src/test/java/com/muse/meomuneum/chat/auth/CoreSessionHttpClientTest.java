package com.muse.meomuneum.chat.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.muse.meomuneum.global.security.InternalSessionProtocol;
import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;
import com.sun.net.httpserver.HttpServer;

class CoreSessionHttpClientTest {
    @Test
    void burstyFreshValidationsRespectTheCoreTaskLimitWithoutRetries() throws Exception {
        String credential = "synthetic-http-service-secret-at-least-32";
        var validation = org.mockito.Mockito.mock(com.muse.meomuneum.auth.internal.CoreSessionValidationService.class);
        org.mockito.Mockito.when(validation.validate(org.mockito.ArgumentMatchers.anyList())).thenAnswer(
                call -> call.<List<SessionPair>>getArgument(0).stream().map(SessionDecision::inactive).toList());
        var filter = new com.muse.meomuneum.auth.internal.CoreInternalAuthFilter(credential, "", validation);
        var requests = new AtomicInteger();
        var rejected = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            server.createContext("/internal/v1/auth/sessions/validate", exchange -> {
                requests.incrementAndGet();
                var request = new org.springframework.mock.web.MockHttpServletRequest("POST",
                        com.muse.meomuneum.auth.internal.CoreInternalAuthFilter.PATH);
                request.setServletPath(com.muse.meomuneum.auth.internal.CoreInternalAuthFilter.PATH);
                request.setContentType("application/json");
                request.addHeader("Authorization", exchange.getRequestHeaders().getFirst("Authorization"));
                request.addHeader("X-Chat-Task-Id", exchange.getRequestHeaders().getFirst("X-Chat-Task-Id"));
                request.setContent(exchange.getRequestBody().readAllBytes());
                var response = new org.springframework.mock.web.MockHttpServletResponse();
                try {
                    filter.doFilter(request, response, (req, res) -> {
                        throw new AssertionError("internal endpoint escaped");
                    });
                } catch (jakarta.servlet.ServletException error) {
                    throw new java.io.IOException(error);
                }
                if (response.getStatus() != 200) {
                    rejected.incrementAndGet();
                }
                byte[] body = response.getContentAsByteArray();
                exchange.sendResponseHeaders(response.getStatus(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try (var client = new CoreSessionHttpClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    credential, true)) {
                // Warm transport/JIT before the burst, without bypassing the real Core rate filter.
                client.validate(List.of(new SessionPair(1, UUID.randomUUID().toString())));
                var attempts = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
                for (long user = 2; user <= 121; user++) {
                    var pair = new SessionPair(user, UUID.randomUUID().toString());
                    attempts.add(workers.submit(() -> {
                        try {
                            return client.validate(List.of(pair)).getFirst().pair().equals(pair);
                        } catch (ChatAuthUnavailableException unavailable) {
                            return false;
                        }
                    }));
                    Thread.sleep(8);
                }
                var outcomes = new java.util.ArrayList<Boolean>();
                for (var attempt : attempts) {
                    outcomes.add(attempt.get(3, TimeUnit.SECONDS));
                }
                assertEquals(121, requests.get(), "each request must be sent once, without a hidden retry");
                assertEquals(0, rejected.get(), "outgoing admission must avoid Core caller_rate rejection");
                assertTrue(outcomes.stream().allMatch(Boolean::booleanValue));
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void busyAdmissionWaitsWithinTheOriginalDeadlineAndDoesNotSendAfterTimeout() throws Exception {
        var pair = new SessionPair(1, UUID.randomUUID().toString());
        var received = new AtomicInteger();
        String taskId = UUID.randomUUID().toString();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/auth/sessions/validate", exchange -> {
            assertEquals(taskId, exchange.getRequestHeaders().getFirst("X-Chat-Task-Id"));
            received.incrementAndGet();
            byte[] response = InternalSessionProtocol
                    .encodeResponse(List.of(new SessionDecision(pair, true, 1, 10000)));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var client = new CoreSessionHttpClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "synthetic-http-service-secret-at-least-32", true, taskId);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            assertTrue(client.validate(List.of(pair)).getFirst().active());
            var permits = (Semaphore) ReflectionTestUtils.getField(client, "permits");
            permits.acquire(16);
            try {
                var started = new CountDownLatch(1);
                var pending = workers.submit(() -> {
                    started.countDown();
                    return client.validate(List.of(pair));
                });
                assertTrue(started.await(1, TimeUnit.SECONDS));
                Thread.sleep(100);
                assertTrue(!pending.isDone(), "a temporary admission spike must not fail immediately");
                permits.release();
                assertTrue(pending.get(800, TimeUnit.MILLISECONDS).getFirst().active());
                permits.acquire();
                int before = received.get();
                long waiting = System.nanoTime();
                assertThrows(ChatAuthUnavailableException.class, () -> client.validate(List.of(pair)));
                long elapsed = System.nanoTime() - waiting;
                assertTrue(elapsed >= TimeUnit.MILLISECONDS.toNanos(900));
                assertTrue(elapsed < TimeUnit.MILLISECONDS.toNanos(1400));
                assertEquals(before, received.get());
            } finally {
                permits.release(16);
            }
        } finally {
            server.stop(0);
        }
    }

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
