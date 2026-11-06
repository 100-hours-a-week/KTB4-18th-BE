package com.muse.meomuneum.support;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.directv2.security.DirectAuthProtectedSessionGuard;
import com.muse.meomuneum.global.security.AccessSessionAuthority;

/** Explicit authenticated-session fixture for MVC tests; database authority is tested separately. */
@TestConfiguration(proxyBeanMethods = false)
public class BearerSecurityTestSupport {
    @Bean
    @ConditionalOnMissingBean(AuthFlowService.class)
    AuthFlowService mvcSessionAuthority() {
        var authority = mock(AuthFlowService.class);
        when(authority.verifySession(anyLong(), anyString())).thenReturn(AccessSessionAuthority.Decision.ACTIVE);
        return authority;
    }

    @Bean
    @ConditionalOnMissingBean(DirectAuthProtectedSessionGuard.class)
    DirectAuthProtectedSessionGuard mvcSessionGuard() {
        var guard = mock(DirectAuthProtectedSessionGuard.class);
        when(guard.verifySession(anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            Supplier<?> canonical = invocation.getArgument(1);
            return canonical.get();
        });
        return guard;
    }
}
