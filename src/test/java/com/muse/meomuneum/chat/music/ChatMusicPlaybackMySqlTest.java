package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.chat.connection.ChatSessionAccessPolicy;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.participation.ChatParticipationProjection;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.participation.ChatParticipationUnavailableException;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.view.ChatViewStorageService;
import com.muse.meomuneum.music.catalog.RequestableMusicRepository;

@Testcontainers
class ChatMusicPlaybackMySqlTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0").withCommand("--skip-log-bin");
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource source;
    private ChatParticipationService taskA;
    private ChatParticipationService taskB;
    private ChatSessionAccessPolicy access;
    private ChatMusicQueueService apiA;
    private ChatMusicQueueService apiB;
    private ChatMusicQueueService.Caller caller;
    private ChatMusicQueueService.Caller other;
    private ChatMusicKeys keys;
    private String environment;
    private long music;
    private long region;
    private ChatMusicPlaybackService playback;

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("20261109103259").load().migrate();
        String stable = "SELECT chat_room_id,state,version,next_sequence_no,position_seconds,state_changed_at,"
                + "created_at,updated_at FROM playback_states ORDER BY chat_room_id";
        var before = jdbc.queryForList(stable);
        var latest = Flyway.configure().dataSource(source).target("20261109132852").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList(stable)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playback_states WHERE source_video_id IS NOT NULL "
                + "OR duration_millis IS NOT NULL OR due_at IS NOT NULL", Long.class)).isZero();
        // Keep the one-migration assertion fixed; subsequent domain tests use the current schema.
        Flyway.configure().dataSource(source).load().migrate();
    }

    @BeforeEach
    void setup() {
        environment = "music-" + UUID.randomUUID().toString().substring(0, 8);
        access = mock(ChatSessionAccessPolicy.class);
        taskA = task();
        taskB = task();
        long room = jdbc.queryForObject("SELECT MIN(id) FROM chat_rooms WHERE id NOT IN "
                + "(SELECT room_id FROM chat_room_members)", Long.class);
        region = jdbc.queryForObject("SELECT region_id FROM chat_rooms WHERE id=?", Long.class, room);
        caller = participant(room);
        other = participant(room);
        keys = new ChatMusicKeys("fixture:" + Base64.getEncoder().encodeToString(new byte[32]));
        apiA = api(taskA, 100, 0);
        apiB = api(taskB, 100, 0);
        playback = new ChatMusicPlaybackService(jdbc,
                new ChatMusicStateRepository(jdbc, new DataSourceTransactionManager(source)),
                new ChatMusicCommandStore(jdbc, keys, 0));
        apiA.setPlaybackService(playback);
        apiB.setPlaybackService(playback);
        taskA.setMusicPresenceHook(playback::presenceChanged);
        taskB.setMusicPresenceHook(playback::presenceChanged);
        String external = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO music(provider,external_music_id,title,artist_name,youtube_video_id) "
                + "VALUES ('ITUNES',?,'Synthetic song','Synthetic artist','abcdefghijk')", external);
        music = jdbc.queryForObject("SELECT id FROM music WHERE external_music_id=?", Long.class, external);
        jdbc.update("""
                INSERT INTO music_playback_sources(music_id,youtube_video_id,channel_id,channel_kind,review_reference,
                  reviewed_by,status,duration_millis,playback_country,verified_at,valid_until,version)
                VALUES (?,'abcdefghijk','synthetic','ARTIST','synthetic','test','VERIFIED',120000,'KR',
                  TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),1)
                """, music);
        // Recommendation provenance is private to the requesting account.
        recommendation(caller.userId(), music);
        recommendation(other.userId(), music);
    }

    private void recommendation(long user, long song) {
        String key = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO recommendation_sessions(user_id,region_id,trigger_type,input_type,"
                        + "conversation_key,status) "
                        + "VALUES (?,?,'CHATBOT','TEXT',?,'COMPLETED')",
                user, region, key);
        long session = jdbc.queryForObject("SELECT id FROM recommendation_sessions WHERE conversation_key=?",
                Long.class, key);
        jdbc.update("INSERT INTO recommendation_items(recommendation_session_id,music_id,rank_no) VALUES (?,?,1)",
                session, song);
    }

    private ChatParticipationService task() {
        var result = new ChatParticipationService(jdbc, new DataSourceTransactionManager(source),
                mock(ChatViewStorageService.class), access, mock(ChatParticipationProjection.class), event -> {
                },
                new ChatSocketSessions(), environment);
        result.start();
        return result;
    }

    private ChatMusicQueueService api(ChatParticipationService task, int cap, int interval) {
        return new ChatMusicQueueService(jdbc, task, new ChatMusicStateRepository(jdbc,
                new DataSourceTransactionManager(source)), new RequestableMusicRepository(jdbc), keys, cap, interval);
    }

    private ChatMusicQueueService.Caller participant(long room) {
        String unique = UUID.randomUUID().toString();
        String sid = UUID.randomUUID().toString();
        String view = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(email,password_hash,nickname,role) VALUES (?,'synthetic',?,'USER')",
                unique.substring(0, 16) + "@x.test", unique.substring(0, 8));
        long user = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class,
                unique.substring(0, 16) + "@x.test");
        jdbc.update("INSERT INTO chat_room_members(user_id,room_id,auth_session_id) VALUES (?,?,?)", user, room, sid);
        long member = jdbc.queryForObject("SELECT id FROM chat_room_members WHERE auth_session_id=?", Long.class, sid);
        jdbc.update("""
                INSERT INTO chat_view_sessions(id,user_id,room_id,membership_id,created_at,updated_at)
                VALUES (?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, view, user, room, member);
        jdbc.update("""
                INSERT INTO chat_view_runtime(view_id,environment,epoch,user_id,room_id,membership_id,state,
                  owner_task_id,owner_generation,socket_token)
                VALUES (?,?,1,?,?,?,'CONNECTED',?,1,?)
                """, view, environment, user, room, member, taskB.taskId(), UUID.randomUUID().toString());
        return new ChatMusicQueueService.Caller(user, sid, room, member, view);
    }

    @Test
    void firstAddStartsAndRetryDoesNotRestartOrEmitTwice() {
        String id = UUID.randomUUID().toString();
        var first = apiA.add(caller, id, music);
        assertThat(first.body()).contains("PLAYING");
        assertThat(apiB.add(caller, id, music)).isEqualTo(first);
        assertThat(snapshot().get("state")).isEqualTo("PLAYING");
        assertThat(snapshot().get("version")).isEqualTo(1L);
        assertThat(count("chat_music_outbox")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=? AND status='PLAYING'",
                Long.class, caller.roomId())).isEqualTo(1);
    }

    @Test
    void anyParticipantCanPauseResumeAndSkipWithFrozenPositionAndPinnedSource() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long first = item();
        jdbc.update("UPDATE playback_states SET state_changed_at=TIMESTAMPADD(SECOND,-7,UTC_TIMESTAMP(6)) "
                + "WHERE chat_room_id=?", caller.roomId());
        String pauseId = UUID.randomUUID().toString();
        var paused = playback.control(taskB, other, pauseId, "PAUSE", first, 1);
        var snapshot = snapshot();
        assertThat(snapshot.get("state")).isEqualTo("PAUSED");
        var frozen = (java.math.BigDecimal) snapshot.get("position_seconds");
        assertThat(frozen).isBetween(new java.math.BigDecimal("7"), new java.math.BigDecimal("9"));
        assertThat(snapshot().get("estimated_position_seconds")).isEqualTo(frozen);
        assertThat(playback.control(taskA, other, pauseId, "PAUSE", first, 1)).isEqualTo(paused);
        apiA.add(caller, UUID.randomUUID().toString(), music);
        assertThat(snapshot().get("state")).isEqualTo("PAUSED");
        assertThat(snapshot().get("position_seconds")).isEqualTo(frozen);
        playback.control(taskA, caller, UUID.randomUUID().toString(), "RESUME", first, 3);
        assertThat(snapshot().get("state")).isEqualTo("PLAYING");
        assertThat(snapshot().get("position_seconds")).isEqualTo(frozen);
        playback.control(taskB, other, UUID.randomUUID().toString(), "SKIP", first, 4);
        assertThat(item()).isNotEqualTo(first);
        assertThat(snapshot().get("state")).isEqualTo("PLAYING");
        assertThat(status(first)).isEqualTo("SKIPPED");
        assertThat(snapshot().get("position_seconds")).isEqualTo(new java.math.BigDecimal("0.000"));
        assertThat(snapshot().get("version")).isEqualTo(5L);
    }

    @Test
    void serverDeadlineAdvancesOnceAcrossTwoTasksAndFinalTrackBecomesIdle() throws Exception {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        apiB.add(other, UUID.randomUUID().toString(), music);
        long old = item();
        due();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> {
                gate.await();
                tick(taskA);
                return true;
            });
            var b = pool.submit(() -> {
                gate.await();
                tick(taskB);
                return true;
            });
            gate.countDown();
            assertThat(a.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(status(old)).isEqualTo("PLAYED");
        assertThat(snapshot().get("version")).isEqualTo(3L);
        long next = item();
        assertThat(next).isNotEqualTo(old);
        due();
        tick(taskA);
        assertThat(status(next)).isEqualTo("PLAYED");
        assertThat(snapshot().get("state")).isEqualTo("IDLE");
        assertThat(snapshot().get("current")).isNull();
        assertThat(snapshot().get("due_at")).isNull();
        assertThat(snapshot().get("version")).isEqualTo(4L);
        tick(taskB);
        assertThat(snapshot().get("version")).isEqualTo(4L);
    }

    @Test
    void staleOrCompetingCommandsCannotSkipNewCurrentTrack() throws Exception {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        apiA.add(caller, UUID.randomUUID().toString(), music);
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long old = item();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> competingSkip(gate, taskA, caller, old));
            var b = pool.submit(() -> competingSkip(gate, taskB, other, old));
            gate.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(snapshot().get("version")).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=? AND status='SKIPPED'",
                Long.class, caller.roomId())).isEqualTo(1);
        error(() -> playback.control(taskA, caller, UUID.randomUUID().toString(), "SKIP", old, 4), "VERSION_CONFLICT");
    }

    private int competingSkip(CountDownLatch gate, ChatParticipationService task,
            ChatMusicQueueService.Caller c, long old) throws Exception {
        gate.await();
        try {
            return playback.control(task, c, UUID.randomUUID().toString(), "SKIP", old, 3).status();
        } catch (ChatMusicException conflict) {
            return conflict.status();
        }
    }

    @Test
    void expiredQueuedSourcesAreCanceledWithoutRecheckingTheRequestersProvenance() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long current = item();
        jdbc.update("UPDATE music_playback_sources SET status='UNAVAILABLE' WHERE music_id=?", music);
        // Current playback retains its original video and duration despite a later catalog replacement.
        jdbc.update("UPDATE music SET youtube_video_id='klmnopqrstu' WHERE id=?", music);
        assertThat(snapshot().get("source_video_id")).isEqualTo("abcdefghijk");
        assertThat(snapshot().get("duration_millis")).isEqualTo(120000L);
        due();
        tick(taskA);
        assertThat(status(current)).isEqualTo("PLAYED");
        assertThat(snapshot().get("state")).isEqualTo("IDLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=? AND status='CANCELED'",
                Long.class, caller.roomId())).isEqualTo(1);
    }

    @Test
    void newPlayStartsLegacyWaitingQueueAndUnavailableResumeDoesNotChangeState() {
        var legacy = api(taskA, 100, 0);
        legacy.add(caller, UUID.randomUUID().toString(), music);
        playback.control(taskA, caller, UUID.randomUUID().toString(), "PLAY", null, 1);
        long current = item();
        playback.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", current, 2);
        jdbc.update("UPDATE music_playback_sources SET status='UNAVAILABLE' WHERE music_id=?", music);
        error(() -> playback.control(taskA, caller, UUID.randomUUID().toString(), "RESUME", current, 3),
                "MUSIC_UNAVAILABLE");
        assertThat(snapshot().get("state")).isEqualTo("PAUSED");
        assertThat(snapshot().get("version")).isEqualTo(3L);
    }

    @Test
    void pauseTimerRaceNeverPausesOrSkipsTheFollowingSong() throws Exception {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long current = item();
        due();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                try {
                    playback.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", current, 2);
                    return "unexpected";
                } catch (ChatMusicException rejection) {
                    return rejection.code();
                }
            });
            var b = pool.submit(() -> {
                tick(taskB);
                return true;
            });
            assertThat(a.get(5, TimeUnit.SECONDS)).isIn("VERSION_CONFLICT", "TRACK_FINISHED");
            assertThat(b.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(snapshot().get("version")).isEqualTo(3L);
        assertThat(snapshot().get("state")).isEqualTo("PLAYING");
        assertThat(status(current)).isEqualTo("PLAYED");
    }

    @Test
    void finalExplicitLeaveCancelsCurrentAndWaitingWhileOtherTabsAndGraceKeepQueue() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        apiB.add(other, UUID.randomUUID().toString(), music);
        taskA.leave(caller.userId(), caller.sid(), caller.roomId(), caller.membershipId(), caller.viewId());
        assertThat(snapshot().get("state")).isEqualTo("PLAYING");
        jdbc.update("UPDATE chat_view_runtime SET state='GRACE',deadline=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) "
                + "WHERE view_id=?", other.viewId());
        tick(taskA);
        assertThat(snapshot().get("state")).isEqualTo("PLAYING");
        taskB.leave(other.userId(), other.sid(), other.roomId(), other.membershipId(), other.viewId());
        assertThat(snapshot().get("state")).isEqualTo("IDLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=? AND status='CANCELED'",
                Long.class, caller.roomId())).isEqualTo(2);
        assertThat(snapshot().get("version")).isEqualTo(3L);
    }

    @Test
    void outboxFailureAndCommitRevocationRollBackWholePlaybackTransition() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long current = item();
        jdbc.execute("CREATE TRIGGER synthetic_playback_outbox_failure BEFORE INSERT ON chat_music_outbox "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic playback failure'");
        try {
            assertThatThrownBy(() -> playback.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", current, 1))
                    .isInstanceOf(ChatParticipationUnavailableException.class);
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_playback_outbox_failure");
        }
        assertThat(snapshot().get("state")).isEqualTo("PLAYING");
        assertThat(snapshot().get("version")).isEqualTo(1L);
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new ChatMusicException(401, "SYNTHETIC_REVOKED");
                }
            });
            return null;
        }).when(access).authorize(caller.userId(), caller.sid());
        error(() -> playback.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", current, 1),
                "SYNTHETIC_REVOKED");
        assertThat(snapshot().get("version")).isEqualTo(1L);
        assertThat(count("chat_music_command_receipts")).isEqualTo(1);
        assertThat(count("chat_music_outbox")).isEqualTo(2);
    }

    @Test
    void revokedViewAndStoppedRuntimeDenyUserAndAutomaticCommands() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long current = item();
        jdbc.update("UPDATE chat_view_runtime SET state='LEFT' WHERE view_id=?", caller.viewId());
        assertThatThrownBy(() -> playback.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", current, 1))
                .isInstanceOf(ChatRoomException.class);
        due();
        taskA.setRuntimePermit(() -> false);
        assertThatThrownBy(() -> tick(taskA)).isInstanceOf(ChatParticipationUnavailableException.class);
        assertThat(snapshot().get("version")).isEqualTo(1L);
        assertThat(status(current)).isEqualTo("PLAYING");
    }

    @Test
    void noOpCommandsStoreReceiptWithoutVersionOrEventChangesAndRatePolicyIsShared() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long current = item();
        playback.control(taskA, other, UUID.randomUUID().toString(), "PLAY", current, 1);
        assertThat(snapshot().get("version")).isEqualTo(1L);
        assertThat(count("chat_music_outbox")).isEqualTo(2);
        var limited = new ChatMusicPlaybackService(jdbc,
                new ChatMusicStateRepository(jdbc, new DataSourceTransactionManager(source)),
                new ChatMusicCommandStore(jdbc, keys, 60_000));
        error(() -> limited.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", current, 1),
                "COMMAND_RATE_LIMITED");
    }

    @Test
    void workerResumesPersistedDeadlineAndRetainsPausedStateWithoutTimerInMemory() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long first = item();
        due();
        try (var worker = new ChatMusicPlaybackWorker(jdbc, taskB, playback)) {
            worker.poll();
            assertThat(status(first)).isEqualTo("PLAYED");
            long next = item();
            playback.control(taskA, other, UUID.randomUUID().toString(), "PAUSE", next, 3);
            worker.poll();
            assertThat(snapshot().get("state")).isEqualTo("PAUSED");
            assertThat(snapshot().get("version")).isEqualTo(4L);
        }
    }

    @Test
    void automaticTransitionRollsBackIfSecondOutboxTypeFailsThenRetriesOnce() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long first = item();
        due();
        jdbc.execute("CREATE TRIGGER synthetic_second_event_failure BEFORE INSERT ON chat_music_outbox "
                + "FOR EACH ROW BEGIN IF NEW.event_type='PLAYBACK_UPDATED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic second event failure'; END IF; END");
        try {
            assertThatThrownBy(() -> tick(taskA)).isInstanceOf(ChatParticipationUnavailableException.class);
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_second_event_failure");
        }
        assertThat(status(first)).isEqualTo("PLAYING");
        assertThat(snapshot().get("version")).isEqualTo(2L);
        assertThat(count("chat_music_outbox")).isEqualTo(3);
        tick(taskB);
        assertThat(status(first)).isEqualTo("PLAYED");
        assertThat(snapshot().get("version")).isEqualTo(3L);
        assertThat(count("chat_music_outbox")).isEqualTo(5);
    }

    @Test
    void queueAndPlaybackShareUuidConflictContractAndValidateLegacyActiveStorage() {
        String id = UUID.randomUUID().toString();
        apiA.add(caller, id, music);
        long first = item();
        error(() -> playback.control(taskB, caller, id, "PAUSE", first, 1), "COMMAND_ID_CONFLICT");
        assertThatThrownBy(() -> playback.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", first, -1))
                .isInstanceOf(ChatMusicException.class);
        playback.validateStorage();
        jdbc.update("UPDATE playback_states SET source_video_id=NULL,duration_millis=NULL WHERE chat_room_id=?",
                caller.roomId());
        assertThatThrownBy(playback::validateStorage).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void queueReadRepairsOverdueTrackAndReturnsEpochWithoutReplayingEveryElapsedSong() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        apiA.add(caller, UUID.randomUUID().toString(), music);
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long previous = item();
        due();
        var latest = apiA.queue(caller, 20, null);
        assertThat(latest.get("epoch")).isEqualTo(1L);
        assertThat(latest.get("version")).isEqualTo(4L);
        assertThat(status(previous)).isEqualTo("PLAYED");
        assertThat(latest.get("state")).isEqualTo("PLAYING");
        assertThat(item()).isNotEqualTo(previous);
        assertThat(apiB.queue(other, 20, null).get("version")).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=? AND status='PLAYED'",
                Long.class, caller.roomId())).isEqualTo(1);
    }

    private void verifiedFailure(int seconds) {
        for (var c : List.of(caller, other)) {
            jdbc.update("""
                    INSERT INTO chat_view_intervals(view_session_id,process_id,started_at,start_after_message_id,
                        verified_at,verified_through_message_id)
                    VALUES (?,?,TIMESTAMPADD(SECOND,-60,UTC_TIMESTAMP(6)),0,
                        TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),0)
                    """, c.viewId(), taskB.taskId(), -seconds);
        }
        jdbc.update("UPDATE chat_runtime_tasks SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
                + "WHERE environment=? AND task_id=?", environment, taskB.taskId());
    }

    @Test
    void pausedFailureSurvivesOnlyTheLastVerifiedThirtySecondsAndWorkerScansIt() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        playback.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", item(), 1);
        apiA.add(caller, UUID.randomUUID().toString(), music);
        verifiedFailure(5);
        try (var worker = new ChatMusicPlaybackWorker(jdbc, taskA, playback)) {
            worker.poll();
            assertThat(jdbc.queryForObject("SELECT state FROM playback_states WHERE chat_room_id=?",
                    String.class, caller.roomId())).isEqualTo("PAUSED");
            jdbc.update("UPDATE chat_view_intervals SET verified_at=TIMESTAMPADD(SECOND,-31,UTC_TIMESTAMP(6)) "
                    + "WHERE view_session_id IN (?,?)", caller.viewId(), other.viewId());
            worker.poll();
            worker.poll();
            assertThat(jdbc.queryForObject("SELECT state FROM playback_states WHERE chat_room_id=?",
                    String.class, caller.roomId())).isEqualTo("IDLE");
            assertThat(count("chat_music_command_receipts")).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=? "
                    + "AND status='CANCELED'", Long.class, caller.roomId())).isEqualTo(2);
        }
    }

    @Test
    void expiredOrphanIsClearedBeforeTheSameViewCanMaskAbsenceWithANewReservation() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        playback.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", item(), 1);
        verifiedFailure(31);
        taskA.claim(caller.userId(), caller.sid(), caller.roomId(), caller.membershipId(), caller.viewId(), "restored");
        assertThat(jdbc.queryForObject("SELECT state FROM playback_states WHERE chat_room_id=?",
                String.class, caller.roomId())).isEqualTo("IDLE");
        assertThat(status(jdbc.queryForObject("SELECT MIN(id) FROM music_queue_items WHERE room_id=?",
                Long.class, caller.roomId()))).isEqualTo("CANCELED");
    }

    @Test
    void sameViewRecoveryInsideGracePreservesPausedPinAndDoesNotGrantReadingAcrossAbsence() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long current = item();
        playback.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", current, 1);
        verifiedFailure(5);
        taskA.claim(caller.userId(), caller.sid(), caller.roomId(), caller.membershipId(), caller.viewId(), "restored");
        taskA.ready(caller.userId(), caller.roomId(), caller.membershipId(), caller.viewId(), "restored");
        var restored = apiA.queue(caller, 20, null);
        assertThat(restored.get("state")).isEqualTo("PAUSED");
        assertThat(restored.get("version")).isEqualTo(2L);
        assertThat(restored.get("source_video_id")).isEqualTo("abcdefghijk");
        assertThat(restored.get("current_queue_item_id")).isEqualTo(current);
    }

    @Test
    void lateRestJoinCannotResurrectPausedQueueByCreatingANewReservation() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        playback.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", item(), 1);
        verifiedFailure(31);
        var views = (ChatViewStorageService) org.springframework.test.util.ReflectionTestUtils
                .getField(taskA, "storage");
        doAnswer(invocation -> {
            String view = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO chat_view_sessions(id,user_id,room_id,membership_id,created_at,updated_at)
                    VALUES (?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, view, caller.userId(), caller.roomId(), caller.membershipId());
            return view;
        }).when(views).create(caller.userId(), caller.roomId(), caller.membershipId());
        long parent = jdbc.queryForObject("SELECT parent_id FROM regions WHERE id=?", Long.class, region);
        var claims = new com.muse.meomuneum.location.security.LocationResolutionClaims(caller.userId(), parent,
                jdbc.queryForObject("SELECT code FROM regions WHERE id=?", String.class, parent), region,
                jdbc.queryForObject("SELECT code FROM regions WHERE id=?", String.class, region),
                java.time.Instant.now().plusSeconds(60));
        var joined = taskA.join(caller.userId(), caller.sid(), caller.roomId(), claims);
        assertThat(joined.membership().viewSessionId()).isNotEqualTo(caller.viewId());
        assertThat(jdbc.queryForObject("SELECT state FROM playback_states WHERE chat_room_id=?",
                String.class, caller.roomId())).isEqualTo("IDLE");
        assertThat(jdbc.queryForObject("SELECT version FROM playback_states WHERE chat_room_id=?",
                Long.class, caller.roomId())).isEqualTo(3L);
    }

    private void tick(ChatParticipationService task) {
        task.systemMusicTransaction(caller.roomId(), tx -> {
            playback.reconcile(tx);
            return null;
        });
    }

    private void due() {
        jdbc.update("UPDATE playback_states SET state_changed_at=TIMESTAMPADD(SECOND,-121,UTC_TIMESTAMP(6)), "
                + "due_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE chat_room_id=?", caller.roomId());
    }

    private java.util.Map<String, Object> snapshot() {
        return taskB.systemMusicTransaction(caller.roomId(), tx -> playback.snapshot(caller.roomId()));
    }

    private long item() {
        return ((Number) snapshot().get("current_queue_item_id")).longValue();
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM music_queue_items WHERE id=?", String.class, id);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE room_id=?", Long.class, caller.roomId());
    }

    private void error(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ChatMusicException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
