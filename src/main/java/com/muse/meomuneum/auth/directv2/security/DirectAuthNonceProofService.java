package com.muse.meomuneum.auth.directv2.security;

import java.security.MessageDigest;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthReceiptRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRefreshRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository;

@Component
public class DirectAuthNonceProofService {
    private final DirectAuthFlowRepository flows;
    private final DirectAuthReceiptRepository logouts;
    private final DirectAuthRefreshRepository refreshes;
    private final DirectAuthSessionRepository sessions;
    private final DirectAuthCookieService cookies;
    private final DirectAuthRefreshProofService proofs;

    public DirectAuthNonceProofService(DirectAuthFlowRepository flows, DirectAuthReceiptRepository logouts,
            DirectAuthRefreshRepository refreshes, DirectAuthSessionRepository sessions,
            DirectAuthCookieService cookies, DirectAuthRefreshProofService proofs) {
        this.flows = flows;
        this.logouts = logouts;
        this.refreshes = refreshes;
        this.sessions = sessions;
        this.cookies = cookies;
        this.proofs = proofs;
    }

    public static void checkSize(HttpServletRequest request) {
        if (request.getCookies() != null) {
            long size = 0;
            for (var cookie : request.getCookies()) {
                size += cookie.getName().length() + cookie.getValue().length();
            }
            if (request.getCookies().length > 64 || size > 16384) {
                throw new DirectAuthFailure(400, "INVALID_AUTH_FLOW_TUPLE");
            }
        }
    }

    public void logout(HttpServletRequest request, Tuple tuple) {
        var flow = flows.find(tuple.flowId()).orElseThrow(() -> new DirectAuthFailure(428, "AUTH_FLOW_REQUIRED"));
        var now = flows.now();
        cookies.requireUnique(request, flow, now, flows::findKey);
        if (cookies.proofs(request).stream().filter(proof -> !proof.binding())
                .anyMatch(proof -> cookies.valid(flow, proof, now))) {
            return;
        }
        var receipt = logouts.findLogout(tuple, false).orElse(null);
        if (receipt != null && receipt.epoch() == tuple.epoch() && now.isBefore(receipt.expiry())
                && receipt.originalProof() != null && cookies.proofs(request).stream()
                        .filter(proof -> !proof.binding()).anyMatch(proof -> proof.key().equals(flow.flowKeyHash())
                                && proof.generation() == receipt.originalGeneration()
                                && MessageDigest.isEqual(receipt.originalProof(),
                                        cookies.hmac("direct-logout-original:" + proof.value())))) {
            return;
        }
        throw new DirectAuthFailure(428, "AUTH_FLOW_COOKIE_INVALID");
    }

    public void refresh(HttpServletRequest request, Tuple tuple) {
        var flow = flows.find(tuple.flowId()).orElseThrow(() -> new DirectAuthFailure(428, "AUTH_FLOW_REQUIRED"));
        var now = flows.now();
        cookies.requireUnique(request, flow, now, flows::findKey);
        var receipt = refreshes.find(flow.flowId(), tuple.operationId(), false).orElse(null);
        var winner = "REFRESH".equals(flow.currentOperationKind()) && flow.currentOperationId() != null
                ? refreshes.find(flow.flowId(), flow.currentOperationId(), false).orElse(null)
                : null;
        if (receipt != null && receipt.epoch() == tuple.epoch() && proofs.original(request, flow, receipt)
                || winner != null && winner.epoch() == tuple.epoch() && proofs.original(request, flow, winner)) {
            return;
        }
        var session = sessions.require(flow, now, false);
        proofs.current(request, flow, session, now);
    }
}
