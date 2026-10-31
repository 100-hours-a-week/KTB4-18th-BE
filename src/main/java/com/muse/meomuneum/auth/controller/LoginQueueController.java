package com.muse.meomuneum.auth.controller;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth/login-queue")
public class LoginQueueController {

    private final HazelcastAuthStateStore store;
    private final AuthFlowService flowService;
    private final LoginQueueHttpResponseFactory responses;
    private final LoginQueueTicketApplicationService tickets;

    @Autowired
    public LoginQueueController(HazelcastAuthStateStore store, AuthStateProperties properties,
            @Value("${auth.cookie.secure:true}") boolean secureCookie,
            @Value("${auth.cookie.same-site:Lax}") String sameSite, AuthFlowService flowService) {
        this.store = store;
        this.flowService = flowService;
        this.responses = new LoginQueueHttpResponseFactory(store, properties, secureCookie, sameSite, flowService);
        this.tickets = new LoginQueueTicketApplicationService(store, properties, flowService, responses);
    }

    public LoginQueueController(HazelcastAuthStateStore store, AuthStateProperties properties,
            boolean secureCookie, String sameSite) {
        this(store, properties, secureCookie, sameSite, null);
    }

    @PostMapping("/tickets")
    public ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request) {
        return tickets.create(idempotencyKey, request);
    }

    @GetMapping({"/current", "/tickets/current"})
    public ResponseEntity<ApiResponse<?>> current(HttpServletRequest request) {
        var flow = flowService == null ? null : flowService.currentFlow(request);
        try {
            HazelcastAuthStateStore.TicketView view = store.currentTicket(responses.cookieValue(request));
            if (flow != null && (flow.currentTicketId() == null
                    || !flow.currentTicketId().equals(view.ticketId()))) {
                return responses.notFound(request, "TICKET_UNKNOWN");
            }
            if (flow != null) {
                flowService.assertFlowSnapshotCurrent(flow);
            }
            ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
            if (flow != null) {
                response.eTag(responses.etag(flow));
                responses.addFlowHeaders(response, flow);
            }
            return response.body(ApiResponse.of("login queue status", responses.response(view)));
        } catch (com.muse.meomuneum.auth.exception.AuthStateExpiredException exception) {
            return ResponseEntity.status(410).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, responses.queueCookie("", 0))
                    .body(ApiResponse.failure("TICKET_EXPIRED"));
        } catch (com.muse.meomuneum.auth.exception.AuthStateNotFoundException exception) {
            return responses.notFound(request, exception.code());
        }
    }

    @DeleteMapping({"/current", "/tickets/current"})
    public ResponseEntity<?> cancel(HttpServletRequest request) {
        return tickets.cancel(request);
    }

    public record QueueCreatePending(QueueReceipt queueReceipt) {
    }

    public record QueueReceipt(String operationId, String status) {
    }
}
