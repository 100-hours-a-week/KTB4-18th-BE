package com.muse.meomuneum.auth.controller;

import java.time.Duration;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.application.AuthFlowService.FlowSnapshot;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.dto.QueueStatusResponse;
import com.muse.meomuneum.auth.exception.AuthFlowException;
import com.muse.meomuneum.auth.exception.AuthStateOperationInProgressException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth/login-queue")
public class LoginQueueController {

    private static final String COOKIE_PATH = "/api/v1/auth";
    private final HazelcastAuthStateStore store;
    private final AuthStateProperties properties;
    private final boolean secureCookie;
    private final String sameSite;
    private final AuthFlowService flowService;

    @Autowired
    public LoginQueueController(HazelcastAuthStateStore store, AuthStateProperties properties,
            @Value("${auth.cookie.secure:true}") boolean secureCookie,
            @Value("${auth.cookie.same-site:Lax}") String sameSite, AuthFlowService flowService) {
        this.store = store;
        this.properties = properties;
        this.secureCookie = secureCookie;
        this.sameSite = sameSite;
        this.flowService = flowService;
    }

    /** Standalone MVC tests exercise the queue handlers without the DB integration boundary. */
    public LoginQueueController(HazelcastAuthStateStore store, AuthStateProperties properties,
            boolean secureCookie, String sameSite) {
        this(store, properties, secureCookie, sameSite, null);
    }

    @PostMapping("/tickets")
    public ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request) {
        if (flowService == null) {
            return createUnfenced(idempotencyKey, request, null);
        }
        return flowService.withLockedFlow(request,
                flow -> createUnfenced(idempotencyKey, request, flow));
    }

    @GetMapping({"/current", "/tickets/current"})
    public ResponseEntity<ApiResponse<?>> current(HttpServletRequest request) {
        FlowSnapshot flow = flowService == null ? null : flowService.currentFlow(request);
        try {
            HazelcastAuthStateStore.TicketView view = store.currentTicket(cookieValue(request));
            if (flow != null && (flow.currentTicketId() == null
                    || !flow.currentTicketId().equals(view.ticketId()))) {
                return notFound(request, "TICKET_UNKNOWN");
            }
            if (flow != null) {
                flowService.assertFlowSnapshotCurrent(flow);
            }
            ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
            if (flow != null) {
                response.eTag(etag(flow));
                addFlowHeaders(response, flow);
            }
            return response.body(ApiResponse.of("login queue status", response(view)));
        } catch (com.muse.meomuneum.auth.exception.AuthStateExpiredException exception) {
            return ResponseEntity.status(410).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, queueCookie("", 0))
                    .body(ApiResponse.failure("TICKET_EXPIRED"));
        } catch (com.muse.meomuneum.auth.exception.AuthStateNotFoundException exception) {
            return notFound(request, exception.code());
        }
    }

    @DeleteMapping({"/current", "/tickets/current"})
    public ResponseEntity<?> cancel(HttpServletRequest request) {
        if (flowService == null) {
            store.cancelCurrentTicket(cookieValue(request));
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, queueCookie("", 0)).build();
        }
        return flowService.withLockedFlow(request, flow -> cancelLocked(request, flow));
    }

    private ResponseEntity<?> createUnfenced(String idempotencyKey, HttpServletRequest request,
            FlowSnapshot flow) {
        store.enforceTicketCreateRateLimit(request.getRemoteAddr());
        String existingCookie = cookieValue(request);
        if (flow != null && flow.currentTicketId() != null) {
            try {
                HazelcastAuthStateStore.TicketView existing = store.currentTicket(existingCookie);
                if (flow.currentTicketId().equals(existing.ticketId())) {
                    HttpHeaders headers = flowHeaders(flow);
                    return ResponseEntity.ok().headers(headers).cacheControl(CacheControl.noStore())
                            .body(ApiResponse.of("login queue ticket", response(existing)));
                }
            } catch (RuntimeException ignored) {
                // The DB pointer is cleared below; a missing AP materialization cannot become current.
            }
            flow = flowService.clearQueueTicket(flow);
        }
        if (flow != null) {
            return createWithCanonicalIntent(idempotencyKey, request, flow, existingCookie);
        }
        try {
            var result = store.createOrReuseTicket(existingCookie, idempotencyKey);
            HttpHeaders headers = queueHeaders(null, result.queueCookie(), properties.queueCookieSeconds());
            return ResponseEntity.ok().headers(headers).cacheControl(CacheControl.noStore())
                    .body(ApiResponse.of("login queue ticket", response(result.view())));
        } catch (AuthStateOperationInProgressException exception) {
            if (exception.queueCookie() == null) {
                return ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.RETRY_AFTER, "1")
                        .body(ApiResponse.failure("OPERATION_IN_PROGRESS"));
            }
            HttpHeaders headers = queueHeaders(null, exception.queueCookie(), properties.queueCookieSeconds());
            return ResponseEntity.status(202).headers(headers).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(ApiResponse.of("AUTH_QUEUE_CREATE_IN_PROGRESS",
                            new QueueCreatePending(new QueueReceipt(idempotencyKey, "PENDING"))));
        }
    }

    private ResponseEntity<?> createWithCanonicalIntent(String operationId, HttpServletRequest request,
            FlowSnapshot initialFlow, String suppliedCookie) {
        HazelcastAuthStateStore.CreateTicket result = null;
        AuthFlowService.QueueIntentReservation intent = null;
        try {
            intent = flowService.reserveQueueCreate(request, operationId);
            FlowSnapshot reservedFlow = flowService.currentFlow(request);
            try {
                result = store.createOrReuseTicket(suppliedCookie, intent.intentId());
            } catch (AuthStateOperationInProgressException pending) {
                HttpHeaders headers = flowHeaders(reservedFlow);
                if (pending.queueCookie() != null) {
                    headers.add(HttpHeaders.SET_COOKIE,
                            queueCookie(pending.queueCookie(), properties.queueCookieSeconds()));
                }
                return ResponseEntity.status(202).headers(headers).cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.RETRY_AFTER, "1")
                        .body(ApiResponse.of("AUTH_QUEUE_CREATE_IN_PROGRESS",
                                new QueueCreatePending(new QueueReceipt(operationId, "PENDING"))));
            }
            FlowSnapshot committed = flowService.commitQueueCreate(request, intent, result.view().ticketId());
            HttpHeaders headers = queueHeaders(committed, result.queueCookie(), properties.queueCookieSeconds());
            return ResponseEntity.ok().headers(headers).cacheControl(CacheControl.noStore())
                    .body(ApiResponse.of("login queue ticket", response(result.view())));
        } catch (RuntimeException rejected) {
            if (result != null) {
                boolean current;
                try {
                    current = flowService.isQueueTicketCurrent(result.view().ticketId());
                } catch (com.muse.meomuneum.auth.exception.AuthStateUnavailableException statusUnavailable) {
                    // The DB commit outcome is unknown. Keep the reserved intent for the DB reaper to
                    // resolve before touching the Hazelcast ticket; deleting a possibly committed ticket
                    // here could invalidate a queue that already became canonical.
                    rejected.addSuppressed(statusUnavailable);
                    throw rejected;
                }
                if (current) {
                    FlowSnapshot canonical = flowService.currentFlow(request);
                    HttpHeaders headers = queueHeaders(canonical, result.queueCookie(),
                            properties.queueCookieSeconds());
                    return ResponseEntity.ok().headers(headers).cacheControl(CacheControl.noStore())
                            .body(ApiResponse.of("login queue ticket", response(result.view())));
                }
            }
            if (intent != null && intent.ticketId() != null) {
                try {
                    store.cancelTicketById(intent.ticketId());
                } catch (RuntimeException cleanupDeferred) {
                    rejected.addSuppressed(cleanupDeferred);
                }
                try {
                    flowService.enqueueRejectedQueueCleanup(initialFlow.flowId(), initialFlow.flowEpoch(),
                            intent.ticketId());
                } catch (RuntimeException cleanupDeferred) {
                    rejected.addSuppressed(cleanupDeferred);
                }
            }
            throw rejected;
        }
    }

    private ResponseEntity<?> cancelLocked(HttpServletRequest request, FlowSnapshot flow) {
        String operationId = request.getHeader("X-Queue-Cancel-Operation-Id");
        try {
            UUID.fromString(operationId);
        } catch (RuntimeException exception) {
            throw new AuthFlowException("INVALID_OPERATION_ID");
        }
        String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new AuthFlowException("AUTH_FLOW_REVISION_REQUIRED");
        }
        if (!ifMatch.matches("\"[A-Za-z0-9_-]{16,128}:[0-9]+\"")) {
            throw new AuthFlowException("AUTH_FLOW_REVISION_INVALID");
        }
        AuthFlowService.QueueCancelReceipt savedReceipt = flowService.queueCancelReceipt(flow.flowId(), operationId);
        if (savedReceipt != null) {
            if (savedReceipt.flowEpoch() != flow.flowEpoch() || flow.currentTicketId() != null) {
                throw new AuthFlowException("AUTH_QUEUE_CANCEL_SUPERSEDED");
            }
            HttpHeaders headers = flowHeaders(flow);
            headers.set(HttpHeaders.SET_COOKIE, queueCookie("", 0));
            headers.setETag(etag(flow));
            return ResponseEntity.noContent().headers(headers).cacheControl(CacheControl.noStore()).build();
        }
        String expected = etag(flow);
        if (!expected.equals(ifMatch)) {
            HttpHeaders headers = flowHeaders(flow);
            headers.setETag(expected);
            return ResponseEntity.status(412).headers(headers).cacheControl(CacheControl.noStore())
                    .body(ApiResponse.failure("AUTH_FLOW_REVISION_STALE"));
        }
        FlowSnapshot updated = flowService.clearQueueTicket(flow, operationId);
        HttpHeaders headers = flowHeaders(updated);
        headers.set(HttpHeaders.SET_COOKIE, queueCookie("", 0));
        headers.setETag(etag(updated));
        return ResponseEntity.noContent().headers(headers).cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<ApiResponse<?>> notFound(HttpServletRequest request, String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.set(HttpHeaders.SET_COOKIE, queueCookie("", 0));
        if (flowService != null) {
            FlowSnapshot flow = flowService.currentFlow(request);
            headers.set("X-Auth-Flow-Id", flow.flowId());
            headers.set("X-Auth-Flow-Epoch", Long.toString(flow.flowEpoch()));
            headers.set("X-Auth-Flow-Revision", Long.toString(flow.revision()));
        }
        return ResponseEntity.status(404).headers(headers).body(ApiResponse.failure(code));
    }

    private HttpHeaders queueHeaders(FlowSnapshot flow, String cookie, long maxAge) {
        HttpHeaders headers = flow == null ? new HttpHeaders() : flowHeaders(flow);
        headers.add(HttpHeaders.SET_COOKIE, queueCookie(cookie, maxAge));
        return headers;
    }

    private HttpHeaders flowHeaders(FlowSnapshot flow) {
        HttpHeaders headers = new HttpHeaders();
        if (flowService != null) {
            flowService.writeTupleHeaders(headers, flow);
        }
        return headers;
    }

    private void addFlowHeaders(ResponseEntity.BodyBuilder response, FlowSnapshot flow) {
        response.header("X-Auth-Flow-Id", flow.flowId())
                .header("X-Auth-Flow-Epoch", Long.toString(flow.flowEpoch()))
                .header("X-Auth-Flow-Revision", Long.toString(flow.revision()));
    }

    private String etag(FlowSnapshot flow) {
        return "\"" + flow.flowId() + ":" + flow.revision() + "\"";
    }

    private QueueStatusResponse response(HazelcastAuthStateStore.TicketView view) {
        return new QueueStatusResponse(view.status(), view.loginOutcome(), view.usersAhead(), view.nextPollSeconds(),
                store.diagnosticCorrelationId(view.ticketId()));
    }

    private String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (properties.queueCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String queueCookie(String value, long maxAge) {
        return ResponseCookie.from(properties.queueCookieName(), value).httpOnly(true).secure(secureCookie)
                .sameSite(sameSite).path(COOKIE_PATH).maxAge(Duration.ofSeconds(maxAge)).build().toString();
    }

    public record QueueCreatePending(QueueReceipt queueReceipt) {
    }

    public record QueueReceipt(String operationId, String status) {
    }
}
