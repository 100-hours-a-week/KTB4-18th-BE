package com.muse.meomuneum.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.muse.meomuneum.recommendation.filter.ImageRequestSizeFilter;

class ImageRequestSizeFilterTests {
    private final ImageRequestSizeFilter filter = new ImageRequestSizeFilter();

    @Test
    void rejectsKnownImageRequestAboveSixMibBeforeDispatcher() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/recommendations/images");
        request.setContent(new byte[(int) ImageRequestSizeFilter.MAX_REQUEST_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("image request too large");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void leavesUnknownLengthEnforcementToIngress() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/recommendations/images");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void allowsDeclaredRequestAtSixMibBoundary() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/recommendations/images");
        request.setContent(new byte[(int) ImageRequestSizeFilter.MAX_REQUEST_BYTES]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
