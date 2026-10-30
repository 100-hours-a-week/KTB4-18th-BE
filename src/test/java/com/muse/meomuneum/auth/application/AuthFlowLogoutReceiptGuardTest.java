package com.muse.meomuneum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthFlowException;
import com.muse.meomuneum.global.config.JwtProperties;

class AuthFlowLogoutReceiptGuardTest {
    private static final String FLOW_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String OPERATION_ID = "123e4567-e89b-42d3-a456-426614174001";
    private static final String FLOW_COOKIE = "opaque-flow-cookie";
    private static final String HMAC_KEY = "test-only-auth-flow-hmac-key-at-least-32-bytes";

    @Test
    void staleLogoutReceiptDoesNotExpireCookiesAfterQueueReservationAdvancesRevision() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        org.mockito.Mockito.when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        AuthStateProperties properties = new AuthStateProperties(HMAC_KEY,
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "MEO_QUEUE_V2", 1209600, 7776000,
                900, 60, 900, 20, 10, 64, 16, 4, 120, 1800, 5, 20, 4000, 86400, 1, 100000, 300, 45);
        AuthFlowService service = new AuthFlowService(jdbc, transactionManager, properties, false, "Lax", true, 120,
                new JwtProperties("issuer", "audience", "test-jwt-secret-with-at-least-32-bytes", 3600, 1209600));
        Instant now = Instant.now();
        String requestHash = hmac(service, "logout-request:" + FLOW_ID + ":8:" + OPERATION_ID);

        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            if (sql.contains("FROM auth_browser_flows")) {
                org.mockito.Mockito.when(resultSet.getString("flow_id")).thenReturn(FLOW_ID);
                org.mockito.Mockito.when(resultSet.getLong("flow_epoch")).thenReturn(9L);
                // Logout committed at revision 5; a queue reservation advanced the same epoch to revision 6.
                org.mockito.Mockito.when(resultSet.getLong("revision")).thenReturn(6L);
                org.mockito.Mockito.when(resultSet.getString("lifecycle")).thenReturn("ANONYMOUS");
                org.mockito.Mockito.when(resultSet.getTimestamp("cookie_expires_at"))
                        .thenReturn(Timestamp.from(now.plusSeconds(600)));
            } else if (sql.contains("FROM auth_flow_operations")) {
                org.mockito.Mockito.when(resultSet.getString("request_hash")).thenReturn(requestHash);
                org.mockito.Mockito.when(resultSet.getLong("result_epoch")).thenReturn(9L);
                org.mockito.Mockito.when(resultSet.getLong("result_revision")).thenReturn(5L);
                org.mockito.Mockito.when(resultSet.getTimestamp("committed_at"))
                        .thenReturn(Timestamp.from(now.minusSeconds(10)));
            } else {
                throw new AssertionError("Unexpected query: " + sql);
            }
            return mapper.mapRow(resultSet, 0);
        }).when(jdbc).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(AuthFlowService.FLOW_COOKIE_NAME, FLOW_COOKIE));
        request.addHeader("X-Auth-Flow-Id", FLOW_ID);
        request.addHeader("X-Auth-Flow-Epoch", "8");
        HttpHeaders responseHeaders = new HttpHeaders();

        assertThatThrownBy(() -> service.commitLogout(request, responseHeaders, OPERATION_ID))
                .isInstanceOf(AuthFlowException.class)
                .satisfies(error -> assertThat(((AuthFlowException) error).code())
                        .isEqualTo("AUTH_FLOW_EPOCH_STALE"));

        assertThat(responseHeaders.isEmpty()).isTrue();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private String hmac(AuthFlowService service, String value) throws Exception {
        Method method = AuthFlowService.class.getDeclaredMethod("hmac", String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, value);
    }
}
