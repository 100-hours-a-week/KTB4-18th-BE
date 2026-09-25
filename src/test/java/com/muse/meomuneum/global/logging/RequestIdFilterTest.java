package com.muse.meomuneum.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    @Test
    void addsRequestIdToResponseAndClearsMdcAfterRequest() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInFilterChain = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> requestIdInFilterChain
                .set(MDC.get(RequestIdFilter.REQUEST_ID_ATTRIBUTE)));

        String requestId = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(requestId).isNotBlank();
        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo(requestId);
        assertThat(requestIdInFilterChain).hasValue(requestId);
        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).isNull();
    }
}
