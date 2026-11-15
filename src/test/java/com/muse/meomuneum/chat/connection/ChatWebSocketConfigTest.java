package com.muse.meomuneum.chat.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatWebSocketConfigTest {
    @Test
    void outboundHandlersFillOneBoundedDbBatchAndLegacyKeepsDefault() {
        assertEquals(32, threads(true));
        assertEquals(0, threads(false));
    }

    private Object threads(boolean relay) {
        var config = new ChatWebSocketConfig(null, null, null, null, null, "http://localhost", null, null,
                relay);
        return ReflectionTestUtils.getField(config, "outboundThreads");
    }
}
