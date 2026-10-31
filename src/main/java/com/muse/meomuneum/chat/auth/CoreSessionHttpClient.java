package com.muse.meomuneum.chat.auth;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.muse.meomuneum.global.security.InternalSessionProtocol;
import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;

/** Dedicated credential, no redirects/retries, default JVM TLS verification, total caller deadline. */
public final class CoreSessionHttpClient implements CoreSessionGateway, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CoreSessionHttpClient.class);
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private final URI endpoint;
    private final String credential;
    private final String taskId;
    private final Semaphore permits = new Semaphore(16);
    private final ThreadPoolExecutor transport = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), Thread.ofPlatform().name("chat-core-http-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient client;

    public CoreSessionHttpClient(String baseUrl, String credential, boolean isolated) {
        this(baseUrl, credential, isolated, UUID.randomUUID().toString());
    }

    public CoreSessionHttpClient(String baseUrl, String credential, boolean isolated, String taskId) {
        this.taskId = UUID.fromString(taskId).toString();
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid Core service configuration");
        }
        boolean localHttp = isolated && "http".equals(base.getScheme())
                && List.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost());
        if (!("https".equals(base.getScheme()) || localHttp) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || "/".equals(base.getPath()))
                || credential == null || credential.length() < 32 || credential.length() > 256
                || credential.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("invalid Core service configuration");
        }
        endpoint = base.resolve("/internal/v1/auth/sessions/validate");
        this.credential = credential;
        client = HttpClient.newBuilder().executor(transport).connectTimeout(Duration.ofMillis(500))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public List<SessionDecision> validate(List<SessionPair> pairs) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        boolean acquired = false;
        CompletableFuture<HttpResponse<byte[]>> future = null;
        String stage = "http_admission";
        int status = 0;
        try {
            acquired = permits.tryAcquire(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!acquired || System.nanoTime() - deadline >= 0) {
                throw new ChatAuthUnavailableException();
            }
            stage = "http_request";
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(1))
                    .header("Authorization", "Bearer " + credential).header("Content-Type", "application/json")
                    .header("Cache-Control", "no-store").header("X-Chat-Task-Id", taskId)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(InternalSessionProtocol.encodeRequest(pairs))).build();
            future = client.sendAsync(request, ignored -> new LimitedBody());
            var response = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            status = response.statusCode();
            stage = "http_response";
            if (response.statusCode() != 200 || System.nanoTime() - deadline >= 0) {
                throw new ChatAuthUnavailableException();
            }
            stage = "http_protocol";
            return InternalSessionProtocol.response(response.body(), pairs);
        } catch (Exception failure) {
            diagnostic(stage, pairs.size(), deadline, status, failure.getClass().getSimpleName());
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ChatAuthUnavailableException();
        } finally {
            if (future != null) {
                future.cancel(true);
            }
            if (acquired) {
                permits.release();
            }
        }
    }

    private void diagnostic(String stage, int batchSize, long deadline, int status, String cause) {
        if (failureLogged.compareAndSet(false, true)) {
            log.warn("event=chat_auth_first_http_failure stage={} batch_size={} elapsed_ms={} status={} "
                    + "in_flight={} transport_active={} transport_queued={} cause={}", stage, batchSize,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - deadline + TimeUnit.SECONDS.toNanos(1)),
                    status, 16 - permits.availablePermits(), transport.getActiveCount(), transport.getQueue().size(),
                    cause);
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
        transport.shutdownNow();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 65536 - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new ChatAuthUnavailableException());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(new ChatAuthUnavailableException());
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
