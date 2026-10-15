package com.muse.meomuneum.auth.queue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("loadtest")
public class AuthQueueDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(AuthQueueDiagnostics.class);
    private static final Set<String> SAFE_ERRORS = Set.of(
            "INVALID_OPERATION_ID", "LOGIN_OPERATION_MISMATCH", "LOGIN_IDENTITY_MISMATCH",
            "QUEUE_LOGIN_IN_PROGRESS", "TICKET_NOT_READY", "QUEUE_WAITING", "BCRYPT_CAPACITY",
            "TICKET_EXPIRED", "TICKET_UNKNOWN", "AUTH_STATE_UNAVAILABLE", "AUTH_STATE_BUSY",
            "LOGIN_RECOVERY_UNAVAILABLE", "INVALID_CREDENTIALS", "TOKEN_ISSUE_FAILED",
            "SESSION_PROVISION_FAILED", "QUEUE_FULL", "APPEND_FENCED", "OTHER");

    private final boolean enabled;
    private final String runId;
    private final byte[] key;

    public AuthQueueDiagnostics(@Value("${auth.queue.diagnostics.enabled:false}") boolean enabled,
            @Value("${auth.queue.diagnostics.run-id:}") String runId,
            @Value("${auth.state.hmac-key:}") String key) {
        this.enabled = enabled && runId != null && runId.matches("[A-Za-z0-9._-]{1,64}")
                && key != null && key.length() >= 32;
        this.runId = runId == null ? "" : runId;
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    public String correlationId(String ticketId) {
        if (!enabled || ticketId == null || ticketId.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal((runId + "|" + ticketId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void event(String event, String ticketId, String status) {
        String correlationId = correlationId(ticketId);
        if (correlationId != null) {
            stage(correlationId, event, status == null ? "UNKNOWN" : status, "NONE", 0, null);
        }
    }

    public void stageForTicket(String stage, String ticketId, String outcome, String errorCode,
            long durationMillis, Throwable failure) {
        String correlationId = correlationId(ticketId);
        if (correlationId != null) {
            stage(correlationId, stage, outcome, errorCode, durationMillis, failure);
        }
    }

    public void stageForCorrelation(String stage, String correlationId, String outcome, String errorCode,
            long durationMillis, Throwable failure) {
        if (enabled && correlationId != null && correlationId.matches("[0-9a-fA-F]{32}")) {
            stage(correlationId.toLowerCase(), stage, outcome, errorCode, durationMillis, failure);
        }
    }

    private void stage(String correlationId, String stage, String outcome, String errorCode,
            long durationMillis, Throwable failure) {
        if (!enabled) {
            return;
        }
        String safeStage = safeToken(stage, "UNKNOWN");
        String safeOutcome = safeToken(outcome, "UNKNOWN");
        String safeError = errorCode != null && SAFE_ERRORS.contains(errorCode) ? errorCode : "NONE";
        List<String> causeTypes = new ArrayList<>(8);
        Throwable cause = failure;
        while (cause != null && causeTypes.size() < 8) {
            causeTypes.add(cause.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]", ""));
            cause = cause.getCause();
        }
        log.info("AUTH_LOGIN_STAGE run_id={} corr_id={} stage={} outcome={} error_code={} duration_ms={} causes={}",
                runId, correlationId, safeStage, safeOutcome, safeError,
                Math.max(0, durationMillis), causeTypes.isEmpty() ? "none" : String.join(",", causeTypes));
    }

    private String safeToken(String value, String fallback) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,47}") ? value : fallback;
    }

    public boolean enabled() {
        return enabled;
    }
}
