package com.muse.meomuneum.musicrecord.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

@Component
public class MusicSearchCursorCodec {
    private static final Duration CURSOR_LIFETIME = Duration.ofMinutes(30);
    private final byte[] secret;
    private final Clock clock;

    public MusicSearchCursorCodec(@Value("${auth.jwt.secret}") String secret) {
        this(secret, Clock.systemUTC());
    }

    @Autowired
    public MusicSearchCursorCodec(@Value("${auth.jwt.secret}") String secret, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public Instant newExpiresAt() {
        return clock.instant().plus(CURSOR_LIFETIME);
    }

    public String encode(String query, String phase, long lastDbId, long highWatermark,
            int externalIndex, String externalHash) {
        return encode(query, phase, lastDbId, highWatermark, externalIndex, externalHash,
                newExpiresAt());
    }

    public String encode(String query, String phase, long lastDbId, long highWatermark,
            int externalIndex, String externalHash, Instant expiresAt) {
        Instant issuedAt = expiresAt.minus(CURSOR_LIFETIME);
        String payload = "2|" + hash(query) + "|ITUNES|" + phase + "|" + lastDbId + "|"
                + highWatermark + "|" + externalIndex + "|" + externalHash + "|"
                + issuedAt.getEpochSecond() + "|" + expiresAt.getEpochSecond();
        return base64(payload.getBytes(StandardCharsets.UTF_8)) + "." + base64(signature(payload));
    }

    public Cursor decode(String value, String query) {
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2) {
                throw invalid();
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(Base64.getUrlDecoder().decode(parts[1]), signature(payload))) {
                throw invalid();
            }
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 10 || !"2".equals(fields[0]) || !hash(query).equals(fields[1])
                    || !"ITUNES".equals(fields[2]) || !("DB".equals(fields[3])
                            || "ITUNES".equals(fields[3]))) {
                throw invalid();
            }
            long lastDbId = Long.parseLong(fields[4]);
            long highWatermark = Long.parseLong(fields[5]);
            int externalIndex = Integer.parseInt(fields[6]);
            Instant issuedAt = Instant.ofEpochSecond(Long.parseLong(fields[8]));
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(fields[9]));
            if (lastDbId < 0 || highWatermark < 0 || externalIndex < 0 || externalIndex > 200
                    || ("ITUNES".equals(fields[3]) && fields[7].isBlank())
                    || ("DB".equals(fields[3]) && (externalIndex != 0 || !fields[7].isBlank()))
                    || !issuedAt.plus(CURSOR_LIFETIME).equals(expiresAt)
                    || issuedAt.isAfter(clock.instant()) || !expiresAt.isAfter(clock.instant())) {
                throw invalid();
            }
            return new Cursor(fields[3], lastDbId, highWatermark, externalIndex, fields[7], expiresAt);
        } catch (MusicRecordException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    public String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("cursor hash unavailable", exception);
        }
    }

    private byte[] signature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("cursor signing unavailable", exception);
        }
    }

    private String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private MusicRecordException invalid() {
        return new MusicRecordException("music_cursor_invalid", HttpStatus.BAD_REQUEST,
                "search query required");
    }

    public record Cursor(String phase, long lastDbId, long highWatermark, int externalIndex,
            String externalHash, Instant expiresAt) {
    }
}
