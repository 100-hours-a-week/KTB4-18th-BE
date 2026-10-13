package com.muse.meomuneum.chat.message.query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ChatCursorCodec {
    private final ObjectMapper mapper;
    private final Clock clock;
    private final byte[] key;

    public ChatCursorCodec(ObjectMapper mapper, Clock clock, @Value("${auth.jwt.secret}") String secret) {
        this.mapper = mapper;
        this.clock = clock;
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String encode(ChatQueryCursor cursor) {
        try {
            byte[] payload = mapper.writeValueAsBytes(cursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." +
                    Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("chat cursor encoding failed");
        }
    }

    public ChatQueryCursor decode(String token) {
        try {
            if (token == null || token.length() > 4096) {
                throw new ChatQueryException(false);
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) {
                throw new ChatQueryException(false);
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            if (!MessageDigest.isEqual(sign(payload), Base64.getUrlDecoder().decode(parts[1]))) {
                throw new ChatQueryException(false);
            }
            ChatQueryCursor cursor = mapper.readValue(payload, ChatQueryCursor.class);
            if (cursor.userId() <= 0 || cursor.roomId() <= 0 || cursor.membershipId() <= 0 ||
                    cursor.snapshot() < 0 || cursor.position() < 0 || cursor.position() > cursor.snapshot() ||
                    cursor.expiresAt() <= clock.instant().toEpochMilli() ||
                    !("history".equals(cursor.mode()) || "recovery".equals(cursor.mode()))) {
                throw new ChatQueryException(false);
            }
            return cursor;
        } catch (Exception invalid) {
            // Never echo cursor contents, view identifiers or parsing exceptions.
            throw new ChatQueryException(false);
        }
    }

    private byte[] sign(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update("chat-query-cursor:v1\u0000".getBytes(StandardCharsets.UTF_8));
        return mac.doFinal(payload);
    }
}
