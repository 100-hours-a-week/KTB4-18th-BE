package com.muse.meomuneum.auth.directv2.controller;

import java.util.List;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.auth.directv2.application.DirectAuthFlowService;
import com.muse.meomuneum.auth.directv2.application.DirectAuthLoginService;
import com.muse.meomuneum.auth.directv2.application.DirectAuthLogoutService;
import com.muse.meomuneum.auth.directv2.application.DirectAuthRecoveryService;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.global.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class DirectAuthOperationController {
    private final DirectAuthRequestParser parser;
    private final DirectAuthResponseWriter writer;
    private final DirectAuthFlowService flows;
    private final DirectAuthLoginService login;
    private final DirectAuthLogoutService logout;
    private final DirectAuthRecoveryService recovery;
    private final com.muse.meomuneum.auth.directv2.application.DirectAuthRefreshService refresh;
    private final com.muse.meomuneum.auth.directv2.application.DirectAuthRebootstrapService rebootstrap;

    public DirectAuthOperationController(DirectAuthRequestParser parser, DirectAuthResponseWriter writer,
            DirectAuthFlowService flows, DirectAuthLoginService login, DirectAuthLogoutService logout,
            DirectAuthRecoveryService recovery,
            com.muse.meomuneum.auth.directv2.application.DirectAuthRefreshService refresh,
            com.muse.meomuneum.auth.directv2.application.DirectAuthRebootstrapService rebootstrap) {
        this.parser = parser;
        this.writer = writer;
        this.flows = flows;
        this.login = login;
        this.logout = logout;
        this.recovery = recovery;
        this.refresh = refresh;
        this.rebootstrap = rebootstrap;
    }

    private ResponseEntity<ApiResponse<?>> execute(Supplier<ResponseEntity<ApiResponse<?>>> action) {
        try {
            return action.get();
        } catch (DirectAuthFailure failure) {
            return writer.error(failure.status(), failure.getMessage());
        } catch (com.muse.meomuneum.auth.exception.AuthenticationFailedException invalid) {
            return writer.error(401, "AUTH_SESSION_INVALID");
        } catch (DataAccessException | AuthStateUnavailableException failure) {
            return writer.error(503, "AUTH_STATE_UNAVAILABLE");
        }
    }

    public ResponseEntity<ApiResponse<?>> login(LoginRequest credentials, HttpServletRequest request) {
        return execute(() -> {
            var tuple = parser.tuple(request, "X-Login-Operation-Id");
            var result = login.login(request, credentials, tuple, parser.expiry(request));
            if ("login operation".equals(result.message())) {
                var status = (java.util.Map<?, ?>) result.data();
                String state = (String) status.get("status");
                String code = switch (state) {
                    case "INVALID_CREDENTIALS" -> "INVALID_CREDENTIALS";
                    case "NOT_ADMITTED" -> (String) status.get("reasonCode");
                    case "SUCCEEDED" -> "LOGIN_RESULT_REQUIRES_RECOVERY";
                    case "REAUTH_REQUIRED" -> "REAUTH_REQUIRED";
                    default -> "AUTH_LOGIN_SUPERSEDED";
                };
                return writer.error("INVALID_CREDENTIALS".equals(code)
                        ? 401
                        : "AUTH_BCRYPT_CAPACITY".equals(code) ? 503 : 409, code);
            }
            return result(result, tuple.operationId());
        });
    }

    public ResponseEntity<ApiResponse<?>> refresh(HttpServletRequest request) {
        return execute(() -> {
            var tuple = parser.tuple(request, "X-Refresh-Operation-Id");
            var result = refresh.refresh(request, tuple, parser.expiry(request));
            return writer.result(result.status(), result.message(), result.data(), result.flow(),
                    "X-Refresh-Operation-Id", tuple.operationId(), result.cookies());
        });
    }

    public ResponseEntity<ApiResponse<?>> logout(HttpServletRequest request) {
        return execute(() -> {
            var tuple = parser.tuple(request, "Idempotency-Key");
            var result = logout.logout(request, tuple);
            return writer.result(200, "logout committed", result.data(), result.flow(),
                    "Idempotency-Key", tuple.operationId(), result.cookies());
        });
    }

    public ResponseEntity<ApiResponse<?>> current(HttpServletRequest request) {
        return execute(() -> {
            var result = flows.current(request);
            return writer.result(200, "auth flow", result.data(), result.flow(), null, null, List.of());
        });
    }

    @PostMapping("/flows")
    public ResponseEntity<ApiResponse<?>> bootstrap(HttpServletRequest request) {
        return execute(() -> {
            String id = parser.id(request, "X-Auth-Flow-Operation-Id");
            var result = flows.bootstrap(request, id);
            return writer.result(200, "auth flow", result.data(), result.flow(),
                    "X-Auth-Flow-Operation-Id", id, result.cookies());
        });
    }

    @GetMapping("/login/operations/{operationId}")
    public ResponseEntity<ApiResponse<?>> loginOperation(HttpServletRequest request, @PathVariable String operationId) {
        return execute(() -> result(recovery.status(request, parser.pathTuple(request, operationId)), operationId));
    }

    @PostMapping("/login/operations/{operationId}/session-recovery")
    public ResponseEntity<ApiResponse<?>> recoverLogin(HttpServletRequest request, @PathVariable String operationId) {
        return execute(
                () -> result(recovery.recoverLogin(request, parser.pathTuple(request, operationId)), operationId));
    }

    @GetMapping("/flow/current-operation")
    public ResponseEntity<ApiResponse<?>> currentOperation(HttpServletRequest request) {
        return execute(() -> {
            var result = recovery.current(request);
            return writer.result(200, "current auth operation", result.data(), result.flow(), null, null, List.of());
        });
    }

    @PostMapping("/flow/rebootstrap")
    public ResponseEntity<ApiResponse<?>> rebootstrap(HttpServletRequest request) {
        return execute(() -> {
            var tuple = parser.tuple(request, "X-Auth-Rebootstrap-Operation-Id");
            var result = rebootstrap.rebootstrap(request, tuple);
            return writer.result(result.status(), result.message(), result.data(), result.flow(),
                    "X-Auth-Rebootstrap-Operation-Id", tuple.operationId(), result.cookies());
        });
    }

    private ResponseEntity<ApiResponse<?>> result(DirectAuthLoginService.Result result, String id) {
        return writer.result(result.status(), result.message(), result.data(), result.flow(),
                "X-Login-Operation-Id", id, result.cookies());
    }
}
