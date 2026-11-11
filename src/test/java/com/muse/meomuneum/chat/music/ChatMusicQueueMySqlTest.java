package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.List;
import java.util.Map;
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
class ChatMusicQueueMySqlTest {
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
    private ChatMusicVideoService videoService;

    private ChatMusicVideoService videos(ChatMusicQueueService api, ChatParticipationService task,
            ChatMusicVideoProvider provider) {
        var result = new ChatMusicVideoService(jdbc, task, new RequestableMusicRepository(jdbc), provider,
                new ChatMusicVideoBudget(jdbc), 3600, 300, 300);
        api.setVideoService(result);
        if (api == apiA) {
            videoService = result;
        }
        return result;
    }

    private ChatMusicVideoProvider provider() {
        var provider = mock(ChatMusicVideoProvider.class);
        when(provider.search("Synthetic song", "Synthetic artist")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(
                    new ChatMusicVideoProvider.Video("ABCDEFGHIJK", "channel", "Video A", "Channel", null, 180000),
                    new ChatMusicVideoProvider.Video("LMNOPQRSTUV", "channel", "Video B", "Channel", null, 200000));
        });
        return provider;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> videoItems(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private ChatMusicPlaybackService playback() {
        var result = new ChatMusicPlaybackService(jdbc,
                new ChatMusicStateRepository(jdbc, new DataSourceTransactionManager(source)),
                new ChatMusicCommandStore(jdbc, keys, 0));
        result.setVideoService(videoService);
        apiA.setPlaybackService(result);
        apiB.setPlaybackService(result);
        return result;
    }

    @Test
    void sharedCandidatesSelectExactPinsAndReplayEvenAfterExpiryOrProviderLoss() {
        var provider = provider();
        videos(apiA, taskA, provider);
        videos(apiB, taskB, provider);
        jdbc.update("UPDATE music SET youtube_video_id=NULL WHERE id=?", music);
        var page = apiA.videoSearch(caller, music);
        assertThat(jdbc.queryForObject("SELECT youtube_video_id FROM music WHERE id=?", String.class, music)).isNull();
        assertThat(apiB.videoSearch(caller, music)).isEqualTo(page);
        verify(provider, times(1)).search("Synthetic song", "Synthetic artist");
        var items = videoItems(page);
        assertThat(items).hasSize(2);
        assertThat(items.getFirst()).containsEntry("official_status", "UNREVIEWED").containsEntry("video_id",
                "ABCDEFGHIJK");
        String a = (String) items.getFirst().get("candidate_id");
        String b = (String) items.getLast().get("candidate_id");
        var playback = playback();
        String command = UUID.randomUUID().toString();
        var receipt = apiB.add(caller, command, music, a);
        assertThat(receipt.status()).isEqualTo(201);
        apiA.add(other, UUID.randomUUID().toString(), music, b);
        var snapshot = apiA.queue(caller, 20, null);
        assertThat(snapshot).containsEntry("source_video_id", "ABCDEFGHIJK").containsEntry("duration_millis", 180000L);
        assertThat(jdbc.queryForObject("SELECT youtube_video_id FROM music WHERE id=?", String.class, music))
                .isEqualTo("LMNOPQRSTUV");
        assertThat(videoItems(snapshot).getFirst()).containsEntry("youtube_video_id", "LMNOPQRSTUV")
                .containsEntry("duration_millis", 200000L);
        long current = ((Number) snapshot.get("current_queue_item_id")).longValue();
        playback.control(taskA, caller, UUID.randomUUID().toString(), "PAUSE", current, 2);
        playback.control(taskA, caller, UUID.randomUUID().toString(), "RESUME", current, 3);
        assertThat(apiA.queue(caller, 20, null)).containsEntry("source_video_id", "ABCDEFGHIJK");
        jdbc.update("""
                UPDATE playback_states SET state_changed_at=TIMESTAMPADD(SECOND,-181,UTC_TIMESTAMP(6)),
                  due_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE chat_room_id=?
                """, caller.roomId());
        assertThat(apiA.queue(caller, 20, null)).containsEntry("source_video_id", "LMNOPQRSTUV")
                .containsEntry("duration_millis", 200000L);
        var restored = playback();
        restored.validateStorage();
        assertThat(apiB.queue(caller, 20, null)).containsEntry("source_video_id", "LMNOPQRSTUV");
        jdbc.update("UPDATE music_video_search_results SET created_at=TIMESTAMPADD(HOUR,-2,UTC_TIMESTAMP(6)),"
                + "expires_at=TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)) WHERE id=?", page.get("search_id"));
        apiA.setVideoService(null);
        assertThat(apiA.add(caller, command, music, a)).isEqualTo(receipt);
        assertThat(apiB.queue(caller, 20, null)).containsEntry("source_video_id", "LMNOPQRSTUV");
        error(() -> apiA.add(caller, command, music, b), 409, "COMMAND_ID_CONFLICT");
        assertThat(count("music_queue_items")).isEqualTo(2);
    }

    @Test
    void expiredWrongSongAndRevokedProvenanceDoNotEnqueue() {
        videos(apiA, taskA, provider());
        var page = apiA.videoSearch(caller, music);
        String candidate = (String) videoItems(page).getFirst().get("candidate_id");
        error(() -> apiA.add(caller, UUID.randomUUID().toString(), music, UUID.randomUUID().toString()), 400,
                "INVALID_CANDIDATE");
        jdbc.update("UPDATE music_video_search_results SET created_at=TIMESTAMPADD(HOUR,-2,UTC_TIMESTAMP(6)),"
                + "expires_at=TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)) WHERE id=?", page.get("search_id"));
        error(() -> apiA.add(caller, UUID.randomUUID().toString(), music, candidate), 410, "CANDIDATE_EXPIRED");
        jdbc.update("UPDATE recommendation_sessions SET status='FAILED' WHERE user_id=?", caller.userId());
        error(() -> apiA.videoSearch(caller, music), 409, "MUSIC_NOT_REQUESTABLE");
        assertThat(count("music_queue_items")).isZero();
    }

    @Test
    void transientMetadataFailurePreservesWaitingAndConfirmedUnavailableAdvancesWithoutChangingPins() {
        var provider = provider();
        var videos = videos(apiA, taskA, provider);
        var items = videoItems(apiA.videoSearch(caller, music));
        String a = (String) items.getFirst().get("candidate_id");
        apiA.add(caller, UUID.randomUUID().toString(), music, a);
        apiA.add(caller, UUID.randomUUID().toString(), music, (String) items.getLast().get("candidate_id"));
        jdbc.update("UPDATE music_video_sources SET verified_at=TIMESTAMPADD(HOUR,-2,UTC_TIMESTAMP(6)),"
                + "valid_until=TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)) "
                + "WHERE music_id=? AND youtube_video_id='ABCDEFGHIJK'",
                music);
        when(provider.verify("ABCDEFGHIJK")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new ChatMusicException(503, "VIDEO_SEARCH_UNAVAILABLE");
        });
        playback();
        videos.prepareRoom(caller.roomId());
        assertThat(apiA.queue(caller, 20, null)).containsEntry("state", "IDLE");
        assertThat(count("music_queue_items")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=? AND status='WAITING'",
                Long.class, caller.roomId())).isEqualTo(2);
        doReturn(null).when(provider).verify("ABCDEFGHIJK");
        videos.prepare(caller, music, a);
        assertThat(apiA.queue(caller, 20, null)).containsEntry("state", "PLAYING").containsEntry("source_video_id",
                "LMNOPQRSTUV");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=? AND status='CANCELED'",
                Long.class, caller.roomId())).isEqualTo(1);
    }

    @Test
    void metadataChangeDuringExternalSearchCannotPublishOldCacheAndEmptyIsNotFailure() {
        var provider = provider();
        videos(apiA, taskA, provider);
        when(provider.search("Synthetic song", "Synthetic artist")).thenAnswer(invocation -> {
            jdbc.update("UPDATE music SET title='Changed title' WHERE id=?", music);
            return List.of();
        });
        error(() -> apiA.videoSearch(caller, music), 409, "MUSIC_METADATA_CHANGED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_video_search_results WHERE music_id=?", Long.class,
                music)).isZero();
        when(provider.search("Changed title", "Synthetic artist")).thenReturn(List.of());
        assertThat(videoItems(apiA.videoSearch(caller, music))).isEmpty();
        assertThat(apiA.videoSearch(caller, music)).containsEntry("result_kind", "EMPTY");
        verify(provider, times(1)).search("Changed title", "Synthetic artist");
    }

    @Test
    void verificationRefreshUsesFiveMinutesAndDoesNotOverwriteConcurrentSourceDecision() {
        var provider = provider();
        videos(apiA, taskA, provider);
        var item = videoItems(apiA.videoSearch(caller, music)).getFirst();
        jdbc.update("UPDATE music_video_sources SET verified_at=TIMESTAMPADD(MINUTE,-6,UTC_TIMESTAMP(6)),"
                + "valid_until=TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)) WHERE music_id=?", music);
        when(provider.verify("ABCDEFGHIJK")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.update(
                    "UPDATE music_video_sources SET playback_status='UNAVAILABLE',version=version+1 WHERE music_id=?",
                    music);
            return new ChatMusicVideoProvider.Video("ABCDEFGHIJK", "channel", "A", "Channel", null, 999000);
        });
        error(() -> apiA.add(caller, UUID.randomUUID().toString(), music, (String) item.get("candidate_id")), 409,
                "VIDEO_UNAVAILABLE");
        assertThat(count("music_queue_items")).isZero();
        assertThat(jdbc.queryForObject("SELECT youtube_video_id FROM music WHERE id=?", String.class, music))
                .isEqualTo("abcdefghijk");
    }

    @Test
    void selectedSourceRepresentativeAndReceiptRollBackTogetherOnOutboxFailure() {
        videos(apiA, taskA, provider());
        String candidate = (String) videoItems(apiA.videoSearch(caller, music)).getFirst().get("candidate_id");
        jdbc.execute("CREATE TRIGGER selected_outbox_failure BEFORE INSERT ON chat_music_outbox "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
        try {
            assertThatThrownBy(() -> apiA.add(caller, UUID.randomUUID().toString(), music, candidate))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("music_queue_items")).isZero();
            assertThat(count("chat_music_command_receipts")).isZero();
            assertThat(jdbc.queryForObject("SELECT youtube_video_id FROM music WHERE id=?", String.class, music))
                    .isEqualTo("abcdefghijk");
        } finally {
            jdbc.execute("DROP TRIGGER selected_outbox_failure");
        }
    }

    @Test
    void concurrentSelectionsKeepBothQueuePinsAndOneAggregateOrder() throws Exception {
        var provider = provider();
        videos(apiA, taskA, provider);
        videos(apiB, taskB, provider);
        var items = videoItems(apiA.videoSearch(caller, music));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var first = executor.submit(() -> {
                gate.await();
                return apiA.add(caller, UUID.randomUUID().toString(), music,
                        (String) items.getFirst().get("candidate_id"));
            });
            var second = executor.submit(() -> {
                gate.await();
                return apiB.add(other, UUID.randomUUID().toString(), music,
                        (String) items.getLast().get("candidate_id"));
            });
            gate.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
            assertThat(second.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
        }
        assertThat(jdbc.queryForList("SELECT source_video_id FROM music_queue_items WHERE room_id=?", String.class,
                caller.roomId())).containsExactlyInAnyOrder("ABCDEFGHIJK", "LMNOPQRSTUV");
        assertThat(jdbc.queryForList("SELECT sequence_no FROM music_queue_items WHERE room_id=? ORDER BY sequence_no",
                Long.class, caller.roomId())).containsExactly(1L, 2L);
        assertThat(count("chat_music_command_receipts")).isEqualTo(2);
        assertThat(version()).isEqualTo(2);
    }

    @Test
    void operatorBlockSurvivesRepresentativeReplacementAndCannotBeUndoneByRefresh() {
        var provider = provider();
        var videos = videos(apiA, taskA, provider);
        String candidate = (String) videoItems(apiA.videoSearch(caller, music)).getFirst().get("candidate_id");
        jdbc.update("""
                UPDATE music_video_sources SET operator_blocked=TRUE,playback_status='UNAVAILABLE',version=version+1,
                  verified_at=TIMESTAMPADD(HOUR,-2,UTC_TIMESTAMP(6)),valid_until=TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6))
                WHERE music_id=? AND youtube_video_id='ABCDEFGHIJK'
                """, music);
        videos.prepare(caller, music, candidate);
        error(() -> apiA.add(caller, UUID.randomUUID().toString(), music, candidate), 409, "VIDEO_UNAVAILABLE");
        verify(provider, times(0)).verify("ABCDEFGHIJK");
        assertThat(count("music_queue_items")).isZero();
    }

    @Test
    void stoppedWorkerCutoverPinsKnownLegacyWaitingAndRestoresActualActivePin() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        jdbc.update("UPDATE music_queue_items SET source_video_id=NULL,duration_millis=NULL WHERE room_id=?",
                caller.roomId());
        var playback = playback();
        playback.validateStorage();
        assertThat(jdbc.queryForObject("SELECT source_video_id FROM music_queue_items WHERE room_id=?", String.class,
                caller.roomId())).isEqualTo("abcdefghijk");
        apiA.queue(caller, 20, null);
        jdbc.update("UPDATE music SET youtube_video_id='ZYXWVUTSRQP' WHERE id=?", music);
        jdbc.update("UPDATE music_queue_items SET source_video_id=NULL,duration_millis=NULL WHERE room_id=?",
                caller.roomId());
        playback.validateStorage();
        assertThat(jdbc.queryForObject("SELECT source_video_id FROM music_queue_items WHERE room_id=?", String.class,
                caller.roomId())).isEqualTo("abcdefghijk");
        assertThat(apiA.queue(caller, 20, null)).containsEntry("source_video_id", "abcdefghijk");
    }

    @Test
    void legacyAdmissionCannotOverrideNewerUnavailabilityOrOperatorBlock() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        jdbc.update("UPDATE music_video_sources SET playback_status='UNAVAILABLE' WHERE music_id=?", music);
        error(() -> apiA.add(caller, UUID.randomUUID().toString(), music), 409, "MUSIC_NOT_REQUESTABLE");
        jdbc.update("UPDATE music_video_sources SET playback_status='PLAYABLE',operator_blocked=TRUE "
                + "WHERE music_id=?", music);
        error(() -> apiB.add(caller, UUID.randomUUID().toString(), music), 409, "MUSIC_NOT_REQUESTABLE");
        assertThat(count("music_queue_items")).isEqualTo(1);
        assertThat(count("chat_music_command_receipts")).isEqualTo(1);
    }

    @Test
    void historicalUnknownPinCanBeFoundAndSelectedWithoutInventingOfficialReview() {
        jdbc.update("INSERT INTO music_video_sources(music_id,youtube_video_id) VALUES (?,'ABCDEFGHIJK')", music);
        var provider = provider();
        when(provider.verify("ABCDEFGHIJK")).thenReturn(
                new ChatMusicVideoProvider.Video("ABCDEFGHIJK", "channel", "Video A", "Channel", null, 180000));
        videos(apiA, taskA, provider);
        var item = videoItems(apiA.videoSearch(caller, music)).getFirst();
        assertThat(item).containsEntry("channel_id", "channel").containsEntry("official_status", "UNREVIEWED");
        assertThat(apiA.add(caller, UUID.randomUUID().toString(), music, (String) item.get("candidate_id")).status())
                .isEqualTo(201);
        verify(provider).verify("ABCDEFGHIJK");
    }

    @Test
    void sharedAccountAndProviderBudgetCannotBeBypassedByAnotherTask() {
        var first = new ChatMusicVideoBudget(jdbc);
        var second = new ChatMusicVideoBudget(jdbc);
        String scope = "test-" + UUID.randomUUID();
        first.reserve(scope, 86400, 1);
        error(() -> second.reserve(scope, 86400, 1), 429, "VIDEO_REQUEST_RATE_LIMITED");
    }

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
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
    void remoteOwnerAddRetryConflictAndAnyParticipantCancelAreAtomic() {
        String id = UUID.randomUUID().toString();
        var created = apiA.add(caller, id, music);
        assertThat(created.status()).isEqualTo(201);
        assertThat(apiB.add(caller, id, music)).isEqualTo(created);
        assertThat(count("music_queue_items")).isEqualTo(1);
        assertThat(count("chat_music_outbox")).isEqualTo(1);
        error(() -> apiB.add(caller, id, music + 1), 409, "COMMAND_ID_CONFLICT");
        var queue = apiA.queue(other, 20, null);
        assertThat(queue.get("state")).isEqualTo("IDLE");
        assertThat(queue.get("current")).isNull();
        long item = jdbc.queryForObject("SELECT id FROM music_queue_items WHERE room_id=?", Long.class,
                caller.roomId());
        String cancel = UUID.randomUUID().toString();
        assertThat(apiB.cancel(other, cancel, item, 1).status()).isEqualTo(204);
        assertThat(apiA.cancel(other, cancel, item, 1).status()).isEqualTo(204);
        assertThat(count("chat_music_outbox")).isEqualTo(2);
        assertThat(version()).isEqualTo(2);
        error(() -> apiA.cancel(caller, UUID.randomUUID().toString(), item, 2), 409, "QUEUE_ITEM_NOT_WAITING");
        assertThat(count("chat_music_command_receipts")).isEqualTo(2);
    }

    @Test
    void searchDoesNotDuplicateProvenanceAndRechecksExpiredSourceOnAdd() {
        var search = apiA.candidates(caller, "Synthetic", 20, null);
        assertThat((List<?>) search.get("items")).hasSize(1);
        assertThat((List<?>) apiA.candidates(caller, "missing", 20, null).get("items")).isEmpty();
        jdbc.update("UPDATE music_playback_sources SET status='UNAVAILABLE' WHERE music_id=?", music);
        assertThat((List<?>) apiA.candidates(caller, "", 20, null).get("items")).hasSize(1);
        error(() -> apiA.add(caller, UUID.randomUUID().toString(), music), 409, "MUSIC_NOT_REQUESTABLE");
        assertThat(count("music_queue_items")).isZero();
        assertThat(count("chat_music_command_receipts")).isZero();
    }

    @Test
    void failedOutboxAndCommitAuthorizationRollBackQueueReceiptAndCounters() {
        jdbc.execute("CREATE TRIGGER synthetic_music_outbox_failure BEFORE INSERT ON chat_music_outbox "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
        try {
            assertThatThrownBy(() -> apiA.add(caller, UUID.randomUUID().toString(), music))
                    .isInstanceOf(ChatParticipationUnavailableException.class);
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_music_outbox_failure");
        }
        assertThat(count("music_queue_items")).isZero();
        assertThat(count("chat_music_command_receipts")).isZero();
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new ChatMusicException(401, "SESSION_REVOKED");
                }
            });
            return null;
        }).when(access).authorize(caller.userId(), caller.sid());
        error(() -> apiA.add(caller, UUID.randomUUID().toString(), music), 401, "SESSION_REVOKED");
        assertThat(count("music_queue_items")).isZero();
        assertThat(count("chat_music_outbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playback_states WHERE chat_room_id=? AND version<>0",
                Long.class, caller.roomId())).isZero();
    }

    @Test
    void retriesStillRequireCurrentSidConnectedViewAndLiveEpoch() {
        String id = UUID.randomUUID().toString();
        apiA.add(caller, id, music);
        var wrong = new ChatMusicQueueService.Caller(caller.userId(), UUID.randomUUID().toString(), caller.roomId(),
                caller.membershipId(), caller.viewId());
        assertThatThrownBy(() -> apiB.add(wrong, id, music)).isInstanceOf(ChatRoomException.class);
        jdbc.update("UPDATE chat_view_runtime SET state='GRACE' WHERE view_id=?", caller.viewId());
        assertThatThrownBy(() -> apiB.add(caller, id, music)).isInstanceOf(ChatRoomException.class);
        jdbc.update("UPDATE chat_view_runtime SET state='CONNECTED' WHERE view_id=?", caller.viewId());
        jdbc.update("UPDATE chat_runtime_control SET epoch=epoch+1 WHERE environment=?", environment);
        assertThatThrownBy(() -> apiB.add(caller, id, music)).isInstanceOf(ChatParticipationUnavailableException.class);
        assertThat(count("music_queue_items")).isEqualTo(1);
    }

    @Test
    void expiredReceiptIsTombstoneEvenIfCapacityOrEligibilityChanges() {
        String id = UUID.randomUUID().toString();
        apiA.add(caller, id, music);
        jdbc.update("UPDATE chat_music_command_receipts SET completed_at=TIMESTAMPADD(DAY,-2,UTC_TIMESTAMP(6)), "
                + "expires_at=TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)) WHERE environment=?", environment);
        error(() -> apiB.add(caller, id, music), 410, "COMMAND_EXPIRED");
        assertThat(count("music_queue_items")).isEqualTo(1);
        apiA.validateRetainedKeys(environment);
        var replacement = new ChatMusicQueueService(jdbc, taskA, new ChatMusicStateRepository(jdbc,
                new DataSourceTransactionManager(source)), new RequestableMusicRepository(jdbc),
                new ChatMusicKeys("new:" + Base64.getEncoder().encodeToString(new byte[32])), 100, 0);
        assertThatThrownBy(() -> replacement.validateRetainedKeys(environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void limitsAndVersionConflictsDoNotPersistPartialChanges() {
        var limited = api(taskA, 1, 1000);
        String id = UUID.randomUUID().toString();
        limited.add(caller, id, music);
        assertThat(limited.add(caller, id, music).status()).isEqualTo(201);
        error(() -> limited.add(caller, UUID.randomUUID().toString(), music), 429, "COMMAND_RATE_LIMITED");
        error(() -> api(taskB, 1, 0).add(other, UUID.randomUUID().toString(), music), 409, "QUEUE_FULL");
        long item = jdbc.queryForObject("SELECT id FROM music_queue_items WHERE room_id=?", Long.class,
                caller.roomId());
        error(() -> apiB.cancel(other, UUID.randomUUID().toString(), item, 0), 409, "VERSION_CONFLICT");
        assertThat(version()).isEqualTo(1);
    }

    @Test
    void twoTasksSerializeSimultaneousAppendsAndSameCommandRetries() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = workers.submit(() -> {
                gate.await();
                return apiA.add(caller, UUID.randomUUID().toString(), music);
            });
            var b = workers.submit(() -> {
                gate.await();
                return apiB.add(other, UUID.randomUUID().toString(), music);
            });
            gate.countDown();
            assertThat(a.get(5, TimeUnit.SECONDS).status()).isEqualTo(201);
            assertThat(b.get(5, TimeUnit.SECONDS).status()).isEqualTo(201);
            assertThat(
                    jdbc.queryForList("SELECT sequence_no FROM music_queue_items WHERE room_id=? ORDER BY sequence_no",
                            Long.class, caller.roomId()))
                    .containsExactly(1L, 2L);
            String id = UUID.randomUUID().toString();
            var x = workers.submit(() -> apiA.add(caller, id, music));
            var y = workers.submit(() -> apiB.add(caller, id, music));
            assertThat(x.get(5, TimeUnit.SECONDS)).isEqualTo(y.get(5, TimeUnit.SECONDS));
        }
        assertThat(count("music_queue_items")).isEqualTo(3);
        assertThat(count("chat_music_outbox")).isEqualTo(3);
    }

    @Test
    void queueCursorsBindViewSizeAndVersion() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        apiA.add(other, UUID.randomUUID().toString(), music);
        String cursor = (String) apiA.queue(caller, 1, null).get("next_cursor");
        assertThat(cursor).isNotBlank();
        assertThat((List<?>) apiB.queue(caller, 1, cursor).get("items")).hasSize(1);
        error(() -> apiB.queue(other, 1, cursor), 400, "INVALID_CURSOR");
        error(() -> apiB.queue(caller, 2, cursor), 400, "INVALID_CURSOR");
        apiA.add(caller, UUID.randomUUID().toString(), music);
        error(() -> apiB.queue(caller, 1, cursor), 409, "QUEUE_CHANGED");
    }

    @Test
    void ownRecommendationsDoNotGainRecordedProvenanceFromAnotherUsersPublicRecord() {
        jdbc.update("DELETE FROM recommendation_items WHERE music_id=?", music);
        String dotCode = UUID.randomUUID().toString().substring(0, 16);
        jdbc.update("INSERT INTO map_dots(code,region_id,latitude,longitude) VALUES (?,?,37,127)", dotCode, region);
        long dot = jdbc.queryForObject("SELECT id FROM map_dots WHERE code=?", Long.class, dotCode);
        jdbc.update("INSERT INTO music_records(user_id,music_id,map_dot_id,region_id) VALUES (?,?,?,?)",
                other.userId(), music, dot, region);
        assertThat((List<?>) apiA.candidates(caller, "", 20, null).get("items")).isEmpty();
        assertThat((List<?>) apiA.candidates(other, "artist", 20, null).get("items")).hasSize(1);
        jdbc.update("INSERT INTO user_settings(user_id,map_visibility) VALUES (?,'PUBLIC')", other.userId());
        recommendation(caller.userId(), music);
        recommendation(caller.userId(), music);
        var items = (List<?>) apiA.candidates(caller, "song", 20, null).get("items");
        assertThat(items).hasSize(1);
        assertThat(((java.util.Map<?, ?>) items.getFirst()).get("sources"))
                .isEqualTo(List.of("RECOMMENDED"));
        jdbc.update("UPDATE music_records SET deleted_at=UTC_TIMESTAMP(6) WHERE music_id=?", music);
        jdbc.update("DELETE FROM recommendation_items WHERE music_id=?", music);
        error(() -> apiA.add(caller, UUID.randomUUID().toString(), music), 409, "MUSIC_NOT_REQUESTABLE");
    }

    @Test
    void playingItemCannotBeCanceledAndPausedCurrentItemIsInSnapshot() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long item = jdbc.queryForObject("SELECT id FROM music_queue_items WHERE room_id=?", Long.class,
                caller.roomId());
        jdbc.update("UPDATE music_queue_items SET status='PLAYING',started_at=UTC_TIMESTAMP(6) WHERE id=?", item);
        jdbc.update("UPDATE playback_states SET state='PAUSED',current_queue_item_id=?,position_seconds=3.25,"
                + "source_video_id='abcdefghijk',duration_millis=120000 "
                + "WHERE chat_room_id=?", item, caller.roomId());
        var snapshot = apiA.queue(other, 20, null);
        assertThat(snapshot.get("state")).isEqualTo("PAUSED");
        assertThat((List<?>) snapshot.get("items")).isEmpty();
        assertThat(((java.util.Map<?, ?>) snapshot.get("current")).get("queue_item_id")).isEqualTo(item);
        error(() -> apiB.cancel(other, UUID.randomUUID().toString(), item, 1), 409, "QUEUE_ITEM_NOT_WAITING");
        assertThat(version()).isEqualTo(1);
    }

    @Test
    void cancellationNeverReusesSequenceAndExhaustedCountersRollBack() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long item = jdbc.queryForObject("SELECT id FROM music_queue_items WHERE room_id=?", Long.class,
                caller.roomId());
        apiB.cancel(other, UUID.randomUUID().toString(), item, 1);
        apiA.add(caller, UUID.randomUUID().toString(), music);
        assertThat(jdbc.queryForList("SELECT sequence_no FROM music_queue_items WHERE room_id=? ORDER BY sequence_no",
                Long.class, caller.roomId())).containsExactly(1L, 2L);
        jdbc.update("UPDATE playback_states SET version=9007199254740991 WHERE chat_room_id=?", caller.roomId());
        error(() -> apiA.add(caller, UUID.randomUUID().toString(), music), 409, "MUSIC_COUNTER_EXHAUSTED");
        assertThat(count("music_queue_items")).isEqualTo(2);
        assertThat(count("chat_music_outbox")).isEqualTo(3);
    }

    @Test
    void staleMembershipEndedViewInactiveRoomAndBanDenyEveryEndpoint() {
        jdbc.update("UPDATE chat_view_sessions SET ended_at=UTC_TIMESTAMP(6) WHERE id=?", caller.viewId());
        assertThatThrownBy(() -> apiA.candidates(caller, "", 20, null)).isInstanceOf(ChatRoomException.class);
        jdbc.update("UPDATE chat_view_sessions SET ended_at=NULL WHERE id=?", caller.viewId());
        jdbc.update("UPDATE chat_room_members SET deleted_at=UTC_TIMESTAMP(6) WHERE id=?", caller.membershipId());
        assertThatThrownBy(() -> apiA.queue(caller, 20, null)).isInstanceOf(ChatRoomException.class);
        jdbc.update("UPDATE chat_room_members SET deleted_at=NULL WHERE id=?", caller.membershipId());
        jdbc.update("UPDATE chat_rooms SET status='INACTIVE' WHERE id=?", caller.roomId());
        assertThatThrownBy(() -> apiA.add(caller, UUID.randomUUID().toString(), music))
                .isInstanceOf(ChatRoomException.class);
        jdbc.update("UPDATE chat_rooms SET status='ACTIVE' WHERE id=?", caller.roomId());
        jdbc.update("INSERT INTO chat_bans(user_id,reason,created_at,expires_at) "
                + "VALUES (?,'PROFANITY',UTC_TIMESTAMP(6),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)))", caller.userId());
        assertThatThrownBy(() -> apiA.add(caller, UUID.randomUUID().toString(), music))
                .isInstanceOf(ChatRoomException.class);
        assertThat(count("music_queue_items")).isZero();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE room_id=?", Long.class, caller.roomId());
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM playback_states WHERE chat_room_id=?", Long.class,
                caller.roomId());
    }

    private void error(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ChatMusicException.class, error -> {
            assertThat(error.status()).isEqualTo(status);
            assertThat(error.code()).isEqualTo(code);
        });
    }
}
