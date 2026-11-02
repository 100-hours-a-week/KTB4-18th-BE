package com.muse.meomuneum.auth.directv2.application;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.application.RefreshReplayCipher.ReplayContext;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthLoginLifecycleRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRecoveryRepository.Recovery;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRefreshRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRefreshRepository.Receipt;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository.Session;
import com.muse.meomuneum.auth.directv2.security.DirectAuthCookieService;

@Service
public class DirectAuthRecoveryLifecycle {
    public record Source(DirectAuthFlowState flow, Receipt receipt, LocalDateTime now) {
    }

    private final DirectAuthFlowRepository flows;
    private final DirectAuthRefreshRepository receipts;
    private final DirectAuthSessionRepository sessions;
    private final DirectAuthCookieService cookies;
    private final DirectAuthRefreshService refresh;
    private final DirectAuthFlowService flowService;
    private final com.muse.meomuneum.auth.directv2.security.DirectAuthRefreshProofService proofs;
    private final com.muse.meomuneum.auth.directv2.repository.DirectAuthRecoveryRepository recoveries;

    public DirectAuthRecoveryLifecycle(DirectAuthFlowRepository flows, DirectAuthRefreshRepository receipts,
            DirectAuthSessionRepository sessions, DirectAuthCookieService cookies, DirectAuthRefreshService refresh,
            com.muse.meomuneum.auth.directv2.repository.DirectAuthRecoveryRepository recoveries,
            com.muse.meomuneum.auth.directv2.security.DirectAuthRefreshProofService proofs,
            DirectAuthFlowService flowService) {
        this.flows = flows;
        this.receipts = receipts;
        this.sessions = sessions;
        this.cookies = cookies;
        this.refresh = refresh;
        this.recoveries = recoveries;
        this.proofs = proofs;
        this.flowService = flowService;
    }

    public void preflight(HttpServletRequest request, Tuple tuple) {
        var source = source(request, tuple, false);
        var session = sessions.require(source.flow(), flows.now(), false);
        authorizeMutation(request, source, session);
    }

    public Source lock(HttpServletRequest request, Tuple tuple) {
        return source(request, tuple, true);
    }

    private Source source(HttpServletRequest request, Tuple tuple, boolean lock) {
        var resolved = flowService.resolve(request, true);
        if (!resolved.flowId().equals(tuple.flowId())) {
            throw new DirectAuthFailure(404, "AUTH_FLOW_NOT_FOUND");
        }
        var flow = (lock ? flows.lock(tuple.flowId()) : flows.find(tuple.flowId()))
                .orElseThrow(() -> new DirectAuthFailure(404, "REFRESH_OPERATION_NOT_FOUND"));
        var now = flows.now();
        var binding = cookies.proofs(request).stream()
                .filter(proof -> proof.binding() && cookies.valid(flow, proof, now)).toList();
        if (binding.size() != 1) {
            throw new DirectAuthFailure(404, "REFRESH_OPERATION_NOT_FOUND");
        }
        var source = receipts.find(flow.flowId(), tuple.operationId(), lock).orElseThrow(
                () -> new DirectAuthFailure(404, "REFRESH_OPERATION_NOT_FOUND"));
        if (source.epoch() != tuple.epoch()) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
        }
        refresh.requireLive(source);
        if (!"SUCCEEDED".equals(source.status()) || !"REFRESH".equals(flow.currentOperationKind())
                || !source.id().equals(flow.currentOperationId()) || source.resultEpoch() == null
                || source.resultEpoch() != flow.epoch()
                || !java.util.Objects.equals(source.sid(), flow.activeSessionSid())) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
        }
        return new Source(flow, source, now);
    }

    public Session session(Source source) {
        try {
            return sessions.require(source.flow(), flows.now(), true);
        } catch (DirectAuthFailure unavailable) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
        }
    }

    public record Claim(Source source, Session session, Recovery recovery, byte[] owner, boolean replay) {
    }

    public Claim claim(HttpServletRequest request, Tuple tuple, String id, long client, byte[] owner) {
        Source source = lock(request, tuple);
        var flow = source.flow();
        var row = recoveries.find(flow.flowId(), id, true).orElse(null);
        var prior = flow.recoveryOperationId() != null && !id.equals(flow.recoveryOperationId())
                ? recoveries.find(flow.flowId(), flow.recoveryOperationId(), true).orElse(null)
                : null;
        Session session = session(source);
        authorizeMutation(request, source, session);
        if (row != null) {
            identity(row, source, id);
            live(row);
            if (row.client() != client) {
                throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
            }
            if ("SUCCEEDED".equals(row.status())) {
                if (!canonical(source, session, row)) {
                    throw DirectAuthFailure.conflict("AUTH_RECOVERY_RESULT_STALE");
                }
                return new Claim(source, session, row, null, true);
            }
            if (!"IN_PROGRESS".equals(row.status()) || !claimMatches(source, session, row)) {
                throw DirectAuthFailure.conflict("AUTH_RECOVERY_RESULT_STALE");
            }
            if (row.lease() == null) {
                throw DirectAuthFailure.unavailable();
            }
            if (source.now().isBefore(row.lease())) {
                return new Claim(source, session, row, null, false);
            }
            recoveries.reclaim(flow, session, row, owner);
        } else {
            if (prior != null && "IN_PROGRESS".equals(prior.status()) && claimMatches(source, session, prior)) {
                throw DirectAuthFailure.conflict("AUTH_RECOVERY_IN_PROGRESS");
            }
            if (prior != null && canonical(source, session, prior)) {
                throw DirectAuthFailure.conflict("AUTH_RECOVERY_ALREADY_COMPLETED");
            }
            if (prior != null && "IN_PROGRESS".equals(prior.status())) {
                recoveries.supersede(prior);
            }
            var expiry = source.now().plusSeconds(900);
            if (source.receipt().expiry().isBefore(expiry)) {
                expiry = source.receipt().expiry();
            }
            recoveries.insert(flow, session, source.receipt(), id, owner, client, effective(client, source, expiry),
                    expiry);
        }
        row = recoveries.find(flow.flowId(), id, true).orElseThrow(DirectAuthFailure::unavailable);
        recoveries.pointer(flow, session, row);
        return new Claim(source, session, row, owner, false);
    }

    public void authorizeMutation(HttpServletRequest request, Source source, Session session) {
        try {
            cookies.requireUnique(request, source.flow(), flows.now(), flows::findKey);
        } catch (DirectAuthFailure invalid) {
            throw new DirectAuthFailure(404, "REFRESH_OPERATION_NOT_FOUND");
        }
        if (proofs.original(request, source.flow(), source.receipt())) {
            return;
        }
        try {
            proofs.current(request, source.flow(), session, flows.now());
        } catch (DirectAuthFailure | com.muse.meomuneum.auth.exception.AuthenticationFailedException invalid) {
            throw new DirectAuthFailure(404, "REFRESH_OPERATION_NOT_FOUND");
        }
    }

    public void identity(Recovery row, Source source, String id) {
        if (!row.id().equals(id) || !row.sourceId().equals(source.receipt().id())
                || row.epoch() != source.receipt().epoch() || !"TOMBSTONE".equals(row.status())
                        && (row.bindingGeneration() == null
                                || row.bindingGeneration() != source.flow().bindingCookieGeneration())) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_ID_REUSED");
        }
    }

    public void live(Recovery row) {
        var now = flows.now();
        if ("TOMBSTONE".equals(row.status()) || row.client() == null || row.effective() == null
                || row.client() <= 0 || row.client() > 9_007_199_254_740_991L || row.effective() <= 0
                || row.effective() > row.client() || !now.isBefore(row.expiry())
                || DirectAuthLoginLifecycleRepository.micros(now) >= row.effective() * 1000) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_RECEIPT_EXPIRED");
        }
    }

    public boolean canonical(Source source, Session session, Recovery row) {
        var flow = source.flow();
        return "SUCCEEDED".equals(row.status()) && row.resultEpoch() != null && row.resultRevision() != null
                && row.flowGeneration() != null && row.refreshGeneration() != null
                && row.resultEpoch() == flow.epoch() && row.resultRevision() == flow.revision()
                && row.flowGeneration() == flow.flowCookieGeneration()
                && row.refreshGeneration() == session.refreshGeneration()
                && session.sid().equals(row.sid()) && row.id().equals(flow.recoveryOperationId())
                && row.sourceId().equals(flow.recoverySourceOperationId()) && "SUCCEEDED".equals(flow.recoveryStatus());
    }

    public boolean claimMatches(Source source, Session session, Recovery row) {
        var flow = source.flow();
        return row.claimFlowGeneration() != null && row.claimRefreshGeneration() != null
                && row.claimFlowGeneration() == flow.flowCookieGeneration()
                && row.claimRefreshGeneration() == session.refreshGeneration()
                && row.id().equals(flow.recoveryOperationId())
                && row.sourceId().equals(flow.recoverySourceOperationId())
                && "IN_PROGRESS".equals(flow.recoveryStatus())
                && java.util.Objects.equals(flow.recoveryClaimFlowCookieGeneration(), row.claimFlowGeneration())
                && java.util.Objects.equals(flow.recoveryClaimRefreshGeneration(), row.claimRefreshGeneration())
                && java.util.Objects.equals(flow.recoveryLeaseExpiresAt(), row.lease());
    }

    public void owner(Source source, Session session, Recovery row, byte[] owner) {
        live(row);
        if (!"IN_PROGRESS".equals(row.status()) || row.owner() == null || owner == null
                || !MessageDigest.isEqual(owner, row.owner()) || row.lease() == null
                || !flows.now().isBefore(row.lease())
                || !claimMatches(source, session, row)) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_SUPERSEDED");
        }
    }

    public long effective(long client, Source source, LocalDateTime expiry) {
        long cutoff;
        try {
            cutoff = Math.min(Math.multiplyExact(client, 1000L), Math.min(
                    DirectAuthLoginLifecycleRepository.micros(source.now()) + 900_000_000L,
                    Math.min(source.receipt().effective() * 1000, DirectAuthLoginLifecycleRepository.micros(expiry))))
                    / 1000;
        } catch (ArithmeticException invalid) {
            throw new DirectAuthFailure(400, "INVALID_CLIENT_EXPIRY");
        }
        if (cutoff * 1000 <= DirectAuthLoginLifecycleRepository.micros(source.now())) {
            throw DirectAuthFailure.conflict("AUTH_OPERATION_RECEIPT_EXPIRED");
        }
        return cutoff;
    }

    public Map<String, Object> data(Tuple tuple, String id, String status, Recovery row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flowId", tuple.flowId());
        data.put("operationId", tuple.operationId());
        data.put("recoveryOperationId", id);
        data.put("startingEpoch", Long.toString(tuple.epoch()));
        data.put("status", status);
        data.put("leaseExpiresAt", row != null && "IN_PROGRESS".equals(status)
                ? row.lease().toInstant(ZoneOffset.UTC).toString()
                : null);
        data.put("resultEpoch", row != null && "SUCCEEDED".equals(status) ? row.resultEpoch().toString() : null);
        data.put("resultRevision", row != null && "SUCCEEDED".equals(status) ? row.resultRevision().toString() : null);
        data.put("clientExpiresAt", row == null || row.client() == null ? null : row.client().toString());
        data.put("effectiveExpiresAt", row == null || row.effective() == null ? null : row.effective().toString());
        return data;
    }

    public ReplayContext context(Recovery row, String payload, long epoch, long revision, long flowGeneration,
            long refreshGeneration) {
        return new ReplayContext(payload, "auth_refresh_recovery_operations", "REFRESH_RECOVERY", row.flowId(),
                row.sourceId(), row.id(), Long.toString(row.epoch()), Long.toString(epoch), Long.toString(revision),
                Long.toString(flowGeneration), Long.toString(refreshGeneration), row.bindingGeneration().toString(), "",
                "", "");
    }
}
