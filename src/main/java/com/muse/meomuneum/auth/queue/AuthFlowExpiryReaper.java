package com.muse.meomuneum.auth.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.application.AuthFlowService;

@Component
public class AuthFlowExpiryReaper {
    private static final Logger log = LoggerFactory.getLogger(AuthFlowExpiryReaper.class);
    private static final int BATCH_SIZE = 100;

    private final AuthFlowService flowService;

    public AuthFlowExpiryReaper(AuthFlowService flowService) {
        this.flowService = flowService;
    }

    @Scheduled(fixedDelayString = "${auth.flow.expiry-reaper-interval:60s}")
    public void reapExpiredAuthority() {
        try {
            flowService.reapExpiredSessions(BATCH_SIZE);
            flowService.reapExpiredAnonymousFlows(BATCH_SIZE);
            flowService.reapRetainedFlowData(BATCH_SIZE);
        } catch (RuntimeException failure) {
            log.warn("Auth flow expiry cleanup deferred type={}", failure.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${auth.flow.queue-create-reaper-interval:5s}")
    public void reapStaleQueueCreateReservations() {
        try {
            flowService.reapStaleQueueCreateReservations(BATCH_SIZE);
        } catch (RuntimeException failure) {
            log.warn("Auth queue create cleanup deferred type={}", failure.getClass().getSimpleName());
        }
    }
}
