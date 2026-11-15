package com.muse.meomuneum.chat.message.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** First key signs new content; retained keys validate receipts and active membership counters. */
public final class ChatMessageFingerprint {
    private final Map<String, byte[]> keys = new LinkedHashMap<>();

    public ChatMessageFingerprint(String specification) {
        try {
            for (String entry : specification.split(",")) {
                String[] parts = entry.trim().split(":", 2);
                if (parts.length != 2 || !parts[0].matches("[a-z0-9-]{1,32}")) {
                    throw new IllegalArgumentException();
                }
                byte[] key = Base64.getDecoder().decode(parts[1]);
                if (key.length < 32 || keys.putIfAbsent(parts[0], key) != null) {
                    throw new IllegalArgumentException();
                }
            }
            if (keys.isEmpty() || keys.size() > 8) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Chat fingerprint requires 1..8 unique id:base64 keys of at least 32 bytes");
        }
    }

    public String activeId() {
        return keys.keySet().iterator().next();
    }

    public Set<String> keyIds() {
        return Set.copyOf(keys.keySet());
    }

    public String digest(String keyId, String content) {
        byte[] key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("Retained Chat fingerprint key required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update("chat-send:v1\u0000".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("Chat fingerprint unavailable");
        }
    }

    public boolean matches(String keyId, String fingerprint, String content) {
        return MessageDigest.isEqual(digest(keyId, content).getBytes(StandardCharsets.US_ASCII),
                fingerprint.getBytes(StandardCharsets.US_ASCII));
    }
}
