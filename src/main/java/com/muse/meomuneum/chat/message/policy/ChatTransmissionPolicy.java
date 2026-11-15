package com.muse.meomuneum.chat.message.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Accessed only inside the participation registry's exclusive transition. */
@Component
public class ChatTransmissionPolicy {
    private final Clock clock;
    private final ChatMessagePolicyProperties.Rules rules;
    private final Map<Long, Instant> lastAccepted = new HashMap<>();
    private final Map<Long, Map<String, Integer>> repetitions = new HashMap<>();
    private final Map<Long, Map<String, Receipt>> receipts = new HashMap<>();

    public ChatTransmissionPolicy(Clock clock, ChatMessagePolicyProperties.Rules rules) {
        this.clock = clock;
        this.rules = rules;
    }

    public Receipt previous(Long userId, String clientId) {
        return receipts.getOrDefault(userId, Map.of()).get(clientId);
    }

    public void check(Long userId, Long membershipId, String content) {
        Instant now = clock.instant();
        lastAccepted.values().removeIf(sent -> !sent.plus(rules.minimumInterval()).isAfter(now));
        Instant previous = lastAccepted.get(userId);
        if (previous != null) {
            long remaining = java.time.Duration.between(now, previous.plus(rules.minimumInterval())).toMillis();
            throw new ChatMessageRejection("RATE_LIMIT", Math.max(1, remaining));
        }
        if (repetitions.getOrDefault(membershipId, Map.of()).getOrDefault(digest(content), 0) >= 2) {
            throw new ChatMessageRejection("DUPLICATE_CONTENT");
        }
    }

    public void accepted(Long userId, Long membershipId, String clientId, String content, Long messageId) {
        lastAccepted.put(userId, clock.instant());
        repetitions.computeIfAbsent(membershipId, ignored -> new HashMap<>()).merge(digest(content), 1, Integer::sum);
        receipts.computeIfAbsent(userId, ignored -> new HashMap<>()).put(clientId,
                new Receipt(membershipId, messageId, digest(content), clock.instant().plusSeconds(86400)));
    }

    public void ended(Long membershipId) {
        repetitions.remove(membershipId);
        // UUID receipts remain as tombstones until their retention boundary; never replay another membership.
    }

    public void purgeReceipts() {
        Instant now = clock.instant();
        receipts.values().forEach(items -> items.values().removeIf(receipt -> !receipt.expiresAt().isAfter(now)));
        receipts.values().removeIf(Map::isEmpty);
    }

    public static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public record Receipt(Long membershipId, Long messageId, String contentHash, Instant expiresAt) {
    }
}
