package com.muse.meomuneum.auth.application;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthNonceAllocator;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

@Component
public class RefreshReplayCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String V2_PREFIX = "rrc:v2:";
    private final AuthStateProperties properties;
    private final DirectAuthNonceAllocator allocator;

    public RefreshReplayCipher(AuthStateProperties properties) {
        this(properties, null);
    }

    @Autowired
    public RefreshReplayCipher(AuthStateProperties properties, DirectAuthNonceAllocator allocator) {
        this.properties = properties;
        this.allocator = allocator;
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

    public String encryptV2(String value, ReplayContext context) {
        try {
            byte[] aad = context.aad();
            byte[] derived = derivedKey();
            byte[] iv = allocator().reserve(keyId(derived));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return V2_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(1 + IV_BYTES + ciphertext.length).put((byte) 2).put(iv).put(ciphertext)
                            .array());
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException failure) {
            throw new AuthStateUnavailableException(failure);
        }
    }

    public String decryptV2(String value, ReplayContext context) {
        try {
            if (value == null || !value.startsWith(V2_PREFIX)) {
                throw new AuthStateUnavailableException();
            }
            byte[] aad = context.aad();
            byte[] derived = derivedKey();
            allocator().verifyKey(keyId(derived));
            String encoded = value.substring(V2_PREFIX.length());
            byte[] payload = Base64.getUrlDecoder().decode(encoded);
            if (payload.length < 1 + IV_BYTES + 16 || payload[0] != 2
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(payload).equals(encoded)) {
                throw new AuthStateUnavailableException();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, "AES"),
                    new GCMParameterSpec(TAG_BITS, payload, 1, IV_BYTES));
            cipher.updateAAD(aad);
            return new String(cipher.doFinal(payload, 1 + IV_BYTES, payload.length - 1 - IV_BYTES),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException failure) {
            throw new AuthStateUnavailableException(failure);
        }
    }

    private DirectAuthNonceAllocator allocator() {
        if (allocator == null) {
            throw new AuthStateUnavailableException();
        }
        return allocator;
    }

    private byte[] derivedKey() throws GeneralSecurityException {
        byte[] input = key();
        if (input.length < 32) {
            throw new AuthStateUnavailableException();
        }
        return KDF.getInstance("HKDF-SHA256").deriveData(HKDFParameterSpec.ofExtract().addIKM(input)
                .addSalt("meomuneum/auth-replay/v2/hkdf-salt".getBytes(StandardCharsets.UTF_8))
                .thenExpand("meomuneum/auth-replay/v2/aes-256-gcm".getBytes(StandardCharsets.UTF_8), 32));
    }

    private String keyId(byte[] derived) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("meomuneum/auth-replay/v2/key-id".getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest(derived));
    }

    public record ReplayContext(String payloadType, String tablePurpose, String operationKind, String flowId,
            String sourceOperationId, String auxiliaryOperationId, String startingEpoch, String resultEpoch,
            String resultRevision, String resultFlowCookieGeneration, String resultRefreshGeneration,
            String bindingGeneration, String resultBindingGeneration, String requestFingerprint,
            String initialProofFingerprint) {
        public byte[] aad() {
            List<String> fields = List.of(payloadType, tablePurpose, operationKind, flowId, sourceOperationId,
                    auxiliaryOperationId, startingEpoch, resultEpoch, resultRevision, resultFlowCookieGeneration,
                    resultRefreshGeneration, bindingGeneration, resultBindingGeneration, requestFingerprint,
                    initialProofFingerprint);
            boolean validPurpose = switch (payloadType) {
                case "operation-response-body", "refresh-cookie-token", "direct-operation-flow-cookie" ->
                    "auth_direct_operation_receipts".equals(tablePurpose)
                            && ("REFRESH".equals(operationKind) || "LOGOUT".equals(operationKind))
                            && (!"refresh-cookie-token".equals(payloadType) || "REFRESH".equals(operationKind));
                case "rebootstrap-flow-cookie" -> "auth_flow_rebootstrap_operations".equals(tablePurpose)
                        && "REBOOTSTRAP".equals(operationKind);
                case "refresh-recovery-response-body", "refresh-recovery-refresh-cookie",
                        "refresh-recovery-flow-cookie" ->
                    "auth_refresh_recovery_operations".equals(tablePurpose)
                            && "REFRESH_RECOVERY".equals(operationKind);
                case "bootstrap-exact-response" -> "auth_flow_bootstrap_operations".equals(tablePurpose)
                        && "BOOTSTRAP".equals(operationKind);
                default -> false;
            };
            if (!validPurpose || !canonicalUuid(flowId) || !canonicalUuid(sourceOperationId)
                    || (!auxiliaryOperationId.isEmpty() && !canonicalUuid(auxiliaryOperationId))
                    || ("REFRESH_RECOVERY".equals(operationKind) != !auxiliaryOperationId.isEmpty())) {
                throw new IllegalArgumentException("invalid replay context");
            }
            boolean bootstrap = "BOOTSTRAP".equals(operationKind);
            boolean recovery = "REFRESH_RECOVERY".equals(operationKind);
            boolean rebootstrap = "REBOOTSTRAP".equals(operationKind);
            boolean refresh = "REFRESH".equals(operationKind);
            if ((!bootstrap && startingEpoch.isEmpty()) || resultEpoch.isEmpty() || resultRevision.isEmpty()
                    || resultFlowCookieGeneration.isEmpty()
                    || ((refresh || recovery) && resultRefreshGeneration.isEmpty())
                    || ((recovery || rebootstrap) && bindingGeneration.isEmpty())
                    || (bootstrap && (resultBindingGeneration.isEmpty() || requestFingerprint.isEmpty()))
                    || (!bootstrap && (!resultBindingGeneration.isEmpty() || !requestFingerprint.isEmpty()
                            || !initialProofFingerprint.isEmpty()))
                    || (!(recovery || rebootstrap) && !bindingGeneration.isEmpty())
                    || (!(refresh || recovery) && !resultRefreshGeneration.isEmpty())) {
                throw new IllegalArgumentException("incomplete replay context");
            }
            for (String number : fields.subList(6, 13)) {
                if (!number.isEmpty() && !number.matches("0|[1-9][0-9]*")) {
                    throw new IllegalArgumentException("invalid replay context");
                }
            }
            for (String fingerprint : fields.subList(13, 15)) {
                if (!fingerprint.isEmpty() && !fingerprint.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("invalid replay context");
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                output.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
                output.writeBytes(bytes);
            }
            return output.toByteArray();
        }

        private static boolean canonicalUuid(String value) {
            try {
                return UUID.fromString(value).toString().equals(value);
            } catch (IllegalArgumentException failure) {
                return false;
            }
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
