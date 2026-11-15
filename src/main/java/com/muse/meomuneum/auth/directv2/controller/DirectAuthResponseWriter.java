package com.muse.meomuneum.auth.directv2.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.global.response.ApiResponse;

@Component
public class DirectAuthResponseWriter {
    public ResponseEntity<ApiResponse<?>> result(int status, String message, Object data, DirectAuthFlowState flow,
            String operationHeader, String operationId, List<String> cookies) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.set("X-Auth-Login-Protocol", "direct-v2");
        if (flow != null) {
            headers.set("X-Auth-Flow-Id", flow.flowId());
            headers.set("X-Auth-Flow-Epoch", Long.toString(flow.epoch()));
            headers.set("X-Auth-Flow-Revision", Long.toString(flow.revision()));
        }
        if (operationHeader != null) {
            headers.set(operationHeader, operationId);
        }
        if (status == 429) {
            headers.set("Retry-After", "60");
        }
        if (status == 503) {
            headers.set("Retry-After", "1");
        }
        if (status >= 200 && status < 300) {
            cookies.forEach(cookie -> headers.add(HttpHeaders.SET_COOKIE, cookie));
        }
        return new ResponseEntity<>(ApiResponse.of(message, data), headers, HttpStatusCode.valueOf(status));
    }

    public ResponseEntity<ApiResponse<?>> error(int status, String code) {
        return result(status, code, null, null, null, null, List.of());
    }
}
