package com.muse.meomuneum.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.dto.QueueStatusResponse;
import com.muse.meomuneum.auth.exception.AuthStateExpiredException;
import com.muse.meomuneum.auth.exception.AuthStateNotFoundException;
import com.muse.meomuneum.auth.exception.AuthStateOperationInProgressException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.response.ApiResponse;

class LoginQueueControllerTest {

    private HazelcastAuthStateStore store;
    private LoginQueueController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = mock(HazelcastAuthStateStore.class);
        AuthStateProperties properties = new AuthStateProperties("01234567890123456789012345678901",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "MEO_QUEUE_V2", 1209600, 7776000,
                900, 60, 900, 20, 10, 64, 16, 4, 120, 1800, 5, 20, 4000, 86400, 1, 100000, 300, 45);
        controller = new LoginQueueController(store, properties, false, "Lax");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void createDoesNotAcquireTheAuthenticationWorkPermit() {
        String ticketId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        var view = new HazelcastAuthStateStore.TicketView(HazelcastAuthStateStore.TicketStatus.WAITING,
                null, 0, 10, ticketId);
        when(store.createOrReuseTicket(null, idempotencyKey))
                .thenReturn(new HazelcastAuthStateStore.CreateTicket("queue-cookie", view, true));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.1");

        var response = controller.create(idempotencyKey, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Set-Cookie")).contains("MEO_QUEUE_V2=queue-cookie");
        verify(store).enforceTicketCreateRateLimit("192.0.2.1");
        verify(store).createOrReuseTicket(null, idempotencyKey);
        verify(store, never()).acquireRequestPermit();
    }

    @Test
    void createReturnsWaitingForAnIdempotentTicketWhoseCreationIsStillBeingReconciled() {
        String idempotencyKey = UUID.randomUUID().toString();
        when(store.createOrReuseTicket(null, idempotencyKey))
                .thenThrow(new AuthStateOperationInProgressException("queue-cookie"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.1");

        var response = controller.create(idempotencyKey, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Set-Cookie")).contains("MEO_QUEUE_V2=queue-cookie");
        assertThat(response.getBody()).isInstanceOf(ApiResponse.class);
        var body = (ApiResponse<?>) response.getBody();
        assertThat(body.data()).isInstanceOf(QueueStatusResponse.class);
        var status = (QueueStatusResponse) body.data();
        assertThat(status.status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        verify(store, never()).acquireRequestPermit();
    }

    @Test
    void statusAndCancellationDoNotAcquireTheAuthenticationWorkPermit() {
        String ticketId = UUID.randomUUID().toString();
        var view = new HazelcastAuthStateStore.TicketView(HazelcastAuthStateStore.TicketStatus.WAITING,
                null, 3, 10, ticketId);
        when(store.currentTicket("queue-cookie")).thenReturn(view);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("MEO_QUEUE_V2", "queue-cookie"));

        assertThat(controller.current(request).getStatusCode().value()).isEqualTo(200);
        var cancellation = controller.cancel(request);
        assertThat(cancellation.getStatusCode().value()).isEqualTo(204);
        assertThat(cancellation.getHeaders().getCacheControl()).contains("no-store");
        assertThat(cancellation.getHeaders().getFirst("Set-Cookie"))
                .contains("MEO_QUEUE_V2=", "Max-Age=0", "Path=/api/v1/auth");
        verify(store).currentTicket("queue-cookie");
        verify(store).cancelCurrentTicket("queue-cookie");
        verify(store, never()).acquireRequestPermit();
    }

    @Test
    void deleteWithoutQueueCookieStillExpiresCookieAndPassesNullToTheStore() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/login-queue/tickets/current"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("MEO_QUEUE_V2="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth"))));

        verify(store).cancelCurrentTicket(null);
    }

    @Test
    void currentStatusSerializesNullableOutcomeForEveryPublicStatus() throws Exception {
        var nullOutcomeStatuses = new HazelcastAuthStateStore.TicketStatus[]{
                HazelcastAuthStateStore.TicketStatus.WAITING,
                HazelcastAuthStateStore.TicketStatus.READY,
                HazelcastAuthStateStore.TicketStatus.VERIFYING,
                HazelcastAuthStateStore.TicketStatus.RETRYABLE,
                HazelcastAuthStateStore.TicketStatus.CANCELLED,
                HazelcastAuthStateStore.TicketStatus.EXPIRED
        };
        for (var ticketStatus : nullOutcomeStatuses) {
            when(store.currentTicket("queue-cookie")).thenReturn(
                    new HazelcastAuthStateStore.TicketView(ticketStatus, null, null, 10, "ticket"));

            mockMvc.perform(get("/api/v1/auth/login-queue/tickets/current").cookie(
                    new Cookie("MEO_QUEUE_V2", "queue-cookie")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(jsonPath("$.data.status").value(ticketStatus.name()))
                    .andExpect(jsonPath("$.data.loginOutcome").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.usersAhead").doesNotExist())
                    .andExpect(jsonPath("$.data.diagnosticCorrId").doesNotExist());
        }
    }

    @Test
    void jacksonThreeSerializationIncludesNullableOutcome() throws Exception {
        var response = new ApiResponse<>("login queue status",
                new QueueStatusResponse(
                        HazelcastAuthStateStore.TicketStatus.WAITING, null, null, 10, null));
        var objectMapper = new tools.jackson.databind.ObjectMapper();
        var json = objectMapper.writeValueAsString(response);
        var root = objectMapper.readTree(json);

        assertThat(root.get("data").get("loginOutcome").isNull()).isTrue();
        assertThat(root.get("data").has("usersAhead")).isFalse();
        assertThat(root.get("data").has("diagnosticCorrId")).isFalse();
    }

    @Test
    void currentStatusSerializesConsumedOutcomesAsTheirWireNames() throws Exception {
        for (var outcome : HazelcastAuthStateStore.LoginOutcome.values()) {
            when(store.currentTicket("queue-cookie")).thenReturn(new HazelcastAuthStateStore.TicketView(
                    HazelcastAuthStateStore.TicketStatus.CONSUMED, outcome, 0, 10, "ticket"));

            mockMvc.perform(get("/api/v1/auth/login-queue/tickets/current").cookie(
                    new Cookie("MEO_QUEUE_V2", "queue-cookie")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(jsonPath("$.data.status").value("CONSUMED"))
                    .andExpect(jsonPath("$.data.loginOutcome").value(outcome.name()));
        }
    }

    @Test
    void createResponseSerializesExplicitNullOutcome() throws Exception {
        var view = new HazelcastAuthStateStore.TicketView(
                HazelcastAuthStateStore.TicketStatus.WAITING, null, null, 10, "ticket");
        when(store.createOrReuseTicket(null, "create-key"))
                .thenReturn(new HazelcastAuthStateStore.CreateTicket("queue-cookie", view, true));

        mockMvc.perform(post("/api/v1/auth/login-queue/tickets").header("Idempotency-Key", "create-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("MEO_QUEUE_V2=queue-cookie")))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.loginOutcome").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.usersAhead").doesNotExist())
                .andExpect(jsonPath("$.data.diagnosticCorrId").doesNotExist());
    }

    @Test
    void expiredStatusIsNotCachedAndClearsTheQueueCookie() throws Exception {
        when(store.currentTicket("queue-cookie")).thenThrow(new AuthStateExpiredException());

        mockMvc.perform(get("/api/v1/auth/login-queue/tickets/current")
                .cookie(new Cookie("MEO_QUEUE_V2", "queue-cookie")))
                .andExpect(status().isGone())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("MEO_QUEUE_V2="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth"))))
                .andExpect(jsonPath("$.message").value("TICKET_EXPIRED"));
    }

    @Test
    void missingStatusIsNotCachedAndClearsTheQueueCookie() throws Exception {
        when(store.currentTicket("queue-cookie")).thenThrow(new AuthStateNotFoundException("TICKET_UNKNOWN"));

        mockMvc.perform(get("/api/v1/auth/login-queue/tickets/current")
                .cookie(new Cookie("MEO_QUEUE_V2", "queue-cookie")))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("MEO_QUEUE_V2="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth"))))
                .andExpect(jsonPath("$.message").value("TICKET_UNKNOWN"));
    }

    @Test
    void deleteCancellationReturnsNoContentWithoutCachingAndClearsTheQueueCookie() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/login-queue/tickets/current")
                .cookie(new Cookie("MEO_QUEUE_V2", "queue-cookie")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("MEO_QUEUE_V2="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth"))));
    }
}
