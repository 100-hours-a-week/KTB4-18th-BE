package com.muse.meomuneum.chat.music;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.participation.ChatParticipationService;

/** Separate bounded scheduler: slow room locks must not delay participation heartbeats. */
public final class ChatMusicPlaybackWorker implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ChatMusicPlaybackWorker.class);
    private final JdbcTemplate jdbc;
    private final ChatParticipationService participation;
    private final ChatMusicPlaybackService playback;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "chat-music-playback");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private long afterRoom;
    private final Set<Long> refreshing = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor metadata = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20), task -> {
                Thread thread = new Thread(task, "chat-music-video-metadata");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public ChatMusicPlaybackWorker(JdbcTemplate jdbc, ChatParticipationService participation,
            ChatMusicPlaybackService playback) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(1);
        this.participation = participation;
        this.playback = playback;
    }

    public void start() {
        participation.setMusicPresenceHook(playback::presenceChanged);
        scheduler.scheduleWithFixedDelay(this::poll, 250, 250, TimeUnit.MILLISECONDS);
    }

    public void poll() {
        if (!participation.available() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            var rooms = jdbc.queryForList("""
                    SELECT p.chat_room_id FROM playback_states p WHERE p.chat_room_id>?
                      AND (p.state IN ('PLAYING','PAUSED') OR
                        (p.state='IDLE' AND EXISTS(SELECT 1 FROM music_queue_items q
                          WHERE q.room_id=p.chat_room_id AND q.status='WAITING')))
                    ORDER BY p.chat_room_id LIMIT 20
                    """, Long.class, afterRoom);
            if (rooms.isEmpty()) {
                afterRoom = 0;
            }
            for (long room : rooms) {
                afterRoom = room;
                if (!participation.available()) {
                    break;
                }
                try {
                    prepare(room);
                    participation.systemMusicTransaction(room, tx -> {
                        playback.reconcile(tx);
                        return null;
                    });
                } catch (RuntimeException unavailable) {
                    // Do not log user, room, command, tokens or source data. Next scan retries under fresh fencing.
                    LOG.warn("event=chat_music_room_transition_failed failure={}",
                            unavailable.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException unavailable) {
            LOG.warn("event=chat_music_scan_failed failure={}", unavailable.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        metadata.shutdownNow();
    }

    private void prepare(long room) {
        if (!refreshing.add(room)) {
            return;
        }
        try {
            metadata.execute(() -> {
                try {
                    if (participation.available()) {
                        playback.prepareRoom(room);
                    }
                } catch (RuntimeException unavailable) {
                    LOG.warn("event=chat_music_metadata_refresh_failed failure={}",
                            unavailable.getClass().getSimpleName());
                } finally {
                    refreshing.remove(room);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            refreshing.remove(room);
        }
    }
}
