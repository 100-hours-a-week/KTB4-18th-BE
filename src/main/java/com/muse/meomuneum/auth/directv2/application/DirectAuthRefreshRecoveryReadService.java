package com.muse.meomuneum.auth.directv2.application;

import java.time.ZoneOffset;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthRecoveryRepository;

@Service
public class DirectAuthRefreshRecoveryReadService {
    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthRecoveryLifecycle lifecycle;
    private final DirectAuthRecoveryRepository recoveries;

    public DirectAuthRefreshRecoveryReadService(DirectAuthTransactionRunner transactions,
            DirectAuthRecoveryLifecycle lifecycle, DirectAuthRecoveryRepository recoveries) {
        this.transactions = transactions;
        this.lifecycle = lifecycle;
        this.recoveries = recoveries;
    }

    public DirectAuthRefreshService.Result read(HttpServletRequest request, Tuple tuple, String exactId) {
        return transactions.run(() -> {
            var source = lifecycle.lock(request, tuple);
            boolean current = exactId == null;
            String id = current ? source.flow().recoveryOperationId() : exactId;
            var row = id == null ? null : recoveries.find(tuple.flowId(), id, true).orElse(null);
            var session = lifecycle.session(source);
            if (row == null) {
                return result(source, tuple, id, current ? "NONE" : "NOT_RECEIVED", null, current);
            }
            lifecycle.identity(row, source, id);
            if ("TOMBSTONE".equals(row.status())) {
                if (!current) {
                    throw DirectAuthFailure.conflict("AUTH_OPERATION_RECEIPT_EXPIRED");
                }
                return result(source, tuple, id, "EXPIRED", null, true);
            }
            lifecycle.live(row);
            if ("SUCCEEDED".equals(row.status())) {
                if (!lifecycle.canonical(source, session, row)) {
                    if (!current) {
                        throw DirectAuthFailure.conflict("AUTH_RECOVERY_RESULT_STALE");
                    }
                    return result(source, tuple, id, "RESULT_STALE", row, true);
                }
                return result(source, tuple, id, "SUCCEEDED", row, current);
            }
            if (!"IN_PROGRESS".equals(row.status()) || !lifecycle.claimMatches(source, session, row)) {
                if (!current) {
                    throw DirectAuthFailure.conflict("AUTH_RECOVERY_RESULT_STALE");
                }
                return result(source, tuple, id, "RESULT_STALE", row, true);
            }
            if (row.lease() == null) {
                throw DirectAuthFailure.unavailable();
            }
            if (!source.now().isBefore(row.lease())) {
                var data = lifecycle.data(tuple, id, "LEASE_EXPIRED", row);
                data.put("leaseExpiresAt", row.lease().toInstant(ZoneOffset.UTC).toString());
                return new DirectAuthRefreshService.Result(409, "AUTH_RECOVERY_LEASE_EXPIRED", data, source.flow(),
                        List.of());
            }
            return result(source, tuple, id, "IN_PROGRESS", row, current);
        });
    }

    private DirectAuthRefreshService.Result result(DirectAuthRecoveryLifecycle.Source source, Tuple tuple, String id,
            String status, DirectAuthRecoveryRepository.Recovery row, boolean current) {
        var data = lifecycle.data(tuple, id, status, row);
        if (current && "RESULT_STALE".equals(status)) {
            data.put("resultEpoch", null);
            data.put("resultRevision", null);
        }
        return new DirectAuthRefreshService.Result(200,
                current ? "current refresh recovery" : "refresh recovery status",
                data, source.flow(), List.of());
    }
}
