package com.muse.meomuneum.auth.directv2.security;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRefreshRepository.Receipt;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository.Session;
import com.muse.meomuneum.global.security.JwtTokenProvider;

@Component
public class DirectAuthRefreshProofService {
    private final DirectAuthCookieService cookies;
    private final JwtTokenProvider tokens;

    public DirectAuthRefreshProofService(DirectAuthCookieService cookies, JwtTokenProvider tokens) {
        this.cookies = cookies;
        this.tokens = tokens;
    }

    public String token(HttpServletRequest request, String sid, long generation) {
        List<String> candidates = new ArrayList<>();
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (cookies.refreshName(sid, generation).equals(cookie.getName())) {
                    candidates.add(cookie.getValue());
                }
            }
        }
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    public String current(HttpServletRequest request, DirectAuthFlowState flow, Session session, LocalDateTime now) {
        cookies.require(request, flow, now);
        String token = token(request, session.sid(), session.refreshGeneration());
        if (!matchesCurrent(token, session)) {
            throw new DirectAuthFailure(401, "AUTH_SESSION_INVALID");
        }
        var claims = tokens.parseRefreshToken(token);
        if (!session.sid().equals(claims.sessionId()) || session.userId() != claims.userId()) {
            throw new DirectAuthFailure(401, "AUTH_SESSION_INVALID");
        }
        return token;
    }

    public boolean matchesCurrent(String token, Session session) {
        return token != null && session.refreshHash() != null && MessageDigest.isEqual(
                cookies.hash("refresh:" + token).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                session.refreshHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    public boolean original(HttpServletRequest request, DirectAuthFlowState flow, Receipt receipt) {
        if (receipt.sid() == null || receipt.originalFlow() == null || receipt.originalRefresh() == null
                || receipt.originalGeneration() == null || receipt.refreshGeneration() == null) {
            return false;
        }
        String token = token(request, receipt.sid(), receipt.refreshGeneration() - 1);
        return token != null
                && MessageDigest.isEqual(receipt.originalRefresh(), cookies.hmac("direct-refresh-token:" + token))
                && cookies.proofs(request).stream().filter(proof -> !proof.binding())
                        .anyMatch(proof -> proof.key().equals(flow.flowKeyHash())
                                && proof.generation() == receipt.originalGeneration()
                                && MessageDigest.isEqual(receipt.originalFlow(),
                                        cookies.hmac("direct-refresh-flow:" + proof.value())));
    }

    public boolean result(HttpServletRequest request, DirectAuthFlowState flow, Session session, Receipt receipt,
            LocalDateTime now) {
        return canonical(flow, session, receipt) && cookies.proofs(request).stream().filter(proof -> !proof.binding())
                .anyMatch(proof -> cookies.valid(flow, proof, now))
                && matchesCurrent(token(request, session.sid(), session.refreshGeneration()), session);
    }

    public boolean canonical(DirectAuthFlowState flow, Session session, Receipt receipt) {
        return session != null && receipt.resultEpoch() != null && receipt.resultRevision() != null
                && receipt.flowGeneration() != null && receipt.refreshGeneration() != null
                && "SUCCEEDED".equals(receipt.status()) && "REFRESH".equals(flow.currentOperationKind())
                && receipt.id().equals(flow.currentOperationId()) && receipt.resultEpoch() == flow.epoch()
                && receipt.resultRevision() == flow.revision()
                && receipt.flowGeneration() == flow.flowCookieGeneration()
                && receipt.refreshGeneration() == session.refreshGeneration() && receipt.sid().equals(session.sid());
    }
}
