package com.muse.meomuneum.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.auth.application.RefreshReplayCipher.ReplayContext;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthNonceAllocator;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

class RefreshReplayCipherVersionedTest {
    @Test
    void legacyAndVersionedRoundTripWithDistinctAuthenticatedContexts() {
        var properties = mock(AuthStateProperties.class);
        when(properties.replayEncryptionKey()).thenReturn(Base64.getEncoder().encodeToString(new byte[32]));
        var allocator = mock(DirectAuthNonceAllocator.class);
        when(allocator.reserve(anyString())).thenReturn(new byte[12]);
        var first = new RefreshReplayCipher(properties, allocator);
        var second = new RefreshReplayCipher(properties, allocator);
        var context = context("direct-operation-flow-cookie", "1");
        String v1 = first.encrypt("payload");
        assertEquals("payload", second.decrypt(v1));
        String v2 = first.encryptV2("payload", context);
        assertEquals("payload", second.decryptV2(v2, context));
        assertThrows(AuthStateUnavailableException.class,
                () -> second.decryptV2(v2, context("operation-response-body", "1")));
        assertThrows(AuthStateUnavailableException.class,
                () -> second.decryptV2(v2, context("direct-operation-flow-cookie", "2")));
        assertThrows(AuthStateUnavailableException.class, () -> second.decryptV2(v1, context));
        assertThrows(AuthStateUnavailableException.class, () -> second.decrypt(v2));
        assertThrows(AuthStateUnavailableException.class, () -> second.decryptV2(v2 + "=", context));
        byte[] bytes = Base64.getUrlDecoder().decode(v2.substring("rrc:v2:".length()));
        bytes[bytes.length - 1] ^= 1;
        String tampered = "rrc:v2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        assertThrows(AuthStateUnavailableException.class, () -> second.decryptV2(tampered, context));
        var wrongProperties = mock(AuthStateProperties.class);
        byte[] otherKey = new byte[32];
        java.util.Arrays.fill(otherKey, (byte) 1);
        when(wrongProperties.replayEncryptionKey()).thenReturn(Base64.getEncoder().encodeToString(otherKey));
        var wrongKey = new RefreshReplayCipher(wrongProperties, allocator);
        assertThrows(AuthStateUnavailableException.class, () -> wrongKey.decryptV2(v2, context));
    }

    private ReplayContext context(String type, String revision) {
        return new ReplayContext(type, "auth_direct_operation_receipts", "REFRESH",
                "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "",
                "1", "1", revision, "2", "2", "", "", "", "");
    }
}
