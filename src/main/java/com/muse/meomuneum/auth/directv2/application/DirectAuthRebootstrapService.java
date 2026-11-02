package com.muse.meomuneum.auth.directv2.application;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.auth.application.RefreshReplayCipher.ReplayContext;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthLoginLifecycleRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthOperationRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRebootstrapRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository;
import com.muse.meomuneum.auth.directv2.security.DirectAuthCookieService;
import com.muse.meomuneum.auth.directv2.security.DirectAuthNonceProofService;

@Service
public class DirectAuthRebootstrapService {
    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthFlowRepository flows;
    private final DirectAuthOperationRepository operations;
    private final DirectAuthLoginLifecycleRepository logins;
    private final DirectAuthRebootstrapRepository receipts;
    private final DirectAuthProjectionRepository projections;
    private final DirectAuthSessionRepository sessions;
    private final DirectAuthCookieService cookies;
    private final DirectAuthLoginService login;
    private final DirectAuthFlowService flowService;
    private final RefreshReplayCipher cipher;
    private final AuthStateProperties properties;
    private final boolean verified;

    public DirectAuthRebootstrapService(DirectAuthTransactionRunner transactions, DirectAuthFlowRepository flows,
            DirectAuthOperationRepository operations, DirectAuthLoginLifecycleRepository logins,
            DirectAuthRebootstrapRepository receipts, DirectAuthProjectionRepository projections,
            DirectAuthSessionRepository sessions, DirectAuthCookieService cookies, DirectAuthLoginService login,
            RefreshReplayCipher cipher, AuthStateProperties properties, DirectAuthFlowService flowService,
            @Value("${auth.direct-v2.rebootstrap-verified:false}") boolean verified) {
        this.transactions = transactions;
        this.flows = flows;
        this.operations = operations;
        this.logins = logins;
        this.receipts = receipts;
        this.projections = projections;
        this.sessions = sessions;
        this.cookies = cookies;
        this.login = login;
        this.flowService = flowService;
        this.cipher = cipher;
        this.properties = properties;
        this.verified = verified;
    }

    public DirectAuthRefreshService.Result rebootstrap(HttpServletRequest request, Tuple tuple) {
        if (!verified) {
            throw DirectAuthFailure.unavailable();
        }
        DirectAuthNonceProofService.checkSize(request);
        var ticket = cipher.reserveV2(1, () -> transactions.run(() -> {
            var selected = flowService.resolve(request, true);
            if (!selected.flowId().equals(tuple.flowId()) || cookies.proofs(request).stream()
                    .filter(proof -> proof.binding() && cookies.valid(selected, proof, flows.now())).count() != 1) {
                throw new DirectAuthFailure(404, "AUTH_FLOW_NOT_FOUND");
            }
            return null;
        }));
        return transactions.run(() -> {
            long projectionGeneration = projections.lockTicket(ticket);
            var selected = flowService.resolve(request, true);
            if (!selected.flowId().equals(tuple.flowId())) {
                throw new DirectAuthFailure(404, "AUTH_FLOW_NOT_FOUND");
            }
            var flow = flows.lock(tuple.flowId()).orElseThrow(() -> new DirectAuthFailure(404, "AUTH_FLOW_NOT_FOUND"));
            var now = flows.now();
            var resolved = flow;
            long bindings = cookies.proofs(request).stream().filter(proof -> proof.binding()
                    && cookies.valid(resolved, proof, now)).count();
            if (bindings != 1) {
                throw new DirectAuthFailure(404, "AUTH_FLOW_NOT_FOUND");
            }
            if (!"AUTHENTICATED".equals(flow.lifecycle()) && flow.activeSessionSid() != null) {
                throw DirectAuthFailure.unavailable();
            }
            var existing = receipts.lock(tuple).orElse(null);
            if (existing != null) {
                if (existing.epoch() != tuple.epoch()) {
                    throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
                }
                if (!now.isBefore(existing.expiry()) || "TOMBSTONE".equals(existing.status())) {
                    throw DirectAuthFailure.conflict("AUTH_OPERATION_RECEIPT_EXPIRED");
                }
                if (existing.binding() == null || existing.binding() != flow.bindingCookieGeneration()) {
                    throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
                }
                if (existing.resultEpoch() == null || existing.resultEpoch() != flow.epoch()
                        || existing.revision() == null || existing.revision() != flow.revision()
                        || existing.generation() == null || existing.generation() != flow.flowCookieGeneration()) {
                    throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
                }
                String header = cipher.decryptV2(existing.encrypted(), context(tuple, flow));
                return result(tuple, flow, header);
            }
            receipts.quota(flow.flowId());
            if (flow.epoch() != tuple.epoch()) {
                var committed = "LOGIN".equals(flow.currentOperationKind()) && flow.currentOperationId() != null
                        ? operations.lockLogin(flow.flowId(), flow.currentOperationId()).orElse(null)
                        : null;
                if (committed == null || committed.startingEpoch() != tuple.epoch()
                        || !"SUCCEEDED".equals(committed.status()) || committed.resultEpoch() == null
                        || committed.resultEpoch() != flow.epoch() || committed.resultRevision() == null
                        || committed.resultRevision() != flow.revision()
                        || !java.util.Objects.equals(committed.resultSid(), flow.activeSessionSid())) {
                    throw DirectAuthFailure.conflict("AUTH_FLOW_EPOCH_STALE");
                }
            }
            long epoch = flow.epoch();
            if (flow.activeLoginOperationId() != null) {
                var operation = operations.lockLogin(flow.flowId(), flow.activeLoginOperationId())
                        .orElseThrow(DirectAuthFailure::unavailable);
                if (!"IN_PROGRESS".equals(operation.status())) {
                    throw DirectAuthFailure.unavailable();
                }
                if (logins.recoverExpired(flow, now)) {
                    flow = flows.lock(flow.flowId()).orElseThrow();
                } else {
                    epoch = Math.incrementExact(epoch);
                    receipts.supersede(flow, operation, epoch, Math.incrementExact(flow.revision()));
                }
            }
            var session = "AUTHENTICATED".equals(flow.lifecycle()) ? sessions.require(flow, now, true) : null;
            var expiry = session == null ? flow.cookieExpiresAt() : session.idleExpiry();
            if (!now.isBefore(expiry)) {
                expiry = now.plusSeconds(properties.sessionIdleSeconds());
            }
            String proof = login.flowProof(flow.flowId(), Math.incrementExact(flow.flowCookieGeneration()));
            receipts.rotate(flow, epoch, cookies.hash("auth-flow-cookie:" + proof), expiry);
            var result = flows.lock(flow.flowId()).orElseThrow();
            String header = cookies.encode(cookies.flowName(result.flowId(), result.flowCookieGeneration()), proof, now,
                    expiry);
            receipts.save(tuple, result, cipher.encryptV2(header, context(tuple, result), ticket));
            if (session != null) {
                projections.fence(flow.flowId(), session.sid(), result.epoch(), result.revision(), projectionGeneration,
                        "ACTIVE", session.absoluteExpiry().plusSeconds(86400));
            }
            return result(tuple, result, header);
        });
    }

    private DirectAuthRefreshService.Result result(Tuple tuple, DirectAuthFlowState flow, String cookie) {
        var data = Map.<String, Object>of("flow",
                Map.of("flowId", flow.flowId(), "flowEpoch", Long.toString(flow.epoch()),
                        "revision", Long.toString(flow.revision()), "lifecycle", flow.lifecycle(),
                        "authenticated", "AUTHENTICATED".equals(flow.lifecycle()), "proofState", "ACTIVE"),
                "operationId", tuple.operationId(), "startingEpoch", Long.toString(tuple.epoch()),
                "resultEpoch", Long.toString(flow.epoch()), "resultRevision", Long.toString(flow.revision()));
        return new DirectAuthRefreshService.Result(200, "auth flow rebootstrap", data, flow, List.of(cookie));
    }

    private ReplayContext context(Tuple tuple, DirectAuthFlowState flow) {
        return new ReplayContext("rebootstrap-flow-cookie", "auth_flow_rebootstrap_operations", "REBOOTSTRAP",
                flow.flowId(), tuple.operationId(), "", Long.toString(tuple.epoch()), Long.toString(flow.epoch()),
                Long.toString(flow.revision()), Long.toString(flow.flowCookieGeneration()), "",
                Long.toString(flow.bindingCookieGeneration()), "", "", "");
    }
}
