package com.muse.meomuneum.auth.directv2.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser;

class DirectAuthRequestParserTest {
    @Test
    void acceptsSafeIntegerBoundaryAndRejectsEveryAmbiguousPhysicalExpiryHeader() {
        var parser = new DirectAuthRequestParser();
        var maximum = new MockHttpServletRequest();
        maximum.addHeader("X-Auth-Client-Expires-At", "9007199254740991");
        assertThat(parser.expiry(maximum)).isEqualTo(9_007_199_254_740_991L);
        assertThat(Math.multiplyExact(parser.expiry(maximum), 1000L)).isEqualTo(9_007_199_254_740_991_000L);
        for (String invalid : new String[]{"", "0", "01", "+1", "-1", "1.0", "1e3", " 1", "1 ",
                "9007199254740992", "9223372036854775807", "1,2"}) {
            var request = new MockHttpServletRequest();
            request.addHeader("X-Auth-Client-Expires-At", invalid);
            assertThatThrownBy(() -> parser.expiry(request)).hasMessage("INVALID_CLIENT_EXPIRY");
        }
        var duplicate = new MockHttpServletRequest();
        duplicate.addHeader("X-Auth-Client-Expires-At", "1");
        duplicate.addHeader("X-Auth-Client-Expires-At", "1");
        assertThatThrownBy(() -> parser.expiry(duplicate)).hasMessage("INVALID_CLIENT_EXPIRY");
        assertThatThrownBy(() -> parser.expiry(new MockHttpServletRequest())).hasMessage("INVALID_CLIENT_EXPIRY");
    }
}
