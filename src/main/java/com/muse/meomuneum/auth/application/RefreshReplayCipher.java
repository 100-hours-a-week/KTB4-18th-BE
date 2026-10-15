package com.muse.meomuneum.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

@Component
public class RefreshReplayCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final AuthStateProperties properties;

    public RefreshReplayCipher(AuthStateProperties properties) {
        this.properties = properties;
    }

    public String encrypt(String value) {
        try {
            byte[] key = key();
            byte[] iv = new byte[IV_BYTES];
            java.security.SecureRandom.getInstanceStrong().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public String decrypt(String value) {
        try {
            byte[] payload = Base64.getDecoder().decode(value);
            if (payload.length <= IV_BYTES) {
                throw new AuthStateUnavailableException();
            }
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, IV_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    private byte[] key() {
        String encoded = properties.replayEncryptionKey();
        try {
            byte[] key = Base64.getDecoder().decode(encoded == null ? "" : encoded);
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new IllegalArgumentException("invalid shared AES key configuration");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }
}
