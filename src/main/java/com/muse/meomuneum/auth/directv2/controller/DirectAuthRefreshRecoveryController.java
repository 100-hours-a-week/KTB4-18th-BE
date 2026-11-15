package com.muse.meomuneum.auth.directv2.controller;

import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.auth.directv2.application.DirectAuthRefreshRecoveryReadService;
import com.muse.meomuneum.auth.directv2.application.DirectAuthRefreshRecoveryService;
import com.muse.meomuneum.auth.directv2.application.DirectAuthRefreshService;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.global.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth/token/refresh/operations/{operationId}/session-recovery")
public class DirectAuthRefreshRecoveryController {
    private final DirectAuthRequestParser parser;
    private final DirectAuthResponseWriter writer;
    private final DirectAuthRefreshRecoveryService recovery;
    private final DirectAuthRefreshRecoveryReadService reads;

    public DirectAuthRefreshRecoveryController(DirectAuthRequestParser parser, DirectAuthResponseWriter writer,
            DirectAuthRefreshRecoveryService recovery, DirectAuthRefreshRecoveryReadService reads) {
        this.parser = parser;
        this.writer = writer;
        this.recovery = recovery;
        this.reads = reads;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<?>> recover(HttpServletRequest request, @PathVariable String operationId) {
        return execute(() -> recovery.recover(request, parser.sourceTuple(request, operationId),
                parser.id(request, "X-Auth-Refresh-Recovery-Operation-Id"), parser.recoveryExpiry(request)));
    }

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<?>> current(HttpServletRequest request, @PathVariable String operationId) {
        return execute(() -> reads.read(request, parser.sourceTuple(request, operationId), null));
    }

    @GetMapping("/{recoveryOperationId}")
    public ResponseEntity<ApiResponse<?>> exact(HttpServletRequest request, @PathVariable String operationId,
            @PathVariable String recoveryOperationId) {
        return execute(
                () -> reads.read(request, parser.sourceTuple(request, operationId), parser.uuid(recoveryOperationId)));
    }

    private ResponseEntity<ApiResponse<?>> execute(Supplier<DirectAuthRefreshService.Result> action) {
        try {
            var result = action.get();
            String id = result.data() != null && result.data().get("recoveryOperationId") instanceof String value
                    ? value
                    : null;
            return writer.result(result.status(), result.message(), result.data(), result.flow(),
                    id == null ? null : "X-Auth-Refresh-Recovery-Operation-Id", id, result.cookies());
        } catch (DirectAuthFailure failure) {
            return writer.error(failure.status(), failure.getMessage());
        } catch (com.muse.meomuneum.auth.exception.AuthenticationFailedException inactive) {
            return writer.error(409, "AUTH_OPERATION_SUPERSEDED");
        } catch (DataAccessException | AuthStateUnavailableException unavailable) {
            return writer.error(503, "AUTH_STATE_UNAVAILABLE");
        }
    }
}
