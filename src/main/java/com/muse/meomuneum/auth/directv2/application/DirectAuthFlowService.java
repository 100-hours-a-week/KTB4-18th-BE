package com.muse.meomuneum.auth.directv2.application;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.auth.application.RefreshReplayCipher.ReplayContext;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthBootstrapRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository;
import com.muse.meomuneum.auth.directv2.security.DirectAuthCookieService;
import com.muse.meomuneum.auth.directv2.security.DirectAuthNonceProofService;

@Service
public class DirectAuthFlowService {
    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthFlowRepository flows;
    private final DirectAuthBootstrapRepository bootstraps;
    private final DirectAuthProjectionRepository projections;
    private final DirectAuthSessionRepository sessions;
    private final DirectAuthCookieService cookies;
    private final RefreshReplayCipher cipher;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final AuthStateProperties properties;

    public record Saved(Map<String, Object> data, List<String> cookies) {
    }

    public record Result(DirectAuthFlowState flow, Map<String, Object> data, List<String> cookies) {
    }

    public DirectAuthFlowService(DirectAuthTransactionRunner transactions, DirectAuthFlowRepository flows,
            DirectAuthBootstrapRepository bootstraps, DirectAuthProjectionRepository projections,
            DirectAuthSessionRepository sessions, DirectAuthCookieService cookies, RefreshReplayCipher cipher,
            ObjectMapper mapper, JdbcTemplate jdbc, AuthStateProperties properties) {
        this.transactions = transactions;
        this.flows = flows;
        this.bootstraps = bootstraps;
        this.projections = projections;
        this.sessions = sessions;
        this.cookies = cookies;
        this.cipher = cipher;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public DirectAuthFlowState resolve(HttpServletRequest request, boolean bindingAllowed) {
        LocalDateTime now = flows.now();
        var proofs = cookies.proofs(request);
        Map<String, DirectAuthFlowState> valid = new LinkedHashMap<>();
        for (var proof : proofs) {
            if (!proof.binding()) {
                flows.findKey(proof.key()).filter(flow -> cookies.valid(flow, proof, now))
                        .ifPresent(flow -> valid.put(flow.flowId(), flow));
            }
        }
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("MEO_AUTH_FLOW".equals(cookie.getName())) {
                    flows.findCookieHash(cookies.hash("auth-flow-cookie:" + cookie.getValue()))
                            .filter(flow -> cookies.legacyMatches(request, flow, now))
                            .ifPresent(flow -> valid.put(flow.flowId(), flow));
                }
            }
        }
        if (valid.size() > 1) {
            throw new DirectAuthFailure(404, "AUTH_FLOW_NOT_FOUND");
        }
        if (valid.size() == 1) {
            return rejectQueued(valid.values().iterator().next());
        }
        if (bindingAllowed) {
            var keys = new HashSet<String>();
            proofs.stream().filter(proof -> !proof.binding()).forEach(proof -> keys.add(proof.key()));
            for (var proof : proofs) {
                if (proof.binding() && (keys.isEmpty() || keys.size() == 1 && keys.contains(proof.key()))) {
                    flows.findKey(proof.key()).filter(flow -> cookies.valid(flow, proof, now))
                            .ifPresent(flow -> valid.put(flow.flowId(), flow));
                }
            }
            if (valid.size() == 1) {
                return rejectQueued(valid.values().iterator().next());
            }
        }
        throw new DirectAuthFailure(404, "AUTH_FLOW_NOT_FOUND");
    }

    private DirectAuthFlowState rejectQueued(DirectAuthFlowState flow) {
        if ("QUEUED".equals(flow.lifecycle())) {
            throw DirectAuthFailure.conflict("AUTH_FLOW_LEGACY_QUEUED");
        }
        return flow;
    }

    public Result current(HttpServletRequest request) {
        return transactions.run(() -> {
            if (cookies.proofs(request).isEmpty() && (request.getCookies() == null
                    || java.util.Arrays.stream(request.getCookies())
                            .noneMatch(cookie -> "MEO_AUTH_FLOW".equals(cookie.getName())))) {
                throw new DirectAuthFailure(404, "AUTH_FLOW_REQUIRED");
            }
            DirectAuthFlowState found = resolve(request, true);
            DirectAuthFlowState flow = flows.lock(found.flowId()).orElseThrow(DirectAuthFailure::unavailable);
            try {
                cookies.require(request, flow, flows.now());
            } catch (DirectAuthFailure bindingOnly) {
                if (!cookies.legacyMatches(request, flow, flows.now())) {
                    throw DirectAuthFailure.conflict("AUTH_FLOW_REBOOTSTRAP_REQUIRED");
                }
            }
            return new Result(flow, data(flow), List.of());
        });
    }

    public Result bootstrap(HttpServletRequest request, String operationId) {
        DirectAuthNonceProofService.checkSize(request);
        var ticket = cipher.reserveV2(1, () -> {
        });
        byte[] fingerprint = cookies.hmac("bootstrap-request:" + request.getHeader("Origin"));
        String material = cookies.proofs(request).stream().map(proof -> proof.name() + "=" + proof.value())
                .sorted().reduce("", (left, right) -> left + "\n" + right);
        byte[] proofHash = cookies.hmac("bootstrap-proof:" + material);
        return transactions.run(() -> {
            projections.lockTicket(ticket);
            boolean inserted = bootstraps.insert(operationId, fingerprint, proofHash);
            var receipt = bootstraps.lock(operationId).orElseThrow(DirectAuthFailure::unavailable);
            LocalDateTime now = flows.now();
            if (!MessageDigest.isEqual(fingerprint, receipt.fingerprint())) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
            }
            if (!now.isBefore(receipt.expiry()) || "TOMBSTONE".equals(receipt.status())) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_RECEIPT_EXPIRED");
            }
            if (!inserted) {
                DirectAuthFlowState flow = flows.lock(receipt.flowId()).orElseThrow(DirectAuthFailure::unavailable);
                boolean original = receipt.proof() != null && MessageDigest.isEqual(proofHash, receipt.proof());
                boolean result = cookies.proofs(request).stream().filter(proof -> !proof.binding())
                        .anyMatch(proof -> cookies.valid(flow, proof, now));
                if (!original && !result) {
                    throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
                }
                if (!"SUCCEEDED".equals(receipt.status()) || flow.epoch() != receipt.epoch()
                        || flow.revision() != receipt.revision()
                        || flow.flowCookieGeneration() != receipt.flowGeneration()
                        || flow.bindingCookieGeneration() != receipt.bindingGeneration()) {
                    throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
                }
                try {
                    Saved saved = mapper.readValue(cipher.decryptV2(receipt.encrypted(), context(receipt)),
                            Saved.class);
                    return new Result(flow, saved.data(), saved.cookies());
                } catch (java.io.IOException failure) {
                    throw DirectAuthFailure.unavailable();
                }
            }
            bootstraps.rateLimit(cookies.hash("bootstrap-address:" + request.getRemoteAddr()));
            DirectAuthFlowState existing = null;
            try {
                existing = resolve(request, false);
            } catch (DirectAuthFailure absent) {
                if (absent.status() != 404) {
                    throw absent;
                }
            }
            List<String> issued = new ArrayList<>();
            DirectAuthFlowState flow;
            if (existing != null) {
                flow = flows.lock(existing.flowId()).orElseThrow(DirectAuthFailure::unavailable);
                if (flow.flowKeyHash() == null && cookies.legacyMatches(request, flow, flows.now())) {
                    flow = upgradeLegacy(flow, now, issued);
                } else {
                    cookies.require(request, flow, flows.now());
                }
            } else {
                String id = UUID.randomUUID().toString();
                String flowProof = cookies.secret();
                String binding = cookies.secret();
                LocalDateTime expiry = now.plusSeconds(properties.sessionIdleSeconds());
                DirectAuthProjectionRepository.requireOne(jdbc.update("""
                        INSERT INTO auth_browser_flows
                        (flow_id, flow_cookie_hash, cookie_expires_at, created_at, updated_at, flow_key_hash,
                         binding_cookie_generation, binding_proof_hmac, binding_cookie_expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                        """, id, cookies.hash("auth-flow-cookie:" + flowProof), expiry, now, now, cookies.key(id),
                        cookies.hmac("direct-binding:" + binding), now.plusSeconds(1800)));
                flow = flows.lock(id).orElseThrow(DirectAuthFailure::unavailable);
                issued.add(cookies.encode(cookies.flowName(id, 1), flowProof, now, expiry));
                issued.add(cookies.encode(cookies.bindingName(id, 1), binding, now, now.plusSeconds(1800)));
            }
            Map<String, Object> body = data(flow);
            var completed = new DirectAuthBootstrapRepository.Receipt(operationId, fingerprint, proofHash,
                    flow.flowId(), "SUCCEEDED", flow.epoch(), flow.revision(), flow.flowCookieGeneration(),
                    flow.bindingCookieGeneration(), null, receipt.expiry());
            try {
                String encrypted = cipher.encryptV2(mapper.writeValueAsString(new Saved(body, issued)),
                        context(completed), ticket);
                bootstraps.complete(receipt, flow.flowId(), flow.lifecycle(), flow.epoch(), flow.revision(),
                        flow.flowCookieGeneration(), flow.bindingCookieGeneration(), encrypted);
            } catch (java.io.IOException failure) {
                throw DirectAuthFailure.unavailable();
            }
            return new Result(flow, body, issued);
        });
    }

    private ReplayContext context(DirectAuthBootstrapRepository.Receipt receipt) {
        return new ReplayContext("bootstrap-exact-response", "auth_flow_bootstrap_operations", "BOOTSTRAP",
                receipt.flowId(), receipt.operationId(), "", "", Long.toString(receipt.epoch()),
                Long.toString(receipt.revision()), Long.toString(receipt.flowGeneration()), "", "",
                Long.toString(receipt.bindingGeneration()), java.util.HexFormat.of().formatHex(receipt.fingerprint()),
                java.util.HexFormat.of().formatHex(receipt.proof()));
    }

    private DirectAuthFlowState upgradeLegacy(DirectAuthFlowState flow, LocalDateTime now, List<String> issued) {
        String proof = cookies.secret();
        String binding = cookies.secret();
        long generation = Math.incrementExact(flow.flowCookieGeneration());
        long bindingGeneration = Math.incrementExact(flow.bindingCookieGeneration());
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_flows SET flow_key_hash = ?, flow_cookie_hash = ?, flow_cookie_generation = ?,
                binding_cookie_generation = ?, binding_proof_hmac = ?, binding_cookie_expires_at = ?,
                revision = revision + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE flow_id = ? AND flow_key_hash IS NULL AND flow_epoch = ? AND revision = ?
                """, cookies.key(flow.flowId()), cookies.hash("auth-flow-cookie:" + proof), generation,
                bindingGeneration, cookies.hmac("direct-binding:" + binding), now.plusSeconds(1800),
                flow.flowId(), flow.epoch(), flow.revision()));
        issued.add(cookies.encode(cookies.flowName(flow.flowId(), generation), proof, now, flow.cookieExpiresAt()));
        issued.add(cookies.encode(cookies.bindingName(flow.flowId(), bindingGeneration), binding, now,
                now.plusSeconds(1800)));
        return flows.lock(flow.flowId()).orElseThrow(DirectAuthFailure::unavailable);
    }

    public Map<String, Object> data(DirectAuthFlowState flow) {
        boolean authenticated = "AUTHENTICATED".equals(flow.lifecycle());
        long generation = authenticated ? sessions.canonical(flow, flows.now(), false).refreshGeneration() : 0;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flowId", flow.flowId());
        body.put("flowEpoch", Long.toString(flow.epoch()));
        body.put("revision", Long.toString(flow.revision()));
        body.put("lifecycle", flow.lifecycle());
        body.put("authenticated", authenticated);
        body.put("refreshGeneration", Long.toString(generation));
        body.put("pendingOperations", List.of());
        body.put("lastLogoutReceipt", null);
        body.put("lastRefreshReceipt", null);
        body.put("rebootstrapReason", null);
        body.put("loginProtocol", "direct-v2");
        body.put("flowCookieGeneration", Long.toString(flow.flowCookieGeneration()));
        body.put("flowCookieExpiresAt", flow.cookieExpiresAt().toInstant(ZoneOffset.UTC).toString());
        return body;
    }
}
