package com.muse.meomuneum.support;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Real canonical legacy-session rows for HTTP tests that issue signed bearer tokens directly. */
public final class AuthSessionTestData {
    private AuthSessionTestData() {
    }

    public static void ensureActive(JdbcTemplate jdbc, Long userId, String sid) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM auth_browser_sessions WHERE sid=?",
                Integer.class, sid);
        if (count != null && count > 0) {
            return;
        }
        String flow = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO auth_browser_flows
                    (flow_id,flow_cookie_hash,flow_epoch,revision,lifecycle,active_session_sid,
                     cookie_expires_at,created_at,updated_at)
                VALUES (?,?,1,1,'AUTHENTICATED',?,DATE_ADD(NOW(6),INTERVAL 1 DAY),NOW(6),NOW(6))
                """, flow, flow.replace("-", "").repeat(2), sid);
        jdbc.update("""
                INSERT INTO auth_browser_sessions
                    (sid,flow_id,flow_epoch,user_id,status,idle_expires_at,absolute_expires_at,created_at,updated_at)
                VALUES (?,?,1,?,'ACTIVE',DATE_ADD(NOW(6),INTERVAL 1 DAY),
                        DATE_ADD(NOW(6),INTERVAL 1 DAY),NOW(6),NOW(6))
                """, sid, flow, userId);
    }
}
