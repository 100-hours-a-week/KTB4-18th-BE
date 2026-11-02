package com.muse.meomuneum.auth.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;

/** One bounded current-state read; delayed Hazelcast cleanup must not restore a DB-revoked browser flow. */
public final class CoreBrowserSessionLookup {
    private final JdbcTemplate jdbc;

    public CoreBrowserSessionLookup(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(1);
    }

    public Map<SessionPair, SessionDecision> findActiveSessions(List<SessionPair> pairs) {
        if (pairs.isEmpty() || pairs.size() > 100) {
            throw new IllegalArgumentException("bounded session validation required");
        }
        var arguments = new ArrayList<Object>();
        for (SessionPair pair : pairs) {
            arguments.add(pair.userId());
            arguments.add(pair.sid());
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(pairs.size(), "(?,?)"));
        Map<SessionPair, SessionDecision> active = new HashMap<>();
        jdbc.query("""
                SELECT s.user_id,s.sid,s.refresh_generation,
                    LEAST(TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(6),s.idle_expires_at),
                          TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(6),s.absolute_expires_at)) DIV 1000 AS remaining_ms
                FROM auth_browser_sessions s JOIN auth_browser_flows f ON f.flow_id=s.flow_id
                WHERE s.status='ACTIVE' AND f.lifecycle='AUTHENTICATED'
                    AND f.active_session_sid=s.sid AND f.flow_epoch=s.flow_epoch
                    AND s.idle_expires_at>UTC_TIMESTAMP(6) AND s.absolute_expires_at>UTC_TIMESTAMP(6)
                    AND (s.user_id,s.sid) IN (
                """ + placeholders + ")", (RowCallbackHandler) row -> {
            SessionPair pair = new SessionPair(row.getLong("user_id"), row.getString("sid"));
            active.put(pair, new SessionDecision(pair, true, row.getLong("refresh_generation"),
                    row.getLong("remaining_ms")));
        }, arguments.toArray());
        return Map.copyOf(active);
    }
}
