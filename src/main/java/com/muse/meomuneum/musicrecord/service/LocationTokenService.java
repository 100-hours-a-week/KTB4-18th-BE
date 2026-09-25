package com.muse.meomuneum.musicrecord.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository.Location;

@Service
public class LocationTokenService {
    private final byte[] secret;
    public LocationTokenService(@Value("${auth.jwt.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }
    public String create(long userId, Location location, Instant expiresAt) {
        try {
            String payload = userId + ":" + location.dotId() + ":" + location.sigunguId() + ":"
                    + location.sidoId() + ":" + expiresAt.getEpochSecond();
            return encode(payload) + "." + encode(signature(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("location token signing failed", exception);
        }
    }

    public LocationClaims parse(String token, long userId) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) {
                throw new MusicRecordException("location_token_malformed");
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(Base64.getUrlDecoder().decode(parts[1]), signature(payload))) {
                throw new MusicRecordException("location_token_signature_invalid");
            }
            String[] values = payload.split(":");
            if (values.length != 5 || Long.parseLong(values[0]) != userId
                    || Instant.now().getEpochSecond() >= Long.parseLong(values[4])) {
                throw new MusicRecordException("location_token_invalid_or_expired");
            }
            return new LocationClaims(Long.parseLong(values[1]), Long.parseLong(values[2]),
                    Long.parseLong(values[3]), Instant.ofEpochSecond(Long.parseLong(values[4])));
        } catch (MusicRecordException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MusicRecordException("location_token_malformed");
        }
    }
    private byte[] signature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    }

    private String encode(String value) {
        return encode(value.getBytes(StandardCharsets.UTF_8));
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record LocationClaims(long dotId, long sigunguId, long sidoId, Instant expiresAt) {
    }
}
