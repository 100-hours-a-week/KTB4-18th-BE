package com.muse.meomuneum.auth.directv2.security;

import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.directv2.application.DirectAuthTransactionRunner;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;

@Component
public class DirectAuthProtectedSessionGuard {
    private final JdbcTemplate jdbc;
    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthProjectionRepository projections;

    public DirectAuthProtectedSessionGuard(JdbcTemplate jdbc, DirectAuthTransactionRunner transactions,
            DirectAuthProjectionRepository projections) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.projections = projections;
    }

    public <T> T check(String sid, Supplier<T> canonical) {
        var modes = jdbc.queryForList("SELECT refresh_cookie_mode FROM auth_browser_sessions WHERE sid = ?",
                String.class, sid);
        if (modes.size() > 1 || modes.size() == 1 && !"LEGACY".equals(modes.getFirst())
                && !"GENERATION_SCOPED".equals(modes.getFirst())) {
            throw DirectAuthFailure.unavailable();
        }
        return transactions.run(() -> {
            if (modes.size() == 1 && "GENERATION_SCOPED".equals(modes.getFirst())) {
                projections.lockControl();
            }
            var current = jdbc.queryForList("SELECT refresh_cookie_mode FROM auth_browser_sessions WHERE sid = ?",
                    String.class, sid);
            if (!current.equals(modes)) {
                throw DirectAuthFailure.unavailable();
            }
            return canonical.get();
        });
    }
}
