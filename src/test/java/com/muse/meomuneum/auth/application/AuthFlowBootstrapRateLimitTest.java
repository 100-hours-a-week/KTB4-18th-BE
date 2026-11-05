package com.muse.meomuneum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthFlowException;
import com.muse.meomuneum.global.config.JwtProperties;

class AuthFlowBootstrapRateLimitTest {

    @Test
    void bootstrapRateLimitCommitsItsCounterBeforeReturningTooManyRequests() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(2);
        AuthFlowService service = new AuthFlowService(jdbc, transactions, properties(), false, "Lax", true, 1,
                new JwtProperties("issuer", "audience", "test-jwt-secret-with-at-least-32-bytes", 3600, 1209600));
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> service.bootstrap(request, new HttpHeaders()))
                .isInstanceOf(AuthFlowException.class)
                .satisfies(failure -> assertThat(((AuthFlowException) failure).code())
                        .isEqualTo("AUTH_FLOW_BOOTSTRAP_RATE_LIMITED"));

        verify(transactions).commit(any());
        verify(jdbc).update(anyString(), any(Object[].class));
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.contains("INSERT INTO auth_browser_flows"),
                any(Object[].class));
    }

    private AuthStateProperties properties() {
        return new AuthStateProperties("test-only-auth-flow-hmac-key-at-least-32-bytes",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "MEO_QUEUE_V2", 1209600, 7776000,
                900, 60, 900, 20, 10, 64, 16, 4, 120, 1800, 5, 20, 4000, 86400, 1, 100000, 300, 45);
    }
}
