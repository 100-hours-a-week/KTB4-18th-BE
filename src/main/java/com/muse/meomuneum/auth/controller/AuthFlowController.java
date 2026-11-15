package com.muse.meomuneum.auth.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.application.AuthFlowService.BootstrapResult;
import com.muse.meomuneum.auth.application.AuthFlowService.FlowSnapshot;
import com.muse.meomuneum.auth.application.AuthFlowService.LogoutReceipt;
import com.muse.meomuneum.auth.application.AuthFlowService.RefreshReceipt;
import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.global.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth/flow")
public class AuthFlowController {
    private final AuthFlowService flowService;
    private final RefreshReplayCipher replayCipher;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.muse.meomuneum.auth.directv2.controller.DirectAuthOperationController directController;

    public AuthFlowController(AuthFlowService flowService, RefreshReplayCipher replayCipher) {
        this.flowService = flowService;
        this.replayCipher = replayCipher;
    }

    @GetMapping
    public ResponseEntity<? extends ApiResponse<?>> current(HttpServletRequest request) {
        if ("direct-v2".equals(request.getHeader("X-Auth-Login-Protocol"))) {
            return directController.current(request);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        BootstrapResult result = flowService.bootstrap(request, headers);
        FlowSnapshot flow = result.flow();
        if (!flow.authenticated()) {
            flowService.expireRefreshCookie(headers);
        } else {
            flowService.repairCurrentRefreshCookie(request, result, replayCipher, headers);
        }
        LogoutReceipt logoutReceipt = flowService.lastLogoutReceipt(flow.flowId());
        RefreshReceipt refreshReceipt = flowService.lastRefreshReceipt(flow.flowId());
        long refreshGeneration = flowService.refreshGeneration(flow);
        FlowResponse body = new FlowResponse(flow.flowId(), flow.flowEpoch(), flow.revision(), flow.lifecycle(),
                flow.authenticated(), refreshGeneration, null, List.of(), logoutReceipt, refreshReceipt,
                null, flow.rebootstrapReason());
        return ResponseEntity.ok().headers(headers).cacheControl(CacheControl.noStore())
                .body(ApiResponse.of("auth flow", body));
    }

    public record FlowResponse(String flowId, long flowEpoch, long revision, String lifecycle,
            boolean authenticated, long refreshGeneration, Object currentTicket,
            List<Object> pendingOperations, LogoutReceipt lastLogoutReceipt,
            RefreshReceipt lastRefreshReceipt, Object lastQueueCancelReceipt,
            String rebootstrapReason) {
    }
}
