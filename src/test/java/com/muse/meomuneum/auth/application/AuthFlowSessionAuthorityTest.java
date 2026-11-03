package com.muse.meomuneum.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.global.security.AccessSessionAuthority;

class AuthFlowSessionAuthorityTest {
    @Test
    void coreAdapterKeepsDatabaseDecisionsAndFailsClosedOnMissingResult() {
        var flows = mock(AuthFlowService.class, CALLS_REAL_METHODS);
        doReturn(AuthFlowService.SessionDecision.ACTIVE).when(flows).checkSession(1L, "sid");
        assertEquals(AccessSessionAuthority.Decision.ACTIVE, flows.verifySession(1L, "sid"));
        doReturn(AuthFlowService.SessionDecision.REJECTED).when(flows).checkSession(1L, "sid");
        assertEquals(AccessSessionAuthority.Decision.REJECTED, flows.verifySession(1L, "sid"));
        doReturn(AuthFlowService.SessionDecision.UNAVAILABLE).when(flows).checkSession(1L, "sid");
        assertEquals(AccessSessionAuthority.Decision.UNAVAILABLE, flows.verifySession(1L, "sid"));
        doReturn(null).when(flows).checkSession(1L, "sid");
        assertEquals(AccessSessionAuthority.Decision.UNAVAILABLE, flows.verifySession(1L, "sid"));
    }
}
