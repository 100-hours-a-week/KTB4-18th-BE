package com.muse.meomuneum.auth.directv2.application;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.model.DirectAuthOperationState;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthOperationRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository;
import com.muse.meomuneum.auth.directv2.security.DirectAuthCookieService;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@Service
public class DirectAuthRecoveryService {
    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthFlowRepository flows;
    private final DirectAuthOperationRepository operations;
    private final DirectAuthSessionRepository sessions;
    private final DirectAuthProjectionRepository projections;
    private final DirectAuthFlowService flowService;
    private final DirectAuthLoginService login;
    private final DirectAuthCookieService cookies;
    private final UserAuthenticationService users;
    private final JwtTokenProvider tokens;
    private final RefreshReplayCipher cipher;
    private final com.muse.meomuneum.auth.directv2.repository.DirectAuthReceiptRepository receipts;
    private final com.muse.meomuneum.auth.directv2.repository.DirectAuthRefreshRepository refreshReceipts;
    private final DirectAuthRefreshService refresh;
    private final com.muse.meomuneum.auth.directv2.repository.DirectAuthRecoveryRepository recoveryRows;
    private final DirectAuthRecoveryLifecycle sourceLifecycle;
    private final com.muse.meomuneum.auth.directv2.repository.DirectAuthRebootstrapRepository reboots;

    public DirectAuthRecoveryService(DirectAuthTransactionRunner transactions, DirectAuthFlowRepository flows,
            DirectAuthOperationRepository operations, DirectAuthSessionRepository sessions,
            DirectAuthProjectionRepository projections, DirectAuthFlowService flowService, DirectAuthLoginService login,
            DirectAuthCookieService cookies, UserAuthenticationService users, JwtTokenProvider tokens,
            RefreshReplayCipher cipher,
            com.muse.meomuneum.auth.directv2.repository.DirectAuthReceiptRepository receipts,
            com.muse.meomuneum.auth.directv2.repository.DirectAuthRefreshRepository refreshReceipts,
            DirectAuthRefreshService refresh,
            com.muse.meomuneum.auth.directv2.repository.DirectAuthRecoveryRepository recoveryRows,
            DirectAuthRecoveryLifecycle sourceLifecycle,
            com.muse.meomuneum.auth.directv2.repository.DirectAuthRebootstrapRepository reboots) {
        this.transactions = transactions;
        this.flows = flows;
        this.operations = operations;
        this.sessions = sessions;
        this.projections = projections;
        this.flowService = flowService;
        this.login = login;
        this.cookies = cookies;
        this.users = users;
        this.tokens = tokens;
        this.cipher = cipher;
        this.receipts = receipts;
        this.refreshReceipts = refreshReceipts;
        this.refresh = refresh;
        this.recoveryRows = recoveryRows;
        this.sourceLifecycle = sourceLifecycle;
        this.reboots = reboots;
    }

    public DirectAuthLoginService.Result status(HttpServletRequest request, Tuple tuple) {
        return transactions.run(() -> {
            var flow = flows.lock(tuple.flowId())
                    .orElseThrow(() -> new DirectAuthFailure(404, "LOGIN_OPERATION_NOT_FOUND"));
            var operation = operations.lockLogin(tuple.flowId(), tuple.operationId()).orElse(null);
            login.requireProof(request, flow, operation);
            if (operation == null || operation.startingEpoch() != tuple.epoch()) {
                throw new DirectAuthFailure(404, "LOGIN_OPERATION_NOT_FOUND");
            }
            try {
                login.requireLive(operation);
            } catch (DirectAuthFailure expired) {
                throw new DirectAuthFailure(404, "LOGIN_OPERATION_NOT_FOUND");
            }
            var result = login.status(flow, operation);
            Object data = result.data();
            if ("SUCCEEDED".equals(operation.status()) && "LOGIN".equals(flow.currentOperationKind())
                    && operation.operationId().equals(flow.currentOperationId())
                    && java.util.Objects.equals(operation.resultSid(), flow.activeSessionSid())
                    && operation.resultEpoch() != null && operation.resultEpoch() == flow.epoch()
                    && reboots.canonical(flow)) {
                @SuppressWarnings("unchecked")
                var canonical = new LinkedHashMap<>((Map<String, Object>) result.data());
                canonical.put("resultEpoch", Long.toString(flow.epoch()));
                canonical.put("resultRevision", Long.toString(flow.revision()));
                data = canonical;
            }
            return new DirectAuthLoginService.Result(result.status() == 202 ? 202 : 200,
                    result.message(), data, flow, List.of());
        });
    }

    public DirectAuthLoginService.Result recoverLogin(HttpServletRequest request, Tuple tuple) {
        return transactions.run(() -> {
            projections.lockControl();
            var flow = flows.lock(tuple.flowId()).orElseThrow(() -> new DirectAuthFailure(428, "AUTH_FLOW_REQUIRED"));
            var operation = operations.lockLogin(tuple.flowId(), tuple.operationId()).orElseThrow(
                    () -> new DirectAuthFailure(404, "LOGIN_OPERATION_NOT_FOUND"));
            login.requireProof(request, flow, operation);
            if (operation.startingEpoch() != tuple.epoch()) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
            }
            try {
                login.requireLive(operation);
            } catch (DirectAuthFailure expired) {
                throw DirectAuthFailure.conflict("REAUTH_REQUIRED");
            }
            if ("IN_PROGRESS".equals(operation.status())) {
                return login.status(flow, operation);
            }
            if (!"SUCCEEDED".equals(operation.status())) {
                throw DirectAuthFailure.conflict("REAUTH_REQUIRED".equals(operation.status())
                        ? "REAUTH_REQUIRED"
                        : "LOGIN_RESULT_NOT_RECOVERABLE");
            }
            if (operation.resultEpoch() == null
                    || operation.resultRevision() == null || !"LOGIN".equals(flow.currentOperationKind())
                    || !tuple.operationId().equals(flow.currentOperationId()) || operation.resultEpoch() != flow.epoch()
                    || operation.resultRevision() != flow.revision() && !reboots.canonical(flow)
                    || !java.util.Objects.equals(operation.resultSid(), flow.activeSessionSid())) {
                throw DirectAuthFailure.conflict("AUTH_LOGIN_SUPERSEDED");
            }
            var now = flows.now();
            var session = sessions.require(flow, now, true);
            if (session.refreshGeneration() != 1) {
                throw DirectAuthFailure.conflict("AUTH_LOGIN_SUPERSEDED");
            }
            String refresh = cipher.decrypt(session.encryptedRefresh());
            var claims = tokens.parseRefreshToken(refresh);
            if (!session.sid().equals(claims.sessionId()) || session.userId() != claims.userId()) {
                throw DirectAuthFailure.unavailable();
            }
            String access = tokens.createAccessToken(users.findActiveUser(session.userId()), session.sid());
            return login.token(flow, access, refresh, session.sid(), login.flowProof(flow.flowId(),
                    flow.flowCookieGeneration()), now, session.idleExpiry());
        });
    }

    public DirectAuthFlowService.Result current(HttpServletRequest request) {
        return transactions.run(() -> {
            var found = flowService.resolve(request, true);
            var flow = flows.lock(found.flowId()).orElseThrow(DirectAuthFailure::unavailable);
            boolean active = cookies.legacyMatches(request, flow, flows.now())
                    || cookies.proofs(request).stream().filter(proof -> !proof.binding())
                            .anyMatch(proof -> cookies.valid(flow, proof, flows.now()));
            Map<String, Object> canonical = Map.of("flowId", flow.flowId(), "flowEpoch", Long.toString(flow.epoch()),
                    "revision", Long.toString(flow.revision()), "lifecycle", flow.lifecycle(),
                    "authenticated", "AUTHENTICATED".equals(flow.lifecycle()),
                    "proofState", active ? "ACTIVE" : "REBOOTSTRAP_REQUIRED");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("flow", canonical);
            body.put("operation", summary(flow));
            return new DirectAuthFlowService.Result(flow, body, List.of());
        });
    }

    private Map<String, Object> summary(DirectAuthFlowState flow) {
        String id = flow.activeLoginOperationId() == null ? flow.currentOperationId() : flow.activeLoginOperationId();
        if (id == null) {
            return null;
        }
        if ("LOGOUT".equals(flow.currentOperationKind())) {
            var receipt = receipts.lockLogout(new Tuple(flow.flowId(), flow.epoch(), id)).orElse(null);
            if (receipt == null || !flows.now().isBefore(receipt.expiry())) {
                return null;
            }
            if (receipt.resultEpoch() != flow.epoch() || receipt.resultRevision() != flow.revision()
                    || receipt.resultGeneration() != flow.flowCookieGeneration()) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("flowId", flow.flowId());
            summary.put("startingEpoch", Long.toString(receipt.epoch()));
            summary.put("operationId", id);
            summary.put("kind", "LOGOUT");
            summary.put("status", "SUCCEEDED");
            summary.put("resultEpoch", Long.toString(receipt.resultEpoch()));
            summary.put("resultRevision", Long.toString(receipt.resultRevision()));
            summary.put("reasonCode", null);
            summary.put("leaseExpiresAt", null);
            summary.put("receiptExpiresAt", receipt.expiry().toInstant(ZoneOffset.UTC).toString());
            summary.put("clientExpiresAt", null);
            summary.put("effectiveExpiresAt", null);
            return summary;
        }
        if ("REFRESH".equals(flow.currentOperationKind())) {
            var now = flows.now();
            var session = sessions.require(flow, now, false);
            var receipt = refreshReceipts.find(flow.flowId(), id, true).orElse(null);
            if (receipt == null || "TOMBSTONE".equals(receipt.status())
                    || "NEGATIVE_STALE".equals(receipt.status())) {
                return null;
            }
            if (!"SUCCEEDED".equals(receipt.status()) || receipt.epoch() <= 0 || receipt.sid() == null
                    || receipt.sid().isBlank() || receipt.resultEpoch() == null || receipt.resultEpoch() <= 0
                    || receipt.resultRevision() == null || receipt.resultRevision() <= 0
                    || receipt.flowGeneration() == null || receipt.flowGeneration() <= 0
                    || receipt.refreshGeneration() == null || receipt.refreshGeneration() <= 0
                    || receipt.client() == null || receipt.effective() == null || receipt.client() <= 0
                    || receipt.client() > 9_007_199_254_740_991L || receipt.effective() <= 0
                    || receipt.effective() > receipt.client() || receipt.expiry() == null) {
                throw DirectAuthFailure.unavailable();
            }
            var recovered = flow.recoveryOperationId() == null
                    ? null
                    : recoveryRows.find(flow.flowId(), flow.recoveryOperationId(), true).orElse(null);
            boolean recoveryCanonical = recovered != null && sourceLifecycle.canonical(
                    new DirectAuthRecoveryLifecycle.Source(flow, receipt, now), session, recovered);
            if (receipt.resultEpoch() == null || receipt.resultEpoch() != flow.epoch()
                    || !recoveryCanonical
                            && (receipt.resultRevision() == null || receipt.resultRevision() != flow.revision())
                    || !java.util.Objects.equals(receipt.sid(), session.sid())
                    || !recoveryCanonical && (receipt.flowGeneration() != flow.flowCookieGeneration()
                            || receipt.refreshGeneration() != session.refreshGeneration())) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
            }
            if (!now.isBefore(receipt.expiry())
                    || com.muse.meomuneum.auth.directv2.repository.DirectAuthLoginLifecycleRepository
                            .micros(now) >= receipt.effective() * 1000) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("flowId", flow.flowId());
            result.put("operationId", id);
            result.put("startingEpoch", Long.toString(receipt.epoch()));
            result.put("kind", "REFRESH");
            result.put("status", "SUCCEEDED");
            result.put("resultEpoch", receipt.resultEpoch().toString());
            result.put("resultRevision",
                    recoveryCanonical ? recovered.resultRevision().toString() : receipt.resultRevision().toString());
            result.put("reasonCode", null);
            result.put("leaseExpiresAt", null);
            result.put("receiptExpiresAt", receipt.expiry().toInstant(ZoneOffset.UTC).toString());
            result.put("clientExpiresAt", receipt.client().toString());
            result.put("effectiveExpiresAt", receipt.effective().toString());
            return result;
        }
        if (!"LOGIN".equals(flow.currentOperationKind())) {
            return null;
        }
        DirectAuthOperationState operation = operations.lockLogin(flow.flowId(), id).orElse(null);
        if (operation == null) {
            return null;
        }
        boolean receiptExpired = operation.receiptExpiresAt() == null
                || !flows.now().isBefore(operation.receiptExpiresAt());
        if (flow.activeLoginOperationId() == null && !"IN_PROGRESS".equals(operation.status()) && receiptExpired) {
            return null;
        }
        login.requireLive(operation);
        if ("SUCCEEDED".equals(operation.status())) {
            sessions.require(flow, flows.now(), false);
            if (!java.util.Objects.equals(operation.resultSid(), flow.activeSessionSid())
                    || operation.resultEpoch() == null || operation.resultEpoch() != flow.epoch()
                    || operation.resultRevision() == null
                    || operation.resultRevision() != flow.revision() && !reboots.canonical(flow)) {
                throw DirectAuthFailure.conflict("AUTH_LOGIN_SUPERSEDED");
            }
        }
        var status = login.status(flow, operation);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) status.data());
        result.put("kind", "LOGIN");
        if ("SUCCEEDED".equals(operation.status()) && reboots.canonical(flow)) {
            result.put("resultRevision", Long.toString(flow.revision()));
        }
        result.put("reasonCode", operation.reasonCode());
        result.put("leaseExpiresAt", operation.leaseExpiresAt() == null
                ? null
                : operation.leaseExpiresAt().toInstant(ZoneOffset.UTC).toString());
        result.put("receiptExpiresAt", "IN_PROGRESS".equals(operation.status())
                ? null
                : operation.receiptExpiresAt().toInstant(ZoneOffset.UTC).toString());
        result.put("clientExpiresAt", operation.clientExpiresAt().toString());
        result.put("effectiveExpiresAt", operation.effectiveExpiresAt().toString());
        return result;
    }
}
