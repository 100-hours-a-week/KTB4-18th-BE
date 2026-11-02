package com.muse.meomuneum.auth.directv2.application;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.auth.application.RefreshReplayCipher.ReplayContext;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.model.DirectAuthNonceTicket;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthLoginLifecycleRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRefreshRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRefreshRepository.Receipt;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository.Session;
import com.muse.meomuneum.auth.directv2.security.DirectAuthCookieService;
import com.muse.meomuneum.auth.directv2.security.DirectAuthNonceProofService;
import com.muse.meomuneum.auth.directv2.security.DirectAuthRefreshProofService;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@Service
public class DirectAuthRefreshService {
    public record Result(int status, String message, Map<String, Object> data, DirectAuthFlowState flow,
            List<String> cookies) {
    }

    public record Saved(Map<String, Object> data, List<String> cookies) {
    }

    private record Preparation(Session session, Result result) {
    }

    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthFlowRepository flows;
    private final DirectAuthSessionRepository sessions;
    private final DirectAuthRefreshRepository receipts;
    private final DirectAuthProjectionRepository projections;
    private final DirectAuthCookieService cookies;
    private final DirectAuthRefreshProofService proofs;
    private final DirectAuthLoginService login;
    private final UserAuthenticationService users;
    private final JwtTokenProvider tokens;
    private final RefreshReplayCipher cipher;
    private final DirectAuthNonceProofService nonceProofs;
    private final ObjectMapper mapper;
    private final AuthStateProperties properties;
    private final JwtProperties jwt;
    private final boolean verified;

    public DirectAuthRefreshService(DirectAuthTransactionRunner transactions, DirectAuthFlowRepository flows,
            DirectAuthSessionRepository sessions, DirectAuthRefreshRepository receipts,
            DirectAuthProjectionRepository projections, DirectAuthCookieService cookies,
            DirectAuthRefreshProofService proofs,
            DirectAuthLoginService login, UserAuthenticationService users, JwtTokenProvider tokens,
            RefreshReplayCipher cipher,
            ObjectMapper mapper, AuthStateProperties properties, JwtProperties jwt,
            @Value("${auth.direct-v2.refresh-verified:false}") boolean verified,
            DirectAuthNonceProofService nonceProofs) {
        this.transactions = transactions;
        this.flows = flows;
        this.sessions = sessions;
        this.receipts = receipts;
        this.projections = projections;
        this.cookies = cookies;
        this.proofs = proofs;
        this.login = login;
        this.users = users;
        this.tokens = tokens;
        this.cipher = cipher;
        this.nonceProofs = nonceProofs;
        this.mapper = mapper;
        this.properties = properties;
        this.jwt = jwt;
        this.verified = verified;
    }

    public Result refresh(HttpServletRequest request, Tuple tuple, long client) {
        if (!verified) {
            throw DirectAuthFailure.unavailable();
        }
        DirectAuthNonceProofService.checkSize(request);
        var ticket = cipher.reserveV2(3, () -> transactions.run(() -> {
            if (!projections.generationMode()) {
                throw DirectAuthFailure.unavailable();
            }
            nonceProofs.refresh(request, tuple);
            return null;
        }));
        Preparation preparation = transactions.run(() -> {
            projections.checkTicket(ticket);
            var flow = flows.lock(tuple.flowId()).orElseThrow(() -> new DirectAuthFailure(428, "AUTH_FLOW_REQUIRED"));
            var result = existing(request, tuple, client, flow);
            if (result != null) {
                return new Preparation(null, result);
            }
            var session = sessions.require(flow, flows.now(), true);
            proofs.current(request, flow, session, flows.now());
            effective(client, flows.now());
            return new Preparation(session, null);
        });
        if (preparation.result() != null) {
            return preparation.result();
        }
        var user = users.findActiveUser(preparation.session().userId());
        String access = tokens.createAccessToken(user, preparation.session().sid());
        String refresh = tokens.createRefreshToken(user, preparation.session().sid());
        String encrypted = cipher.encrypt(refresh);
        return transactions
                .run(() -> commit(request, tuple, client, preparation.session().sid(), access, refresh, encrypted,
                        ticket));
    }

    private Result existing(HttpServletRequest request, Tuple tuple, long client, DirectAuthFlowState flow) {
        var receipt = receipts.find(flow.flowId(), tuple.operationId(), true).orElse(null);
        var session = flow.activeSessionSid() == null
                ? null
                : sessions.find(flow.activeSessionSid(), false).orElse(null);
        var now = flows.now();
        cookies.requireUnique(request, flow, now, flows::findKey);
        if (receipt != null && !"NEGATIVE_STALE".equals(receipt.status())) {
            boolean tombstoneProof = "TOMBSTONE".equals(receipt.status()) && session != null
                    && session.active(flow, now)
                    && cookies.proofs(request).stream().filter(proof -> !proof.binding())
                            .anyMatch(proof -> cookies.valid(flow, proof, now))
                    && proofs.matchesCurrent(proofs.token(request, session.sid(), session.refreshGeneration()),
                            session);
            if (!proofs.original(request, flow, receipt) && !proofs.result(request, flow, session, receipt, now)
                    && !tombstoneProof) {
                throw new DirectAuthFailure(428, "AUTH_FLOW_COOKIE_INVALID");
            }
            if (receipt.epoch() != tuple.epoch()) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
            }
            if ("TOMBSTONE".equals(receipt.status())) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_RECEIPT_EXPIRED");
            }
            if (receipt.client() == null || receipt.client() != client) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
            }
            requireLive(receipt);
            if (!proofs.canonical(flow, session, receipt) || session == null || !session.active(flow, now)) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
            }
            try {
                var saved = mapper.readValue(
                        cipher.decryptV2(receipt.encryptedBody(), context(receipt, "operation-response-body")),
                        Saved.class);
                return new Result(200, "token refreshed", saved.data(), flow, saved.cookies());
            } catch (java.io.IOException failure) {
                throw DirectAuthFailure.unavailable();
            }
        }
        if (flow.epoch() == tuple.epoch() && receipt == null) {
            return null;
        }
        Receipt winner = "REFRESH".equals(flow.currentOperationKind())
                ? receipts.find(flow.flowId(), flow.currentOperationId(), true).orElse(null)
                : null;
        boolean current = session != null && session.active(flow, now)
                && cookies.proofs(request).stream().filter(proof -> !proof.binding())
                        .anyMatch(proof -> cookies.valid(flow, proof, now))
                && proofs.matchesCurrent(proofs.token(request, session.sid(), session.refreshGeneration()), session);
        boolean original = winner != null && proofs.canonical(flow, session, winner)
                && winner.epoch() == tuple.epoch() && proofs.original(request, flow, winner);
        if (!current && !original) {
            throw new DirectAuthFailure(428, "AUTH_FLOW_COOKIE_INVALID");
        }
        if (receipt != null) {
            if (receipt.epoch() != tuple.epoch()) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
            }
            return new Result(409, "AUTH_REFRESH_STALE", null, flow, List.of());
        }
        if (!original || winner == null) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
        }
        requireLive(winner);
        receipts.negative(tuple);
        return new Result(409, "AUTH_REFRESH_STALE", null, flow, List.of());
    }

    private Result commit(HttpServletRequest request, Tuple tuple, long client, String sid, String access,
            String refresh, String encrypted, DirectAuthNonceTicket ticket) {
        long projectionGeneration = projections.lockTicket(ticket);
        var flow = flows.lock(tuple.flowId()).orElseThrow(() -> new DirectAuthFailure(428, "AUTH_FLOW_REQUIRED"));
        var existing = existing(request, tuple, client, flow);
        if (existing != null) {
            return existing;
        }
        var now = flows.now();
        var session = sessions.require(flow, now, true);
        if (!sid.equals(session.sid())) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
        }
        String originalRefresh = proofs.current(request, flow, session, now);
        var originalFlow = cookies.require(request, flow, now);
        long cutoff = effective(client, now);
        long epoch = Math.incrementExact(flow.epoch());
        long revision = Math.incrementExact(flow.revision());
        long generation = Math.incrementExact(flow.flowCookieGeneration());
        long refreshGeneration = Math.incrementExact(session.refreshGeneration());
        var idle = now.plusSeconds(properties.sessionIdleSeconds());
        if (session.absoluteExpiry().isBefore(idle)) {
            idle = session.absoluteExpiry();
        }
        String proof = login.flowProof(flow.flowId(), generation);
        var body = Map.<String, Object>of("access_token", access, "expires_in",
                Long.toString(jwt.accessTokenExpirationSeconds()));
        List<String> issued = List.of(cookies.encode(cookies.flowName(flow.flowId(), generation), proof, now, idle),
                cookies.encode(cookies.refreshName(sid, refreshGeneration), refresh, now, idle),
                cookies.expire(originalFlow.name()),
                cookies.expire(cookies.refreshName(sid, session.refreshGeneration())));
        var receipt = new Receipt(flow.flowId(), tuple.operationId(), tuple.epoch(), flow.flowCookieGeneration(),
                cookies.hmac("direct-refresh-flow:" + originalFlow.value()),
                cookies.hmac("direct-refresh-token:" + originalRefresh),
                sid, epoch, revision, generation, refreshGeneration, "SUCCEEDED", client, cutoff, now.plusSeconds(900),
                null, null, null);
        try {
            receipt = new Receipt(receipt.flowId(), receipt.id(), receipt.epoch(), receipt.originalGeneration(),
                    receipt.originalFlow(),
                    receipt.originalRefresh(), sid, epoch, revision, generation, refreshGeneration, "SUCCEEDED", client,
                    cutoff, receipt.expiry(),
                    cipher.encryptV2(mapper.writeValueAsString(new Saved(body, issued)),
                            context(receipt, "operation-response-body"), ticket),
                    cipher.encryptV2(proof, context(receipt, "direct-operation-flow-cookie"), ticket),
                    cipher.encryptV2(refresh, context(receipt, "refresh-cookie-token"), ticket));
        } catch (java.io.IOException failure) {
            throw DirectAuthFailure.unavailable();
        }
        var accessClaims = tokens.parseAccessToken(access);
        requireLive(receipt);
        receipts.save(receipt);
        receipts.rotate(flow, session, tuple, cookies.hash("auth-flow-cookie:" + proof), idle,
                cookies.hash("refresh:" + refresh), encrypted, accessClaims.tokenId(),
                accessClaims.expiresAt().atOffset(ZoneOffset.UTC).toLocalDateTime(), cutoff);
        projections.fence(flow.flowId(), sid, epoch, revision, projectionGeneration, "ACTIVE",
                session.absoluteExpiry().plusSeconds(86400));
        return new Result(200, "token refreshed", body, flows.lock(flow.flowId()).orElseThrow(), issued);
    }

    public void requireLive(Receipt receipt) {
        var now = flows.now();
        if (receipt.client() == null || receipt.effective() == null || receipt.client() <= 0
                || receipt.client() > 9_007_199_254_740_991L || receipt.effective() <= 0
                || receipt.effective() > receipt.client() || !now.isBefore(receipt.expiry())
                || DirectAuthLoginLifecycleRepository.micros(now) >= receipt.effective() * 1000) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_RECEIPT_EXPIRED");
        }
    }

    private long effective(long client, LocalDateTime now) {
        long micros = DirectAuthLoginLifecycleRepository.micros(now);
        long cutoff;
        try {
            cutoff = Math.min(Math.multiplyExact(client, 1000L), Math.addExact(micros, 900_000_000L)) / 1000;
        } catch (ArithmeticException invalid) {
            throw new DirectAuthFailure(400, "INVALID_CLIENT_EXPIRY");
        }
        if (cutoff * 1000 <= micros) {
            throw new DirectAuthFailure(400, "INVALID_CLIENT_EXPIRY");
        }
        return cutoff;
    }

    public ReplayContext context(Receipt receipt, String payload) {
        return new ReplayContext(payload, "auth_direct_operation_receipts", "REFRESH", receipt.flowId(), receipt.id(),
                "",
                Long.toString(receipt.epoch()), receipt.resultEpoch().toString(), receipt.resultRevision().toString(),
                receipt.flowGeneration().toString(), receipt.refreshGeneration().toString(), "", "", "", "");
    }
}
