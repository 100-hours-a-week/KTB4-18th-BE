package com.muse.meomuneum.auth.controller;

import java.time.Duration;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.application.AuthFlowService.FlowSnapshot;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.dto.QueueStatusResponse;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.response.ApiResponse;

final class LoginQueueHttpResponseFactory {
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final HazelcastAuthStateStore store;
    private final AuthStateProperties properties;
    private final boolean secureCookie;
    private final String sameSite;
    private final AuthFlowService flowService;

    LoginQueueHttpResponseFactory(HazelcastAuthStateStore store, AuthStateProperties properties,
            boolean secureCookie, String sameSite, AuthFlowService flowService) {
        this.store = store;
        this.properties = properties;
        this.secureCookie = secureCookie;
        this.sameSite = sameSite;
        this.flowService = flowService;
    }

    ResponseEntity<ApiResponse<?>> notFound(HttpServletRequest request, String code) {
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

    HttpHeaders queueHeaders(FlowSnapshot flow, String cookie, long maxAge) {
        HttpHeaders headers = flow == null ? new HttpHeaders() : flowHeaders(flow);
        headers.add(HttpHeaders.SET_COOKIE, queueCookie(cookie, maxAge));
        return headers;
    }

    HttpHeaders flowHeaders(FlowSnapshot flow) {
        HttpHeaders headers = new HttpHeaders();
        if (flowService != null) {
            flowService.writeTupleHeaders(headers, flow);
        }
        return headers;
    }

    void addFlowHeaders(ResponseEntity.BodyBuilder response, FlowSnapshot flow) {
        response.header("X-Auth-Flow-Id", flow.flowId())
                .header("X-Auth-Flow-Epoch", Long.toString(flow.flowEpoch()))
                .header("X-Auth-Flow-Revision", Long.toString(flow.revision()));
    }

    String etag(FlowSnapshot flow) {
        return "\"" + flow.flowId() + ":" + flow.revision() + "\"";
    }

    QueueStatusResponse response(HazelcastAuthStateStore.TicketView view) {
        return new QueueStatusResponse(view.status(), view.loginOutcome(), view.usersAhead(), view.nextPollSeconds(),
                store.diagnosticCorrelationId(view.ticketId()));
    }

    String cookieValue(HttpServletRequest request) {
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

    String queueCookie(String value, long maxAge) {
        return ResponseCookie.from(properties.queueCookieName(), value).httpOnly(true).secure(secureCookie)
                .sameSite(sameSite).path(COOKIE_PATH).maxAge(Duration.ofSeconds(maxAge)).build().toString();
    }
}
