package com.muse.meomuneum.auth.directv2.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;

@Component
public class DirectAuthCookieService {
    private static final Pattern FLOW = Pattern.compile("MEO_AUTH_FLOW_V2_([A-Za-z0-9_-]{43})_g([1-9][0-9]*)");
    private static final Pattern BINDING = Pattern.compile("MEO_AUTH_BINDING_V2_([A-Za-z0-9_-]{43})_g([1-9][0-9]*)");
    private final AuthStateProperties properties;
    private final boolean secure;
    private final String sameSite;
    private final SecureRandom random = new SecureRandom();

    public record Proof(String name, String value, String key, long generation, boolean binding) {
    }

    public DirectAuthCookieService(AuthStateProperties properties,
            @Value("${auth.cookie.secure:true}") boolean secure,
            @Value("${auth.cookie.same-site:Lax}") String sameSite) {
        this.properties = properties;
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public String secret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public byte[] hmac(String value) {
        try {
            String key = properties.hmacKey();
            if (key == null || key.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw DirectAuthFailure.unavailable();
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException failure) {
            throw DirectAuthFailure.unavailable();
        }
    }

    public String hash(String value) {
        return HexFormat.of().formatHex(hmac(value));
    }

    public String key(String id) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            throw DirectAuthFailure.unavailable();
        }
    }

    public String flowName(String flowId, long generation) {
        return "MEO_AUTH_FLOW_V2_" + key(flowId) + "_g" + generation;
    }

    public String bindingName(String flowId, long generation) {
        return "MEO_AUTH_BINDING_V2_" + key(flowId) + "_g" + generation;
    }

    public String refreshName(String sid, long generation) {
        return "refresh_token_v2_" + key(sid) + "_g" + generation;
    }

    public String encode(String name, String value, LocalDateTime now, LocalDateTime expiry) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite(sameSite)
                .path("/api/v1/auth").maxAge(Math.max(0, Duration.between(now, expiry).getSeconds())).build()
                .toString();
    }

    public String expire(String name) {
        return ResponseCookie.from(name, "").httpOnly(true).secure(secure).sameSite(sameSite)
                .path("/api/v1/auth").maxAge(0).build().toString();
    }

    public List<Proof> proofs(HttpServletRequest request) {
        List<Proof> proofs = new ArrayList<>();
        if (request.getCookies() == null) {
            return proofs;
        }
        for (var cookie : request.getCookies()) {
            var flow = FLOW.matcher(cookie.getName());
            var binding = BINDING.matcher(cookie.getName());
            boolean isBinding = binding.matches();
            if (!flow.matches() && !isBinding) {
                continue;
            }
            var match = isBinding ? binding : flow;
            try {
                proofs.add(new Proof(cookie.getName(), cookie.getValue(), match.group(1),
                        Long.parseLong(match.group(2)), isBinding));
            } catch (NumberFormatException invalid) {
                throw new DirectAuthFailure(428, "AUTH_FLOW_COOKIE_INVALID");
            }
        }
        return proofs;
    }

    public boolean legacyMatches(HttpServletRequest request, DirectAuthFlowState flow, LocalDateTime now) {
        if (request.getCookies() == null || !now.isBefore(flow.cookieExpiresAt())) {
            return false;
        }
        int matches = 0;
        for (var cookie : request.getCookies()) {
            if ("MEO_AUTH_FLOW".equals(cookie.getName()) && MessageDigest.isEqual(
                    flow.cookieHash().getBytes(StandardCharsets.US_ASCII),
                    hash("auth-flow-cookie:" + cookie.getValue()).getBytes(StandardCharsets.US_ASCII))) {
                matches++;
            }
        }
        return matches == 1;
    }

    public boolean valid(DirectAuthFlowState flow, Proof proof, LocalDateTime now) {
        if (!proof.key().equals(flow.flowKeyHash())) {
            return false;
        }
        if (proof.binding()) {
            return proof.generation() == flow.bindingCookieGeneration() && flow.bindingCookieExpiresAt() != null
                    && now.isBefore(flow.bindingCookieExpiresAt()) && flow.bindingProofHmac() != null
                    && MessageDigest.isEqual(flow.bindingProofHmac(), hmac("direct-binding:" + proof.value()));
        }
        return proof.generation() == flow.flowCookieGeneration() && now.isBefore(flow.cookieExpiresAt())
                && MessageDigest.isEqual(flow.cookieHash().getBytes(StandardCharsets.US_ASCII),
                        hash("auth-flow-cookie:" + proof.value()).getBytes(StandardCharsets.US_ASCII));
    }

    public void requireUnique(HttpServletRequest request, DirectAuthFlowState target, LocalDateTime now,
            java.util.function.Function<String, java.util.Optional<DirectAuthFlowState>> finder) {
        var ids = new java.util.HashSet<String>();
        for (var proof : proofs(request)) {
            if (!proof.binding()) {
                finder.apply(proof.key()).filter(flow -> valid(flow, proof, now))
                        .ifPresent(flow -> ids.add(flow.flowId()));
            }
        }
        if (ids.size() > 1 || ids.size() == 1 && !ids.contains(target.flowId())) {
            throw new DirectAuthFailure(404, "AUTH_FLOW_NOT_FOUND");
        }
    }

    public Proof require(HttpServletRequest request, DirectAuthFlowState flow, LocalDateTime now) {
        var valid = proofs(request).stream().filter(proof -> !proof.binding() && valid(flow, proof, now)).toList();
        if (valid.size() != 1) {
            throw new DirectAuthFailure(428, "AUTH_FLOW_COOKIE_INVALID");
        }
        return valid.getFirst();
    }
}
