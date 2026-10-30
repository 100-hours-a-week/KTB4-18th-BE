package com.muse.meomuneum.auth.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.application.AuthFlowService.FlowSnapshot;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthFlowException;
import com.muse.meomuneum.auth.exception.AuthStateOperationInProgressException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.response.ApiResponse;

final class LoginQueueTicketApplicationService {
    private final HazelcastAuthStateStore store;
    private final AuthStateProperties properties;
    private final AuthFlowService flowService;
    private final LoginQueueHttpResponseFactory responses;

    LoginQueueTicketApplicationService(HazelcastAuthStateStore store, AuthStateProperties properties,
            AuthFlowService flowService, LoginQueueHttpResponseFactory responses) {
        this.store = store;
        this.properties = properties;
        this.flowService = flowService;
        this.responses = responses;
    }

    ResponseEntity<?> create(String idempotencyKey, HttpServletRequest request) {
        if (flowService == null) {
            return createUnfenced(idempotencyKey, request, null);
        }
        return flowService.withLockedFlow(request,
                flow -> createUnfenced(idempotencyKey, request, flow));
    }

    ResponseEntity<?> cancel(HttpServletRequest request) {
        if (flowService == null) {
            store.cancelCurrentTicket(responses.cookieValue(request));
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, responses.queueCookie("", 0)).build();
        }
        return flowService.withLockedFlow(request, flow -> cancelLocked(request, flow));
    }

    private ResponseEntity<?> createUnfenced(String idempotencyKey, HttpServletRequest request,
            FlowSnapshot flow) {
        store.enforceTicketCreateRateLimit(request.getRemoteAddr());
        String existingCookie = responses.cookieValue(request);
        if (flow != null && flow.currentTicketId() != null) {
            try {
                HazelcastAuthStateStore.TicketView existing = store.currentTicket(existingCookie);
                if (flow.currentTicketId().equals(existing.ticketId())) {
                    HttpHeaders headers = responses.flowHeaders(flow);
                    return ResponseEntity.ok().headers(headers).cacheControl(CacheControl.noStore())
                            .body(ApiResponse.of("login queue ticket", responses.response(existing)));
                }
            } catch (RuntimeException ignored) {
            }
            flow = flowService.clearQueueTicket(flow);
        }
        if (flow != null) {
            return createWithCanonicalIntent(idempotencyKey, request, flow, existingCookie);
        }
        try {
            var result = store.createOrReuseTicket(existingCookie, idempotencyKey);
            HttpHeaders headers = responses.queueHeaders(null, result.queueCookie(), properties.queueCookieSeconds());
            return ResponseEntity.ok().headers(headers).cacheControl(CacheControl.noStore())
                    .body(ApiResponse.of("login queue ticket", responses.response(result.view())));
        } catch (AuthStateOperationInProgressException exception) {
            if (exception.queueCookie() == null) {
                return ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.RETRY_AFTER, "1")
                        .body(ApiResponse.failure("OPERATION_IN_PROGRESS"));
            }
            HttpHeaders headers = responses.queueHeaders(null, exception.queueCookie(),
                    properties.queueCookieSeconds());
            return ResponseEntity.status(202).headers(headers).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(ApiResponse.of("AUTH_QUEUE_CREATE_IN_PROGRESS",
                            new LoginQueueController.QueueCreatePending(
                                    new LoginQueueController.QueueReceipt(idempotencyKey, "PENDING"))));
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
                HttpHeaders headers = responses.flowHeaders(reservedFlow);
                if (pending.queueCookie() != null) {
                    headers.add(HttpHeaders.SET_COOKIE,
                            responses.queueCookie(pending.queueCookie(), properties.queueCookieSeconds()));
                }
                return ResponseEntity.status(202).headers(headers).cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.RETRY_AFTER, "1")
                        .body(ApiResponse.of("AUTH_QUEUE_CREATE_IN_PROGRESS",
                                new LoginQueueController.QueueCreatePending(
                                        new LoginQueueController.QueueReceipt(operationId, "PENDING"))));
            }
            FlowSnapshot committed = flowService.commitQueueCreate(request, intent, result.view().ticketId());
            HttpHeaders headers = responses.queueHeaders(committed, result.queueCookie(),
                    properties.queueCookieSeconds());
            return ResponseEntity.ok().headers(headers).cacheControl(CacheControl.noStore())
                    .body(ApiResponse.of("login queue ticket", responses.response(result.view())));
        } catch (RuntimeException rejected) {
            if (result != null) {
                boolean current;
                try {
                    current = flowService.isQueueTicketCurrent(result.view().ticketId());
                } catch (com.muse.meomuneum.auth.exception.AuthStateUnavailableException statusUnavailable) {
                    rejected.addSuppressed(statusUnavailable);
                    throw rejected;
                }
                if (current) {
                    FlowSnapshot canonical = flowService.currentFlow(request);
                    HttpHeaders headers = responses.queueHeaders(canonical, result.queueCookie(),
                            properties.queueCookieSeconds());
                    return ResponseEntity.ok().headers(headers).cacheControl(CacheControl.noStore())
                            .body(ApiResponse.of("login queue ticket", responses.response(result.view())));
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
            HttpHeaders headers = responses.flowHeaders(flow);
            headers.set(HttpHeaders.SET_COOKIE, responses.queueCookie("", 0));
            headers.setETag(responses.etag(flow));
            return ResponseEntity.noContent().headers(headers).cacheControl(CacheControl.noStore()).build();
        }
        String expected = responses.etag(flow);
        if (!expected.equals(ifMatch)) {
            HttpHeaders headers = responses.flowHeaders(flow);
            headers.setETag(expected);
            return ResponseEntity.status(412).headers(headers).cacheControl(CacheControl.noStore())
                    .body(ApiResponse.failure("AUTH_FLOW_REVISION_STALE"));
        }
        FlowSnapshot updated = flowService.clearQueueTicket(flow, operationId);
        HttpHeaders headers = responses.flowHeaders(updated);
        headers.set(HttpHeaders.SET_COOKIE, responses.queueCookie("", 0));
        headers.setETag(responses.etag(updated));
        return ResponseEntity.noContent().headers(headers).cacheControl(CacheControl.noStore()).build();
    }
}
