package com.muse.meomuneum.auth.directv2.application;

import java.nio.ByteBuffer;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthNonceTicket;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRecoveryRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRecoveryRepository.Recovery;
import com.muse.meomuneum.auth.directv2.security.DirectAuthCookieService;
import com.muse.meomuneum.auth.directv2.security.DirectAuthNonceProofService;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@Service
public class DirectAuthRefreshRecoveryService {
    public record Saved(Map<String, Object> data, List<String> cookies) {
    }

    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthProjectionRepository projections;
    private final DirectAuthRecoveryLifecycle lifecycle;
    private final DirectAuthRecoveryRepository recoveries;
    private final DirectAuthFlowRepository flows;
    private final DirectAuthCookieService cookies;
    private final DirectAuthLoginService login;
    private final JwtTokenProvider tokens;
    private final UserAuthenticationService users;
    private final RefreshReplayCipher cipher;
    private final ObjectMapper mapper;
    private final JwtProperties jwt;
    private final boolean verified;

    public DirectAuthRefreshRecoveryService(DirectAuthTransactionRunner transactions,
            DirectAuthProjectionRepository projections, DirectAuthRecoveryLifecycle lifecycle,
            DirectAuthRecoveryRepository recoveries, DirectAuthFlowRepository flows, DirectAuthCookieService cookies,
            DirectAuthLoginService login, JwtTokenProvider tokens, UserAuthenticationService users,
            RefreshReplayCipher cipher, ObjectMapper mapper, JwtProperties jwt,
            @Value("${auth.direct-v2.refresh-recovery-verified:false}") boolean verified) {
        this.transactions = transactions;
        this.projections = projections;
        this.lifecycle = lifecycle;
        this.recoveries = recoveries;
        this.flows = flows;
        this.cookies = cookies;
        this.login = login;
        this.tokens = tokens;
        this.users = users;
        this.cipher = cipher;
        this.mapper = mapper;
        this.jwt = jwt;
        this.verified = verified;
    }

    public DirectAuthRefreshService.Result recover(HttpServletRequest request, Tuple tuple, String id, long client) {
        if (!verified) {
            throw DirectAuthFailure.unavailable();
        }
        DirectAuthNonceProofService.checkSize(request);
        var ticket = cipher.reserveV2(3, () -> transactions.run(() -> {
            lifecycle.preflight(request, tuple);
            return null;
        }));
        UUID random = UUID.randomUUID();
        byte[] owner = ByteBuffer.allocate(16).putLong(random.getMostSignificantBits())
                .putLong(random.getLeastSignificantBits()).array();
        var claim = transactions.run(() -> {
            projections.checkTicket(ticket);
            return lifecycle.claim(request, tuple, id, client, owner);
        });
        if (claim.replay()) {
            return transactions.run(() -> {
                projections.checkTicket(ticket);
                var current = lifecycle.claim(request, tuple, id, client, owner);
                if (!current.replay()) {
                    throw DirectAuthFailure.unavailable();
                }
                return replay(current.source(), current.recovery());
            });
        }
        if (claim.owner() == null) {
            return new DirectAuthRefreshService.Result(202, "RECOVERY_IN_PROGRESS", Map.of(
                    "flowId", tuple.flowId(), "operationId", tuple.operationId(), "recoveryOperationId", id,
                    "startingEpoch", Long.toString(tuple.epoch()), "status", "IN_PROGRESS",
                    "leaseExpiresAt", claim.recovery().lease().toInstant(ZoneOffset.UTC).toString()),
                    claim.source().flow(), List.of());
        }
        var user = users.findActiveUser(claim.session().userId());
        String access = tokens.createAccessToken(user, claim.session().sid());
        String refresh = tokens.createRefreshToken(user, claim.session().sid());
        String encrypted = cipher.encrypt(refresh);
        return transactions.run(() -> complete(request, tuple, id, claim.owner(), access, refresh, encrypted, ticket));
    }

    private DirectAuthRefreshService.Result complete(HttpServletRequest request, Tuple tuple, String id, byte[] owner,
            String access, String refresh, String encrypted, DirectAuthNonceTicket ticket) {
        long projectionGeneration = projections.lockTicket(ticket);
        var source = lifecycle.lock(request, tuple);
        var row = recoveries.find(tuple.flowId(), id, true).orElseThrow(DirectAuthFailure::unavailable);
        lifecycle.identity(row, source, id);
        var session = lifecycle.session(source);
        lifecycle.authorizeMutation(request, source, session);
        lifecycle.owner(source, session, row, owner);
        var flow = source.flow();
        long revision = Math.incrementExact(flow.revision());
        long flowGeneration = Math.incrementExact(flow.flowCookieGeneration());
        long refreshGeneration = Math.incrementExact(session.refreshGeneration());
        String proof = login.flowProof(flow.flowId(), flowGeneration);
        Map<String, Object> body = Map.of("flowId", flow.flowId(), "operationId", tuple.operationId(),
                "recoveryOperationId", id, "startingEpoch", Long.toString(tuple.epoch()),
                "resultEpoch", Long.toString(flow.epoch()), "resultRevision", Long.toString(revision),
                "access_token", access, "expires_in", Long.toString(jwt.accessTokenExpirationSeconds()));
        var now = flows.now();
        List<String> issued = List.of(
                cookies.encode(cookies.flowName(flow.flowId(), flowGeneration), proof, now, session.idleExpiry()),
                cookies.encode(cookies.refreshName(session.sid(), refreshGeneration), refresh, now,
                        session.idleExpiry()));
        String saved;
        try {
            saved = cipher.encryptV2(mapper.writeValueAsString(new Saved(body, issued)),
                    lifecycle.context(row, "refresh-recovery-response-body", flow.epoch(), revision, flowGeneration,
                            refreshGeneration),
                    ticket);
        } catch (java.io.IOException unavailable) {
            throw DirectAuthFailure.unavailable();
        }
        String savedFlow = cipher.encryptV2(proof, lifecycle.context(row, "refresh-recovery-flow-cookie",
                flow.epoch(), revision, flowGeneration, refreshGeneration), ticket);
        String savedRefresh = cipher.encryptV2(refresh, lifecycle.context(row, "refresh-recovery-refresh-cookie",
                flow.epoch(), revision, flowGeneration, refreshGeneration), ticket);
        recoveries.complete(flow, session, row, owner, revision, flowGeneration, refreshGeneration, saved, savedFlow,
                savedRefresh);
        var claims = tokens.parseAccessToken(access);
        recoveries.rotate(flow, session, row, cookies.hash("auth-flow-cookie:" + proof),
                cookies.hash("refresh:" + refresh),
                encrypted, claims.tokenId(), claims.expiresAt().atOffset(ZoneOffset.UTC).toLocalDateTime());
        projections.fence(flow.flowId(), session.sid(), flow.epoch(), revision, projectionGeneration, "ACTIVE",
                session.absoluteExpiry().plusSeconds(86400));
        return new DirectAuthRefreshService.Result(200, "refresh session recovered", body,
                flows.lock(flow.flowId()).orElseThrow(), issued);
    }

    private DirectAuthRefreshService.Result replay(DirectAuthRecoveryLifecycle.Source source, Recovery row) {
        try {
            Saved saved = mapper.readValue(cipher.decryptV2(row.encrypted(), lifecycle.context(row,
                    "refresh-recovery-response-body", row.resultEpoch(), row.resultRevision(), row.flowGeneration(),
                    row.refreshGeneration())), Saved.class);
            return new DirectAuthRefreshService.Result(200, "refresh session recovered", saved.data(), source.flow(),
                    saved.cookies());
        } catch (java.io.IOException unavailable) {
            throw DirectAuthFailure.unavailable();
        }
    }
}
