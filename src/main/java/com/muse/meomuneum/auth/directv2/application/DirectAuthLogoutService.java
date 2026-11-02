package com.muse.meomuneum.auth.directv2.application;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.auth.application.RefreshReplayCipher.ReplayContext;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthOperationRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthReceiptRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthReceiptRepository.Receipt;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository;
import com.muse.meomuneum.auth.directv2.security.DirectAuthCookieService;
import com.muse.meomuneum.auth.directv2.security.DirectAuthNonceProofService;

@Service
public class DirectAuthLogoutService {
    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthFlowRepository flows;
    private final DirectAuthReceiptRepository receipts;
    private final DirectAuthOperationRepository operations;
    private final DirectAuthProjectionRepository projections;
    private final DirectAuthSessionRepository sessions;
    private final DirectAuthCookieService cookies;
    private final DirectAuthLoginService login;
    private final RefreshReplayCipher cipher;
    private final DirectAuthNonceProofService nonceProofs;
    private final ObjectMapper mapper;
    private final AuthStateProperties properties;

    public record Saved(Map<String, Object> data, List<String> cookies) {
    }

    public record Result(DirectAuthFlowState flow, Map<String, Object> data, List<String> cookies) {
    }

    public DirectAuthLogoutService(DirectAuthTransactionRunner transactions, DirectAuthFlowRepository flows,
            DirectAuthReceiptRepository receipts, DirectAuthOperationRepository operations,
            DirectAuthProjectionRepository projections, DirectAuthSessionRepository sessions,
            DirectAuthCookieService cookies, DirectAuthLoginService login, RefreshReplayCipher cipher,
            ObjectMapper mapper, AuthStateProperties properties, DirectAuthNonceProofService nonceProofs) {
        this.transactions = transactions;
        this.flows = flows;
        this.receipts = receipts;
        this.operations = operations;
        this.projections = projections;
        this.sessions = sessions;
        this.cookies = cookies;
        this.login = login;
        this.cipher = cipher;
        this.nonceProofs = nonceProofs;
        this.mapper = mapper;
        this.properties = properties;
    }

    public Result logout(HttpServletRequest request, Tuple tuple) {
        DirectAuthNonceProofService.checkSize(request);
        var ticket = cipher.reserveV2(2, () -> transactions.run(() -> {
            nonceProofs.logout(request, tuple);
            return null;
        }));
        return transactions.run(() -> {
            long projectionGeneration = projections.lockTicket(ticket);
            DirectAuthFlowState flow = flows.lock(tuple.flowId()).orElseThrow(
                    () -> new DirectAuthFailure(428, "AUTH_FLOW_REQUIRED"));
            Receipt receipt = receipts.lockLogout(tuple).orElse(null);
            LocalDateTime now = flows.now();
            cookies.requireUnique(request, flow, now, flows::findKey);
            boolean currentProof = cookies.proofs(request).stream().filter(proof -> !proof.binding())
                    .anyMatch(proof -> cookies.valid(flow, proof, now));
            boolean originalProof = receipt != null && receipt.originalProof() != null
                    && cookies.proofs(request).stream().filter(proof -> !proof.binding())
                            .anyMatch(proof -> flow.flowKeyHash().equals(proof.key())
                                    && proof.generation() == receipt.originalGeneration()
                                    && MessageDigest.isEqual(receipt.originalProof(),
                                            cookies.hmac("direct-logout-original:" + proof.value())));
            if (!currentProof && !originalProof) {
                throw new DirectAuthFailure(428, "AUTH_FLOW_COOKIE_INVALID");
            }
            if (receipt != null) {
                return replay(flow, tuple, receipt, now);
            }
            if (flow.epoch() != tuple.epoch()) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
            }
            var proof = cookies.require(request, flow, now);
            if (flow.activeLoginOperationId() != null) {
                operations.lockLogin(flow.flowId(), flow.activeLoginOperationId())
                        .orElseThrow(DirectAuthFailure::unavailable);
            }
            var session = flow.activeSessionSid() == null ? null : sessions.canonical(flow, now, true);
            long epoch = Math.incrementExact(flow.epoch());
            long revision = Math.incrementExact(flow.revision());
            long generation = Math.incrementExact(flow.flowCookieGeneration());
            String resultProof = login.flowProof(flow.flowId(), generation);
            LocalDateTime expiry = now.plusSeconds(properties.sessionIdleSeconds());
            List<String> issued = new ArrayList<>();
            issued.add(cookies.encode(cookies.flowName(flow.flowId(), generation), resultProof, now, expiry));
            issued.add(cookies.expire(proof.name()));
            if (session != null) {
                issued.add(cookies.expire(cookies.refreshName(session.sid(), session.refreshGeneration())));
            }
            issued.add(cookies.expire("refresh_token"));
            Map<String, Object> body = Map.of("logoutReceipt", Map.of("operationId", tuple.operationId(),
                    "committed", true, "flowEpoch", Long.toString(epoch), "revision", Long.toString(revision),
                    "completedAt", now.toInstant(ZoneOffset.UTC).toString()));
            Receipt result = new Receipt(flow.flowId(), tuple.operationId(), tuple.epoch(), flow.flowCookieGeneration(),
                    cookies.hmac("direct-logout-original:" + proof.value()), epoch, revision, generation,
                    null, "SUCCEEDED", now.plusSeconds(900));
            try {
                String encrypted = cipher.encryptV2(mapper.writeValueAsString(new Saved(body, issued)),
                        context(result, "operation-response-body"), ticket);
                String encryptedFlow = cipher.encryptV2(resultProof, context(result, "direct-operation-flow-cookie"),
                        ticket);
                receipts.insertLogout(flow, tuple, result.originalProof(), epoch, revision, generation,
                        encrypted, encryptedFlow);
            } catch (java.io.IOException failure) {
                throw DirectAuthFailure.unavailable();
            }
            if (session != null) {
                sessions.revoke(session);
            }
            receipts.logout(flow, tuple.operationId(), cookies.hash("auth-flow-cookie:" + resultProof), expiry);
            if (session != null) {
                projections.fence(flow.flowId(), session.sid(), epoch, revision, projectionGeneration, "REVOKED",
                        session.absoluteExpiry().plusSeconds(86400));
            }
            return new Result(flows.lock(flow.flowId()).orElseThrow(), body, issued);
        });
    }

    private Result replay(DirectAuthFlowState flow, Tuple tuple, Receipt receipt, LocalDateTime now) {
        if (receipt.epoch() != tuple.epoch()) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
        }
        if (!now.isBefore(receipt.expiry()) || "TOMBSTONE".equals(receipt.status())) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_RECEIPT_EXPIRED");
        }
        if (!"ANONYMOUS".equals(flow.lifecycle()) || flow.epoch() != receipt.resultEpoch()
                || flow.revision() != receipt.resultRevision()
                || flow.flowCookieGeneration() != receipt.resultGeneration()
                || !"LOGOUT".equals(flow.currentOperationKind())
                || !tuple.operationId().equals(flow.currentOperationId())) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
        }
        try {
            Saved saved = mapper.readValue(cipher.decryptV2(receipt.encrypted(),
                    context(receipt, "operation-response-body")), Saved.class);
            return new Result(flow, saved.data(), saved.cookies());
        } catch (java.io.IOException failure) {
            throw DirectAuthFailure.unavailable();
        }
    }

    private ReplayContext context(Receipt receipt, String payload) {
        return new ReplayContext(payload, "auth_direct_operation_receipts", "LOGOUT", receipt.flowId(),
                receipt.operationId(), "", Long.toString(receipt.epoch()), Long.toString(receipt.resultEpoch()),
                Long.toString(receipt.resultRevision()), Long.toString(receipt.resultGeneration()), "", "", "", "", "");
    }
}
