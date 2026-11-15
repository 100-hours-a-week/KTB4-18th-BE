package com.muse.meomuneum.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthNonceAllocator;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

class RefreshReplayCipherVersionedTest {
    @Test
    void legacyCipherRemainsReadableAndVersionedEncryptionRequiresAllocatedTicket() {
        var properties = mock(AuthStateProperties.class);
        when(properties.replayEncryptionKey()).thenReturn(Base64.getEncoder().encodeToString(new byte[32]));
        var allocator = mock(DirectAuthNonceAllocator.class);
        var first = new RefreshReplayCipher(properties, allocator);
        var second = new RefreshReplayCipher(properties, allocator);
        String v1 = first.encrypt("payload");
        assertEquals("payload", second.decrypt(v1));
        assertThrows(AuthStateUnavailableException.class,
                () -> first.encryptV2("payload", context()));
    }

    private RefreshReplayCipher.ReplayContext context() {
        return new RefreshReplayCipher.ReplayContext("direct-operation-flow-cookie",
                "auth_direct_operation_receipts", "REFRESH", "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002", "", "1", "1", "1", "2", "2", "", "", "", "");
    }
}
