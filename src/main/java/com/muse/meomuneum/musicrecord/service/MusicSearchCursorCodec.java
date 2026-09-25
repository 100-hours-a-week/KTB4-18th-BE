package com.muse.meomuneum.musicrecord.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

@Component
public class MusicSearchCursorCodec {
    private final byte[] secret;

    public MusicSearchCursorCodec(@Value("${auth.jwt.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String encode(String query, String phase, long lastDbId, long highWatermark,
            int externalIndex, String externalHash) {
        String payload = "1|" + hash(query) + "|ITUNES|" + phase + "|" + lastDbId + "|"
                + highWatermark + "|" + externalIndex + "|" + externalHash;
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
            if (fields.length != 8 || !"1".equals(fields[0]) || !hash(query).equals(fields[1])
                    || !"ITUNES".equals(fields[2]) || !("DB".equals(fields[3])
                    || "ITUNES".equals(fields[3]))) {
                throw invalid();
            }
            long lastDbId = Long.parseLong(fields[4]);
            long highWatermark = Long.parseLong(fields[5]);
            int externalIndex = Integer.parseInt(fields[6]);
            if (lastDbId < 0 || highWatermark < 0 || externalIndex < 0 || externalIndex > 200
                    || ("ITUNES".equals(fields[3]) && fields[7].isBlank())) {
                throw invalid();
            }
            return new Cursor(fields[3], lastDbId, highWatermark, externalIndex, fields[7]);
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
            String externalHash) {
    }
}
