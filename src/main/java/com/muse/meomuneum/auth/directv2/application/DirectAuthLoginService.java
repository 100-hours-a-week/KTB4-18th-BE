package com.muse.meomuneum.auth.directv2.application;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.model.DirectAuthOperationState;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthLoginLifecycleRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthOperationRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository;
import com.muse.meomuneum.auth.directv2.security.DirectAuthCookieService;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@Service
public class DirectAuthLoginService {
    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthFlowRepository flows;
    private final DirectAuthOperationRepository operations;
    private final DirectAuthLoginLifecycleRepository lifecycle;
    private final DirectAuthProjectionRepository projections;
    private final DirectAuthSessionRepository sessions;
    private final DirectAuthCookieService cookies;
    private final UserAuthenticationService users;
    private final JwtTokenProvider tokens;
    private final RefreshReplayCipher cipher;
    private final AuthStateProperties properties;
    private final JwtProperties jwt;
    private final Semaphore permits;

    public record Result(int status, String message, Object data, DirectAuthFlowState flow, List<String> cookies) {
    }

    private record Claim(DirectAuthFlowState flow, DirectAuthOperationState operation, byte[] owner) {
    }

    public DirectAuthLoginService(DirectAuthTransactionRunner transactions, DirectAuthFlowRepository flows,
            DirectAuthOperationRepository operations, DirectAuthLoginLifecycleRepository lifecycle,
            DirectAuthProjectionRepository projections, DirectAuthSessionRepository sessions,
            DirectAuthCookieService cookies, UserAuthenticationService users, JwtTokenProvider tokens,
            RefreshReplayCipher cipher, AuthStateProperties properties, JwtProperties jwt) {
        this.transactions = transactions;
        this.flows = flows;
        this.operations = operations;
        this.lifecycle = lifecycle;
        this.projections = projections;
        this.sessions = sessions;
        this.cookies = cookies;
        this.users = users;
        this.tokens = tokens;
        this.cipher = cipher;
        this.properties = properties;
        this.jwt = jwt;
        permits = new Semaphore(properties.localBcryptConcurrency());
    }

    public Result login(HttpServletRequest request, LoginRequest credentials, Tuple tuple, long client) {
        transactions.run(() -> {
            projections.lockControl();
            DirectAuthFlowState flow = flows.lock(tuple.flowId()).orElseThrow(
                    () -> new DirectAuthFailure(428, "AUTH_FLOW_REQUIRED"));
            requireProof(request, flow, operations.lockLogin(tuple.flowId(), tuple.operationId()).orElse(null));
            lifecycle.recoverExpired(flow, flows.now());
            return null;
        });
        boolean acquired = permits.tryAcquire();
        try {
            Claim claim = transactions.run(() -> claim(request, tuple, client, acquired));
            if (claim.owner() == null) {
                return status(claim.flow(), claim.operation());
            }
            User user;
            try {
                user = users.authenticate(credentials.email(), credentials.password());
            } catch (AuthenticationFailedException invalid) {
                return transactions.run(() -> finishInvalid(claim, tuple));
            }
            String sid = UUID.randomUUID().toString();
            String access = tokens.createAccessToken(user, sid);
            String refresh = tokens.createRefreshToken(user, sid);
            LocalDateTime tokenExpiry = tokens.parseRefreshToken(refresh).expiresAt()
                    .atOffset(ZoneOffset.UTC).toLocalDateTime();
            String encrypted = cipher.encrypt(refresh);
            return transactions.run(() -> finish(claim, tuple, user, sid, access, refresh, encrypted, tokenExpiry));
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    private Claim claim(HttpServletRequest request, Tuple tuple, long client, boolean acquired) {
        projections.lockControl();
        DirectAuthFlowState flow = flows.lock(tuple.flowId()).orElseThrow(
                () -> new DirectAuthFailure(428, "AUTH_FLOW_REQUIRED"));
        var existing = operations.lockLogin(tuple.flowId(), tuple.operationId()).orElse(null);
        requireProof(request, flow, existing);
        if (existing != null) {
            if (existing.startingEpoch() != tuple.epoch()
                    || existing.clientExpiresAt() != null && existing.clientExpiresAt() != client) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
            }
            requireLive(existing);
            return new Claim(flow, existing, null);
        }
        if (flow.epoch() != tuple.epoch() || !"ANONYMOUS".equals(flow.lifecycle())) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
        }
        long effective = lifecycle.effective(client, flows.now());
        if (!acquired || flow.activeLoginOperationId() != null) {
            lifecycle.reject(flow, tuple, client, effective,
                    !acquired ? "AUTH_BCRYPT_CAPACITY" : "AUTH_LOGIN_IN_PROGRESS");
            return new Claim(flow, operations.lockLogin(tuple.flowId(), tuple.operationId()).orElseThrow(), null);
        }
        UUID id = UUID.randomUUID();
        byte[] owner = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
        var proof = cookies.require(request, flow, flows.now());
        lifecycle.claim(flow, tuple, client, effective, owner, cookies.hmac("direct-login-original:" + proof.value()));
        return new Claim(flows.lock(tuple.flowId()).orElseThrow(),
                operations.lockLogin(tuple.flowId(), tuple.operationId()).orElseThrow(), owner);
    }

    private Result finish(Claim claim, Tuple tuple, User user, String sid, String access, String refresh,
            String encrypted, LocalDateTime tokenExpiry) {
        long projectionGeneration = projections.lockAdmission(properties.globalSessionCapacity());
        var operation = operations.lockOwnedLogin(tuple.flowId(), tuple.operationId(), tuple.epoch(),
                claim.flow().flowCookieGeneration(), claim.owner()).orElseThrow(
                        () -> DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED"));
        DirectAuthFlowState flow = flows.lock(tuple.flowId()).orElseThrow();
        LocalDateTime now = flows.now();
        LocalDateTime absolute = now.plusSeconds(properties.sessionAbsoluteSeconds());
        if (tokenExpiry.isBefore(absolute)) {
            absolute = tokenExpiry;
        }
        LocalDateTime idle = now.plusSeconds(properties.sessionIdleSeconds());
        if (absolute.isBefore(idle)) {
            idle = absolute;
        }
        long epoch = Math.incrementExact(flow.epoch());
        long revision = Math.incrementExact(flow.revision());
        long generation = Math.incrementExact(flow.flowCookieGeneration());
        String flowProof = flowProof(flow.flowId(), generation);
        sessions.requireCapacity(user.getId(), properties.maxDeviceSessions(), properties.globalSessionCapacity());
        lifecycle.complete(flow, operation, claim.owner(), "SUCCEEDED", epoch, revision, sid, generation);
        var accessClaims = tokens.parseAccessToken(access);
        sessions.insert(sid, flow, epoch, user.getId(), idle, absolute, cookies.hash("refresh:" + refresh), encrypted,
                accessClaims.tokenId(), accessClaims.expiresAt().atOffset(ZoneOffset.UTC).toLocalDateTime());
        lifecycle.activate(flow, tuple.operationId(), sid, cookies.hash("auth-flow-cookie:" + flowProof), idle);
        projections.fence(flow.flowId(), sid, epoch, revision, projectionGeneration, "ACTIVE",
                absolute.plusSeconds(86400));
        DirectAuthFlowState result = flows.lock(flow.flowId()).orElseThrow();
        return token(result, access, refresh, sid, flowProof, now, idle);
    }

    private Result finishInvalid(Claim claim, Tuple tuple) {
        projections.lockControl();
        var operation = operations.lockOwnedLogin(tuple.flowId(), tuple.operationId(), tuple.epoch(),
                claim.flow().flowCookieGeneration(), claim.owner()).orElseThrow(
                        () -> DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED"));
        DirectAuthFlowState flow = flows.lock(tuple.flowId()).orElseThrow();
        lifecycle.complete(flow, operation, claim.owner(), "INVALID_CREDENTIALS", flow.epoch(),
                Math.incrementExact(flow.revision()), null, flow.flowCookieGeneration());
        lifecycle.release(flow, tuple.operationId());
        return status(flows.lock(flow.flowId()).orElseThrow(),
                operations.lockLogin(flow.flowId(), tuple.operationId()).orElseThrow());
    }

    public String flowProof(String id, long generation) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                cookies.hmac("direct-flow-proof:" + id + ":" + generation));
    }

    public void requireProof(HttpServletRequest request, DirectAuthFlowState flow, DirectAuthOperationState operation) {
        LocalDateTime now = flows.now();
        cookies.requireUnique(request, flow, now, flows::findKey);
        boolean valid = cookies.proofs(request).stream().filter(proof -> !proof.binding())
                .anyMatch(proof -> cookies.valid(flow, proof, now) || operation != null
                        && proof.key().equals(flow.flowKeyHash()) && operation.proofGeneration() != null
                        && proof.generation() == operation.proofGeneration() && operation.originalProof() != null
                        && now.isBefore(flow.cookieExpiresAt()) && MessageDigest.isEqual(operation.originalProof(),
                                cookies.hmac("direct-login-original:" + proof.value())));
        if (!valid) {
            throw new DirectAuthFailure(428, "AUTH_FLOW_COOKIE_INVALID");
        }
    }

    public void requireLive(DirectAuthOperationState operation) {
        LocalDateTime now = flows.now();
        if (operation.receiptExpiresAt() == null || !now.isBefore(operation.receiptExpiresAt())
                || operation.effectiveExpiresAt() == null || operation.clientExpiresAt() == null
                || operation.clientExpiresAt() <= 0 || operation.clientExpiresAt() > 9_007_199_254_740_991L
                || operation.effectiveExpiresAt() <= 0 || operation.effectiveExpiresAt() > operation.clientExpiresAt()
                || DirectAuthLoginLifecycleRepository.micros(now) >= operation.effectiveExpiresAt() * 1000) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_RECEIPT_EXPIRED");
        }
    }

    public Result status(DirectAuthFlowState flow, DirectAuthOperationState operation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flowId", flow.flowId());
        body.put("operationId", operation.operationId());
        body.put("startingEpoch", Long.toString(operation.startingEpoch()));
        body.put("status", operation.status());
        body.put("resultEpoch", operation.resultEpoch() == null ? null : operation.resultEpoch().toString());
        body.put("resultRevision", operation.resultRevision() == null ? null : operation.resultRevision().toString());
        if (!"IN_PROGRESS".equals(operation.status())) {
            body.put("reasonCode", operation.reasonCode());
        }
        int status = switch (operation.status()) {
            case "IN_PROGRESS" -> 202;
            case "INVALID_CREDENTIALS" -> 401;
            case "NOT_ADMITTED" -> 429;
            default -> 200;
        };
        return new Result(status, status == 202 ? "LOGIN_IN_PROGRESS" : "login operation", body, flow, List.of());
    }

    public Result token(DirectAuthFlowState flow, String access, String refresh, String sid, String proof,
            LocalDateTime now, LocalDateTime expiry) {
        return new Result(200, "login success", Map.of("access_token", access,
                "expires_in", Long.toString(jwt.accessTokenExpirationSeconds())), flow,
                List.of(cookies.encode(cookies.flowName(flow.flowId(), flow.flowCookieGeneration()), proof, now,
                        expiry),
                        cookies.encode(cookies.refreshName(sid, 1), refresh, now, expiry)));
    }
}
