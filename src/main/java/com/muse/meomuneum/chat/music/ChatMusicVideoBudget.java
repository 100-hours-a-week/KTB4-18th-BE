package com.muse.meomuneum.chat.music;

import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared fixed-window reservations commit before an external call, including failed calls. */
public final class ChatMusicVideoBudget {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ChatMusicVideoBudget(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void reserve(String scope, int seconds, int maximum) {
        if (maximum < 1 || seconds < 1) {
            throw new IllegalArgumentException("Invalid video request budget");
        }
        boolean accepted = Boolean.TRUE.equals(transaction.execute(status -> {
            long now = jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,'1970-01-01',UTC_TIMESTAMP())", Long.class);
            long window = now / seconds * seconds;
            jdbc.update("INSERT IGNORE INTO music_video_request_limits(scope_key,window_start) VALUES (?,?)", scope,
                    window);
            boolean allowed = jdbc.update("""
                    UPDATE music_video_request_limits SET request_count=request_count+1
                    WHERE scope_key=? AND window_start=? AND request_count<?
                    """, scope, window, maximum) == 1;
            jdbc.update("DELETE FROM music_video_request_limits WHERE window_start<? LIMIT 100", now - 172800);
            return allowed;
        }));
        if (!accepted) {
            throw new ChatMusicException(429, "VIDEO_REQUEST_RATE_LIMITED", seconds);
        }
    }
}
