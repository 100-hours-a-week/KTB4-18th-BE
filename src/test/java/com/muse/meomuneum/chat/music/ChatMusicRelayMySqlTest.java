package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.chat.auth.ChatSessionVerifier;
import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.connection.ChatSessionAccessPolicy;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.participation.ChatParticipationProjection;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.participation.ChatParticipationUnavailableException;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisProperties;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;
import com.muse.meomuneum.chat.view.ChatViewStorageService;
import com.muse.meomuneum.music.catalog.RequestableMusicRepository;

@Testcontainers
class ChatMusicRelayMySqlTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0").withCommand("--skip-log-bin");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "synthetic-music-redis-password");
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
    private ChatRedisConnection redisA;
    private ChatRedisConnection redisB;
    private ChatRedisChannels channels;
    private ChatMusicOutboxStore storeA;
    private ChatMusicOutboxStore storeB;
    private ChatMusicRelay relayA;
    private ChatMusicRelay relayB;
    private ChatSessionVerifier verifierA;
    private final Queue<Map<String, Object>> framesA = new ConcurrentLinkedQueue<>();
    private final Queue<Map<String, Object>> framesB = new ConcurrentLinkedQueue<>();

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("20261109103259").load().migrate();
        String stable = "SELECT chat_room_id,state,version,next_sequence_no,position_seconds,state_changed_at,"
                + "created_at,updated_at FROM playback_states ORDER BY chat_room_id";
        var before = jdbc.queryForList(stable);
        var latest = Flyway.configure().dataSource(source).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList(stable)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playback_states WHERE source_video_id IS NOT NULL "
                + "OR duration_millis IS NOT NULL OR due_at IS NOT NULL", Long.class)).isZero();
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
        attach(taskA, caller, "music-a");
        attach(taskB, other, "music-b");
        var properties = new ChatRedisProperties(environment, REDIS.getHost(), REDIS.getMappedPort(6379),
                "default", "synthetic-music-redis-password", false,
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        redisA = new ChatRedisConnection(properties);
        redisB = new ChatRedisConnection(properties);
        channels = new ChatRedisChannels(properties);
        storeA = new ChatMusicOutboxStore(jdbc, new DataSourceTransactionManager(source), taskA, environment);
        storeB = new ChatMusicOutboxStore(jdbc, new DataSourceTransactionManager(source), taskB, environment);
        verifierA = mock(ChatSessionVerifier.class);
        relayA = relay(taskA, caller, "music-a", redisA, storeA, framesA, verifierA);
        relayB = relay(taskB, other, "music-b", redisB, storeB, framesB, mock(ChatSessionVerifier.class));
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

    private void attach(ChatParticipationService task, ChatMusicQueueService.Caller c, String socket) {
        jdbc.update("UPDATE chat_view_runtime SET state='OFFLINE',owner_task_id=NULL,socket_token=NULL WHERE view_id=?",
                c.viewId());
        task.claim(c.userId(), c.sid(), c.roomId(), c.membershipId(), c.viewId(), socket);
        task.ready(c.userId(), c.roomId(), c.membershipId(), c.viewId(), socket);
    }

    private ChatMusicRelay relay(ChatParticipationService task, ChatMusicQueueService.Caller c, String socket,
            ChatRedisConnection connection, ChatMusicOutboxStore outbox,
            Queue<Map<String, Object>> frames,
            ChatSessionVerifier verifier) {
        var presence = mock(ChatPresenceRegistry.class);
        when(presence.roomSubscriptions(c.roomId())).thenReturn(Map.of(socket, Set.of("room")));
        var auth = new ChatAuthSessionRegistry();
        auth.register(socket, new ChatAuthSessionRegistry.Context(c.userId(), c.sid()));
        var views = mock(ChatViewLifecycle.class);
        when(views.localViews()).thenReturn(Map.of(socket,
                new ChatViewLifecycle.LocalView(c.userId(), c.roomId(), c.membershipId(), c.viewId())));
        var broker = mock(SimpMessagingTemplate.class);
        doAnswer(call -> {
            Map<String, Object> headers = call.getArgument(2);
            assertThat(headers.get("chatRecipients")).isEqualTo(Set.of(socket));
            assertThat(headers.get("chatMusicVersion")).isNotNull();
            frames.add(call.getArgument(1));
            return null;
        }).when(broker).convertAndSend(anyString(), any(), anyMap());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("broker", broker);
        return new ChatMusicRelay(task, playback, outbox, connection, channels, presence, auth, verifier,
                views, beans.getBeanProvider(SimpMessagingTemplate.class), environment);
    }

    @AfterEach
    void close() {
        if (relayA != null) {
            relayA.close();
        }
        if (relayB != null) {
            relayB.close();
        }
        if (redisA != null) {
            redisA.close();
        }
        if (redisB != null) {
            redisB.close();
        }
    }

    @Test
    void bothTasksIncludingSenderReceiveEqualVersionQueueAndPlaybackThroughRealRedis() throws Exception {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        dispatchAll();
        await(() -> framesA.size() == 2 && framesB.size() == 2);
        assertThat(framesA.stream().map(frame -> frame.get("type")))
                .containsExactlyInAnyOrder("QUEUE_UPDATED", "PLAYBACK_UPDATED");
        assertThat(framesB.stream().map(frame -> frame.get("type")))
                .containsExactlyInAnyOrder("QUEUE_UPDATED", "PLAYBACK_UPDATED");
        assertThat(framesA.stream().map(frame -> data(frame).get("version"))).containsOnly(1L);
        assertThat(framesB.stream().map(frame -> data(frame).get("version"))).containsOnly(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_outbox WHERE room_id=? AND status='SENT'",
                Long.class, caller.roomId())).isEqualTo(2);
    }

    @Test
    void lateAndDuplicateHintsReadLatestDbAndNeverRegressOrLoseTheOtherType() throws Exception {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        var old = storeA.claim(caller.roomId());
        assertThat(old).isNotNull();
        long first = jdbc.queryForObject("SELECT current_queue_item_id FROM playback_states WHERE chat_room_id=?",
                Long.class, caller.roomId());
        playback.control(taskB, other, UUID.randomUUID().toString(), "PAUSE", first, 1);
        var event = old.event();
        relayA.receive(event.channel(channels), event.encode());
        await(() -> framesA.size() == 1);
        assertThat(data(framesA.peek()).get("version")).isEqualTo(2L);
        assertThat(((Map<?, ?>) data(framesA.peek()).get("playback")).get("state")).isEqualTo("PAUSED");
        relayA.receive(event.channel(channels), event.encode());
        storeA.complete(old, true);
        dispatchAll();
        await(() -> framesA.stream().anyMatch(frame -> frame.get("type").equals("QUEUE_UPDATED")));
        assertThat(framesA.stream().filter(frame -> frame.get("type").equals(event.type()))).hasSize(1);
        assertThat(framesA.stream().map(frame -> data(frame).get("version"))).containsOnly(2L);
    }

    @Test
    void claimHeadLeaseRetryAndOldTokenCompletionAreFencedAcrossTasks() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        var first = storeA.claim(caller.roomId());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // The durable claim must not keep the room lock while the publisher performs network I/O.
        assertThat(apiB.add(other, UUID.randomUUID().toString(), music).status()).isEqualTo(201);
        assertThat(storeB.claim(caller.roomId())).isNull();
        assertThat(storeA.complete(first, false)).isTrue();
        assertThat(storeB.claim(caller.roomId())).isNull();
        jdbc.update("UPDATE chat_music_outbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
                + "WHERE event_id=?", first.event().eventId());
        var second = storeB.claim(caller.roomId());
        assertThat(second.event()).isEqualTo(first.event());
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(storeA.complete(first, true)).isFalse();
        jdbc.update("UPDATE chat_music_outbox SET claim_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
                + "WHERE event_id=?", first.event().eventId());
        var third = storeA.claim(caller.roomId());
        assertThat(storeB.publishable(second)).isFalse();
        assertThat(storeB.complete(second, true)).isFalse();
        assertThat(storeA.complete(third, true)).isTrue();
    }

    @Test
    void publishBeforeCompletionCanRetrySameEventAfterClaimExpiresWithoutDuplicateEffects() throws Exception {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        var claim = storeA.claim(caller.roomId());
        redisA.commands().sync().publish(claim.event().channel(channels), claim.event().encode());
        await(() -> framesB.size() == 1);
        jdbc.update("UPDATE chat_music_outbox SET claim_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
                + "WHERE event_id=?", claim.event().eventId());
        dispatchAll();
        await(() -> framesB.size() == 2);
        assertThat(framesB.stream().filter(frame -> frame.get("type").equals(claim.event().type()))).hasSize(1);
    }

    @Test
    void malformedForgedWrongRoomAndOldEpochAreRejectedWithoutBlockingHealthyRelay() throws Exception {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        var claim = storeA.claim(caller.roomId());
        var event = claim.event();
        relayA.receive(channels.musicChannel(caller.roomId() + 1), event.encode());
        relayA.receive(event.channel(channels), event.encode().replace("\"epoch\":1", "\"epoch\":2"));
        relayA.receive(event.channel(channels), "{}");
        relayA.receive(event.channel(channels), event.encode().replace(event.eventId(), UUID.randomUUID().toString()));
        await(() -> relayA.rejectedEvents() == 4);
        assertThat(framesA).isEmpty();
        assertThat(relayA.ready()).isTrue();
    }

    @Test
    void syncIsReadOnlyAndDoesNotRestartPausedMusicOrCreateOutboxRows() throws Exception {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        long first = jdbc.queryForObject("SELECT current_queue_item_id FROM playback_states WHERE chat_room_id=?",
                Long.class, caller.roomId());
        playback.control(taskB, other, UUID.randomUUID().toString(), "PAUSE", first, 1);
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_outbox WHERE room_id=?", Long.class,
                caller.roomId());
        relayA.sync();
        relayB.sync();
        await(() -> framesA.size() == 1 && framesB.size() == 1);
        assertThat(framesA.peek().get("type")).isEqualTo("PLAYBACK_SYNC");
        assertThat(((Map<?, ?>) data(framesA.peek()).get("playback")).get("state")).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("SELECT version FROM playback_states WHERE chat_room_id=?", Long.class,
                caller.roomId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_outbox WHERE room_id=?", Long.class,
                caller.roomId())).isEqualTo(count);
    }

    @Test
    void departedSocketAndCoreRevocationAreDeniedAndExpiredOutboxDoesNotPurgeReceipts() throws Exception {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        taskB.leave(other.userId(), other.sid(), other.roomId(), other.membershipId(), other.viewId());
        org.mockito.Mockito.doThrow(new ChatRoomException(
                com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode.AUTH_REQUIRED))
                .when(verifierA).assertValid(caller.userId(), caller.sid());
        dispatchAll();
        assertThat(framesA).isEmpty();
        assertThat(framesB).isEmpty();
        jdbc.update("UPDATE chat_music_outbox SET occurred_at=TIMESTAMPADD(DAY,-2,UTC_TIMESTAMP(6)), "
                + "expires_at=TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)) WHERE room_id=?", caller.roomId());
        storeA.cleanup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_outbox WHERE room_id=?", Long.class,
                caller.roomId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_command_receipts WHERE room_id=?", Long.class,
                caller.roomId())).isEqualTo(1);
    }

    @Test
    void disconnectedSubscriptionLatchesClosedAndOldEpochCannotCompleteClaim() {
        apiA.add(caller, UUID.randomUUID().toString(), music);
        var claim = storeA.claim(caller.roomId());
        jdbc.update("UPDATE chat_runtime_control SET epoch=epoch+1 WHERE environment=?", environment);
        assertThatThrownBy(() -> storeA.complete(claim, true))
                .isInstanceOf(ChatParticipationUnavailableException.class);
        redisB.close();
        relayB.monitor();
        assertThat(relayB.ready()).isFalse();
        assertThat(taskB.available()).isFalse();
    }

    private void dispatchAll() throws Exception {
        await(() -> {
            relayA.dispatch();
            relayB.dispatch();
            return jdbc.queryForObject("SELECT COUNT(*) FROM chat_music_outbox WHERE room_id=? AND status='PENDING'",
                    Long.class, caller.roomId()) == 0;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> frame) {
        return (Map<String, Object>) frame.get("data");
    }

    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("music relay condition timed out");
            }
            Thread.sleep(10);
        }
    }
}
