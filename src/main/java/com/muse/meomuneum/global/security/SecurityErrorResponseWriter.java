package com.muse.meomuneum.global.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.exception.ErrorCode;
import com.muse.meomuneum.global.response.ApiResponse;

@Component
public class SecurityErrorResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorResponseWriter.class);

    private final ObjectMapper objectMapper;

    public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode) throws IOException {
        log.warn(
                "event=security_request_rejected domainCode={} httpStatus={} method={} path={} requestId={}",
                errorCode.code(),
                errorCode.status().value(),
                request.getMethod(),
                request.getRequestURI(),
                MDC.get("requestId")
        );
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(errorCode.message()));
    }
}
