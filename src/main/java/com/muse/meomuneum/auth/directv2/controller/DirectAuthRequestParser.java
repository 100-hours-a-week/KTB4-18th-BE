package com.muse.meomuneum.auth.directv2.controller;

import java.util.Collections;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;

@Component
public class DirectAuthRequestParser {
    public record Tuple(String flowId, long epoch, String operationId) {
    }

    public boolean direct(HttpServletRequest request) {
        return "direct-v2".equals(request.getHeader("X-Auth-Login-Protocol"));
    }

    public String single(HttpServletRequest request, String header, String error) {
        var values = Collections.list(request.getHeaders(header));
        if (values.size() != 1 || values.getFirst().isEmpty() || values.getFirst().length() > 128
                || values.getFirst().contains(",")) {
            throw new DirectAuthFailure(400, error);
        }
        return values.getFirst();
    }

    public String uuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException failure) {
            throw new DirectAuthFailure(400, "INVALID_OPERATION_ID");
        }
    }

    public String id(HttpServletRequest request, String name) {
        return uuid(single(request, name, "INVALID_OPERATION_ID"));
    }

    public long positive(String value, String code, long maximum) {
        try {
            if (value.length() > 19 || !value.matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException();
            }
            long parsed = Long.parseLong(value);
            if (parsed > maximum) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (RuntimeException failure) {
            throw new DirectAuthFailure(400, code);
        }
    }

    public Tuple tuple(HttpServletRequest request, String operationHeader) {
        return new Tuple(id(request, "X-Auth-Flow-Id"),
                positive(single(request, "X-Auth-Flow-Epoch", "INVALID_AUTH_FLOW_TUPLE"),
                        "INVALID_AUTH_FLOW_TUPLE", Long.MAX_VALUE),
                id(request, operationHeader));
    }

    public Tuple pathTuple(HttpServletRequest request, String operationId) {
        String pathId = uuid(operationId);
        String header = request.getHeader("X-Login-Operation-Id");
        if (header != null && !pathId.equals(id(request, "X-Login-Operation-Id"))) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
        }
        return new Tuple(id(request, "X-Auth-Flow-Id"),
                positive(single(request, "X-Auth-Flow-Epoch", "INVALID_AUTH_FLOW_TUPLE"),
                        "INVALID_AUTH_FLOW_TUPLE", Long.MAX_VALUE),
                pathId);
    }

    public Tuple sourceTuple(HttpServletRequest request, String sourceId) {
        String id = uuid(sourceId);
        if (request.getHeader("X-Refresh-Operation-Id") != null
                && !id.equals(id(request, "X-Refresh-Operation-Id"))) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
        }
        return new Tuple(id(request, "X-Auth-Flow-Id"),
                positive(single(request, "X-Auth-Flow-Epoch", "INVALID_AUTH_FLOW_TUPLE"),
                        "INVALID_AUTH_FLOW_TUPLE", Long.MAX_VALUE),
                id);
    }

    public long recoveryExpiry(HttpServletRequest request) {
        return positive(single(request, "X-Auth-Recovery-Client-Expires-At", "INVALID_CLIENT_EXPIRY"),
                "INVALID_CLIENT_EXPIRY", 9_007_199_254_740_991L);
    }

    public long expiry(HttpServletRequest request) {
        return positive(single(request, "X-Auth-Client-Expires-At", "INVALID_CLIENT_EXPIRY"),
                "INVALID_CLIENT_EXPIRY", 9_007_199_254_740_991L);
    }
}
