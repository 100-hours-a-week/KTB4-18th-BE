package com.muse.meomuneum.chat.participation;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.chat.auth.ChatAuthOperation;
import com.muse.meomuneum.chat.connection.ChatPresenceChangedEvent;
import com.muse.meomuneum.chat.connection.ChatSessionAccessPolicy;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.control.ChatControlEvent;
import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;
import com.muse.meomuneum.chat.message.policy.ChatMessageRejection;
import com.muse.meomuneum.chat.message.query.ChatQueryException;
import com.muse.meomuneum.chat.message.relay.ChatDeliveryMetrics;
import com.muse.meomuneum.chat.music.ChatMusicException;
import com.muse.meomuneum.chat.room.dto.ChatRoomMembershipResponse;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.service.ChatRoomJoinResult;
import com.muse.meomuneum.chat.view.ChatRoomReadBoundaryStore;
import com.muse.meomuneum.chat.view.ChatViewStorageService;
import com.muse.meomuneum.global.security.AuthSessionIds;
import com.muse.meomuneum.location.security.LocationResolutionClaims;

/** DB authority; JVM registries contain only local transports. No Redis/socket/Core I/O inside transactions. */
public final class ChatParticipationService {
    private ChatDeliveryMetrics deliveryMetrics;

    @Autowired(required = false)
    public void setDeliveryMetrics(ChatDeliveryMetrics value) {
        deliveryMetrics = value;
    }

    private <T> T measured(ChatDeliveryMetrics.Stage stage, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            ChatDeliveryMetrics.record(deliveryMetrics, stage, started);
        }
    }

    private static final String EFFECTIVE = """
            v.epoch = ? AND m.deleted_at IS NULL AND s.ended_at IS NULL AND (
              (v.state IN ('RESERVED','GRACE') AND v.deadline > UTC_TIMESTAMP(6)
                 AND (v.owner_task_id IS NULL OR (t.state='ACTIVE' AND t.lease_until > UTC_TIMESTAMP(6))))
              OR (v.state='CONNECTED' AND t.state='ACTIVE' AND t.epoch=v.epoch
                  AND t.lease_until > UTC_TIMESTAMP(6)))
            """;
    private static final String VIEW_JOINS = """
            FROM chat_view_runtime v JOIN chat_view_sessions s ON s.id=v.view_id
            JOIN chat_room_members m ON m.id=v.membership_id
            LEFT JOIN chat_runtime_tasks t ON t.environment=v.environment AND t.task_id=v.owner_task_id
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ChatViewStorageService storage;
    private final ChatSessionAccessPolicy access;
    private final ChatParticipationProjection projection;
    private final ApplicationEventPublisher events;
    private final ChatSocketSessions sockets;
    private final String environment;
    private final ChatRoomReadBoundaryStore boundaries;
    private final Map<String, LocalClaim> claims = new ConcurrentHashMap<>();

    private record LocalClaim(String view, long generation) {
    }

    private long claimGeneration(String socket) {
        var claim = claims.get(socket);
        return claim == null ? -1 : claim.generation();
    }
    private record RecipientPermission(long user, long generation) {
    }

    /** Internal, immutable routing evidence for one message; never authorizes actual outbound handling. */
    public static final class MessageEnqueuePermissions {
        private final ChatParticipationService issuer;
        private final long room;
        private final long message;
        private final long epoch;
        private final Map<String, RecipientPermission> recipients;

        private MessageEnqueuePermissions(ChatParticipationService issuer, long room, long message, long epoch,
                Map<String, RecipientPermission> recipients) {
            this.issuer = issuer;
            this.room = room;
            this.message = message;
            this.epoch = epoch;
            this.recipients = recipients;
        }

        public Set<String> sockets() {
            return recipients.keySet();
        }
    }

    private final String taskId;
    private final ChatParticipationDiagnostics diagnostics;
    private final Set<Long> touchedRooms = ConcurrentHashMap.newKeySet();
    // Admit before acquiring a DB connection: contended room locks must not consume the shared pool.
    private final Semaphore transitionAdmission = new Semaphore(2, true);
    private final Map<Transition, Boolean> activeTransitions = new ConcurrentHashMap<>();

    /** Scheduling hint only: defer background claims while foreground transitions use the shared room row. */
    public boolean transitionBusy() {
        return transitionAdmission.availablePermits() < 2 || transitionAdmission.hasQueuedThreads();
    }
    private volatile long epoch;
    private volatile long localLease;
    private volatile boolean failed;
    private volatile boolean draining;
    private volatile BooleanSupplier runtimePermit = () -> true;

    public void setRuntimePermit(BooleanSupplier permit) {
        runtimePermit = Objects.requireNonNull(permit);
    }

    public void beginDrain() {
        draining = true;
    }

    public boolean isDraining() {
        return draining;
    }

    public boolean isFailed() {
        return failed;
    }
    private volatile boolean controlEvents;

    public void enableControlEvents() {
        controlEvents = true;
    }

    public void forgetSocket(String socket) {
        claims.remove(socket);
    }

    public ChatParticipationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ChatViewStorageService storage, ChatSessionAccessPolicy access, ChatParticipationProjection projection,
            ApplicationEventPublisher events, ChatSocketSessions sockets, String environment) {
        this(jdbc, manager, storage, access, projection, events, sockets, environment, UUID.randomUUID().toString());
    }

    public ChatParticipationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ChatViewStorageService storage, ChatSessionAccessPolicy access, ChatParticipationProjection projection,
            ApplicationEventPublisher events, ChatSocketSessions sockets, String environment, String taskId) {
        this.taskId = UUID.fromString(taskId).toString();
        if (environment == null || !environment.matches("[a-z0-9-]{1,32}")) {
            throw new IllegalArgumentException("chat participation environment required");
        }
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(1);
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setTimeout(2);
        this.storage = storage;
        this.access = access;
        this.projection = projection;
        this.events = events;
        this.sockets = sockets;
        this.environment = environment;
        this.diagnostics = new ChatParticipationDiagnostics(environment, taskId);
        this.boundaries = new ChatRoomReadBoundaryStore(this.jdbc, environment);
    }

    public void start() {
        long started = System.nanoTime();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT IGNORE INTO chat_runtime_control(environment) VALUES (?)", environment);
            Map<String, Object> control = control();
            epoch = ((Number) control.get("epoch")).longValue();
            jdbc.update("""
                    INSERT INTO chat_runtime_tasks(environment,task_id,epoch,state,lease_until)
                    VALUES (?,?,?,'ACTIVE',TIMESTAMPADD(SECOND,15,UTC_TIMESTAMP(6)))
                    """, environment, taskId, epoch);
        });
        localLease = started + TimeUnit.SECONDS.toNanos(14);
    }

    public String taskId() {
        return taskId;
    }

    public long epoch() {
        return epoch;
    }

    private volatile BooleanSupplier messageTransportReady = () -> true;
    private volatile BooleanSupplier musicTransportReady = () -> true;

    public void setMusicTransportReady(BooleanSupplier guard) {
        musicTransportReady = Objects.requireNonNull(guard);
    }

    public void setMessageTransportReady(BooleanSupplier guard) {
        messageTransportReady = guard;
    }

    public boolean available() {
        if (draining) {
            return unavailable("draining");
        }
        if (failed) {
            return unavailable("already_failed");
        }
        if (!runtimePermit.getAsBoolean()) {
            return unavailable("runtime_permit");
        }
        if (!messageTransportReady.getAsBoolean() || !musicTransportReady.getAsBoolean()) {
            return unavailable("message_transport");
        }
        long remaining = localLease - System.nanoTime();
        return remaining > 0 || diagnostics.unavailable("local_lease_expired", remaining);
    }

    private boolean unavailable(String reason) {
        return diagnostics.unavailable(reason, localLease - System.nanoTime());
    }

    public ChatRoomJoinResult join(long user, String sid, long room, LocationResolutionClaims claims) {
        access.authorize(user, sid);
        if (!Long.valueOf(user).equals(claims.userId())) {
            throw error(ChatRoomErrorCode.LOCATION_REGION_MISMATCH);
        }
        Join result = execute(user, tx -> {
            authorizeUser(user, false);
            List<Member> active = members(user, sid, true);
            var rooms = new ArrayList<>(active.stream().map(Member::room).toList());
            rooms.add(room);
            tx.lock(rooms);
            List<Map<String, Object>> targets = jdbc.queryForList("""
                    SELECT r.capacity,r.region_id,g.code FROM chat_rooms r JOIN regions g ON g.id=r.region_id
                    WHERE r.id=? AND r.status='ACTIVE'
                    """, room);
            if (targets.isEmpty()) {
                throw error(ChatRoomErrorCode.CHAT_ROOM_NOT_FOUND);
            }
            Map<String, Object> target = targets.getFirst();
            if (claims.sigunguRegionId() != ((Number) target.get("region_id")).longValue()
                    || !claims.sigunguCode().equals(target.get("code"))) {
                throw error(ChatRoomErrorCode.LOCATION_REGION_MISMATCH);
            }
            Member same = null;
            for (Member member : active) {
                if (member.room == room) {
                    same = member;
                } else {
                    endMembership(tx, member, "REGION_CHANGED");
                }
            }
            if (!capacity(user, room, ((Number) target.get("capacity")).intValue())) {
                return new Join(null, true);
            }
            LocalDateTime now = now();
            long memberId = same == null ? createMembership(user, sid, room, now) : same.id;
            String view = storage.create(user, room, memberId);
            jdbc.update(
                    """
                                INSERT INTO chat_view_runtime
                            (view_id,environment,epoch,user_id,room_id,membership_id,state,deadline)
                                VALUES (?,?,?,?,?,?,'RESERVED',TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)))
                                """,
                    view, environment, epoch, user, room, memberId);
            tx.presenceChanged(room);
            jdbc.update("UPDATE chat_view_runtime SET changed_seq=? WHERE view_id=?", tx.version(room), view);
            LocalDateTime joined = same == null ? now : same.created;
            return new Join(new ChatRoomJoinResult(new ChatRoomMembershipResponse(memberId, room,
                    ((Number) target.get("region_id")).longValue(), joined.atOffset(ZoneOffset.UTC), view),
                    same == null),
                    false);
        });
        if (result.full) {
            throw error(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED);
        }
        return result.result;
    }

    public void claim(long user, String authSessionId, long room, long member, String view, String socket) {
        if (!AuthSessionIds.isValid(view) || socket == null || socket.length() > 128 || claims.containsKey(socket)) {
            throw denied();
        }
        long generation = execute(user, tx -> {
            tx.lock(List.of(room));
            RuntimeView current = owned(user, room, member, view);
            if (authSessionId == null || !authSessionId.equals(sid(member))) {
                throw denied();
            }
            authorizeUser(user, false);
            access.authorize(user, sid(member));
            if (current.state.equals("LEFT") || current.owner != null && ownerLive(current.owner)
                    && (current.state.equals("CONNECTED")
                            || current.deadline != null && current.deadline.isAfter(now()))) {
                throw denied();
            }
            if (!capacity(user, room, roomCapacity(room))) {
                throw error(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED);
            }
            // A socket before subscription remains RESERVED; reconnect must not renew an unconsumed reservation.
            jdbc.update("""
                    UPDATE chat_view_runtime SET state='RESERVED',owner_task_id=?,socket_token=?,
                        owner_generation=owner_generation+1,
                        deadline=CASE WHEN state IN ('RESERVED','GRACE') AND deadline>UTC_TIMESTAMP(6)
                                      THEN deadline ELSE TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) END
                    WHERE view_id=? AND owner_generation=? AND state<>'LEFT'
                    """, taskId, socket, view, current.generation);
            tx.presenceChanged(room);
            mark(view, tx.version(room));
            return current.generation + 1;
        });
        claims.put(socket, new LocalClaim(view, generation));
    }

    public String ready(long user, long room, long member, String view, String socket) {
        return execute(user, tx -> {
            tx.lock(List.of(room));
            RuntimeView current = owned(user, room, member, view);
            requireOwner(current, socket);
            if (!current.state.equals("CONNECTED") && (current.deadline == null || !current.deadline.isAfter(now()))) {
                throw denied();
            }
            authorizeUser(user, false);
            access.authorize(user, sid(member));
            if (current.state.equals("CONNECTED")) {
                return view;
            }
            tx.presenceChanged(room);
            boundaries.subscribed(view, room, epoch, taskId, current.generation);
            jdbc.update("""
                    UPDATE chat_view_runtime SET state='CONNECTED',deadline=NULL
                    WHERE view_id=? AND owner_task_id=? AND socket_token=? AND owner_generation=?
                    """, view, taskId, socket, current.generation);
            tx.presenceChanged(room);
            mark(view, tx.version(room));
            return view;
        });
    }

    public void checkpoint(long user, long room, long member, String view, String socket) {
        execute(user, tx -> {
            tx.lock(List.of(room));
            RuntimeView current = owned(user, room, member, view);
            requireOwner(current, socket);
            if (!current.state.equals("CONNECTED")) {
                throw denied();
            }
            access.authorize(user, sid(member));
            tx.changed(room);
            boundaries.checkpoint(view, room, taskId, current.generation);
            return null;
        });
    }

    public void paused(long user, long room, long member, String view, String socket) {
        execute(user, tx -> {
            tx.lock(List.of(room));
            requireOwner(owned(user, room, member, view), socket);
            tx.presenceChanged(room);
            boundaries.close(view, room, "UNSUBSCRIBED");
            jdbc.update("""
                    UPDATE chat_view_runtime SET state='RESERVED',deadline=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6))
                    WHERE view_id=?
                    """, view);
            tx.presenceChanged(room);
            mark(view, tx.version(room));
            return null;
        });
    }

    public void disconnected(long user, long room, long member, String view, String socket) {
        try {
            execute(user, tx -> {
                tx.lock(List.of(room));
                RuntimeView current = owned(user, room, member, view);
                if (!taskId.equals(current.owner) || !socket.equals(current.socket)) {
                    return null; // Delayed old socket callbacks cannot modify a new claim.
                }
                boolean connected = current.state.equals("CONNECTED");
                if (connected) {
                    boundaries.disconnected(view, taskId, current.generation);
                }
                jdbc.update("""
                        UPDATE chat_view_runtime SET state=?,owner_task_id=NULL,socket_token=NULL,
                            deadline=CASE WHEN ? THEN TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) ELSE deadline END
                        WHERE view_id=? AND owner_task_id=? AND socket_token=? AND owner_generation=?
                        """, connected ? "GRACE" : "RESERVED", connected, view, taskId, socket, current.generation);
                tx.presenceChanged(room);
                mark(view, tx.version(room));
                return null;
            });
        } finally {
            claims.remove(socket);
        }
    }

    public boolean leave(long user, String sid, long room, long member, String view) {
        return execute(user, tx -> {
            tx.lock(List.of(room));
            RuntimeView current = owned(user, room, member, view);
            if (!sid.equals(jdbc.queryForObject("SELECT auth_session_id FROM chat_room_members WHERE id=?",
                    String.class, member))) {
                throw denied();
            }
            if (current.state.equals("LEFT")) {
                return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM chat_room_members WHERE id=? AND deleted_at IS NOT NULL",
                        Integer.class, member) == 1;
            }
            tx.presenceChanged(room);
            boundaries.close(view, room, "LEFT");
            storage.leaveShared(user, room, member, view);
            jdbc.update("""
                    UPDATE chat_view_runtime SET state='LEFT',deadline=NULL,owner_task_id=NULL,socket_token=NULL,
                        owner_generation=owner_generation+1,end_reason='LEFT' WHERE view_id=? AND state<>'LEFT'
                    """, view);
            boolean last = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM chat_view_sessions WHERE membership_id=? AND ended_at IS NULL
                    """, Integer.class, member) == 0;
            if (last) {
                jdbc.update(
                        "UPDATE chat_room_members SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(6)), "
                                + "end_reason='LEFT' WHERE id=?",
                        member);
            }
            tx.presenceChanged(room);
            mark(view, tx.version(room));
            terminalEvent(tx, "CHAT_VIEW_ENDED", user, sid, room, member, view, "LEFT");
            if (last) {
                terminalEvent(tx, "CHAT_MEMBERSHIP_ENDED", user, sid, room, member, null, "LEFT");
            }
            return last;
        });
    }

    public void end(long user, String sid, Long room, Long membership) {
        end(user, sid, room, membership, "LEFT");
    }

    public void end(long user, String sid, Long room, Long membership, String reason) {
        if (!ChatControlEvent.REASONS.contains(reason)) {
            throw new IllegalArgumentException("Unsupported Chat termination reason");
        }
        execute(user, tx -> {
            List<Member> selected = members(user, sid, membership == null).stream()
                    .filter(m -> membership == null || membership == m.id && room != null && room == m.room).toList();
            if (membership != null && selected.isEmpty()) {
                throw error(ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND);
            }
            tx.lock(selected.stream().map(Member::room).toList());
            selected.forEach(member -> endMembership(tx, member, reason));
            return null;
        });
    }

    public void assertSocket(long user, long room, long member, String socket) {
        if (!available()) {
            throw new ChatParticipationUnavailableException();
        }
        long started = System.nanoTime();
        try {
            // No state transition: one authoritative DB read must not queue behind room writers.
            var authorizations = jdbc.query("""
                    SELECT m.auth_session_id,c.mode,c.epoch,
                        (t.state='ACTIVE' AND t.epoch=v.epoch AND t.lease_until>UTC_TIMESTAMP(6)) AS owner_live,
                        (u.deleted_at IS NULL) AS user_active,
                        EXISTS(SELECT 1 FROM chat_bans b WHERE b.user_id=v.user_id AND b.deleted_at IS NULL
                            AND b.created_at<=UTC_TIMESTAMP(6) AND b.expires_at>UTC_TIMESTAMP(6)) AS banned
                    FROM chat_view_runtime v JOIN chat_view_sessions s ON s.id=v.view_id
                    JOIN chat_room_members m ON m.id=v.membership_id AND m.user_id=v.user_id AND m.room_id=v.room_id
                    JOIN users u ON u.id=v.user_id
                    JOIN chat_runtime_control c ON c.environment=v.environment
                    LEFT JOIN chat_runtime_tasks t ON t.environment=v.environment AND t.task_id=v.owner_task_id
                    WHERE v.environment=? AND v.user_id=? AND v.room_id=? AND v.membership_id=?
                        AND v.owner_task_id=? AND v.socket_token=? AND v.epoch=? AND v.owner_generation=?
                        AND m.deleted_at IS NULL AND s.ended_at IS NULL
                        AND (v.state='CONNECTED' OR (v.state='RESERVED' AND v.deadline>UTC_TIMESTAMP(6)))
                    """, (row, index) -> new SocketAuthorization(row.getString(1), row.getString(2), row.getLong(3),
                    row.getBoolean(4), row.getBoolean(5), row.getBoolean(6)),
                    environment, user, room, member, taskId, socket, epoch, claimGeneration(socket));
            if (authorizations.size() != 1) {
                throw denied();
            }
            var authorization = authorizations.getFirst();
            if (!available() || !"RUNNING".equals(authorization.mode()) || authorization.epoch() != epoch
                    || !authorization.ownerLive()) {
                throw new ChatParticipationUnavailableException();
            }
            if (!authorization.userActive()) {
                throw error(ChatRoomErrorCode.USER_NOT_FOUND);
            }
            if (authorization.banned()) {
                throw error(ChatRoomErrorCode.CHAT_BANNED);
            }
            access.authorize(user, authorization.sid());
            ChatAuthOperation.check();
        } catch (ChatRoomException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            diagnostics.transactionFailed("socket_authorization", unavailable, started);
            throw new ChatParticipationUnavailableException();
        }
    }

    private record SocketAuthorization(String sid, String mode, long epoch, boolean ownerLive,
            boolean userActive, boolean banned) {
    }

    public record ReadContext(String environment, long epoch, long generation, long roomSeq,
            LocalDateTime asOf) {
    }

    public <T> T read(long user, long room, long member, String view, Function<ReadContext, T> operation) {
        return execute(user, tx -> read(tx, user, room, member, view, operation));
    }

    private <T> T read(Transition tx, long user, long room, long member, String view,
            Function<ReadContext, T> operation) {
        tx.lock(List.of(room));
        RuntimeView current = owned(user, room, member, view);
        if (!current.state.equals("CONNECTED") || current.owner == null || !ownerLive(current.owner)
                || current.generation < 1 || jdbc.queryForObject("""
                        SELECT COUNT(*) FROM chat_view_sessions WHERE id=? AND ended_at IS NULL
                            AND updated_at>TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6))
                        """, Integer.class, view) != 1) {
            throw denied();
        }
        authorizeUser(user, false);
        access.authorize(user, sid(member));
        T result = operation.apply(new ReadContext(environment, epoch, current.generation, tx.version(room), now()));
        if (!ownerLive(current.owner)) {
            throw denied();
        }
        return result;
    }

    /** REST music operations authorize the current connected view, including a remote Task owner. */
    public <T> T musicTransaction(long user, String authSid, long room, long member, String view,
            Function<MusicTransaction, T> operation) {
        return execute(user, tx -> read(tx, user, room, member, view, context -> {
            if (!authSid.equals(sid(member)) || jdbc.queryForObject("""
                    SELECT COUNT(*) FROM chat_view_sessions v JOIN chat_rooms r ON r.id=v.room_id
                    JOIN regions g ON g.id=r.region_id JOIN regions p ON p.id=g.parent_id
                    JOIN chat_room_members m ON m.id=v.membership_id
                    WHERE v.id=? AND m.auth_session_id=? AND r.status='ACTIVE'
                      AND g.is_active=TRUE AND p.is_active=TRUE
                    """, Integer.class, view, authSid) != 1) {
                throw denied();
            }
            return operation.apply(new MusicTransaction(context, room, tx));
        }));
    }

    private volatile java.util.function.Consumer<MusicTransaction> musicPresenceHook;

    public void setMusicPresenceHook(java.util.function.Consumer<MusicTransaction> hook) {
        musicPresenceHook = Objects.requireNonNull(hook);
    }

    /** Internal scheduler only; still pins control, epoch, Task lease and room before music locks. */
    public <T> T systemMusicTransaction(long room, Function<MusicTransaction, T> operation) {
        return execute(null, tx -> {
            tx.lock(List.of(room));
            return operation.apply(new MusicTransaction(
                    new ReadContext(environment, epoch, 0, tx.version(room), now()), room, tx));
        });
    }

    public final class MusicTransaction {
        private final ReadContext context;
        private final long room;
        private final Transition transition;

        private MusicTransaction(ReadContext context, long room, Transition transition) {
            this.context = context;
            this.room = room;
            this.transition = transition;
        }

        public ReadContext context() {
            return context;
        }

        public long roomId() {
            return room;
        }

        public String producerId() {
            return "chat:" + taskId;
        }

        public long changed() {
            if (context.roomSeq() >= 9_007_199_254_740_991L) {
                throw new ChatMusicException(409, "MUSIC_COUNTER_EXHAUSTED");
            }
            transition.changed(room);
            return transition.version(room);
        }
    }

    public ChatMessageResponse writeMessage(long user, long room, long member,
            String socket, Supplier<ChatMessageResponse> write) {
        return messageTransaction(user, room, member, socket, operation -> operation.save(write));
    }

    public <T> T messageTransaction(long user, long room, long member, String socket,
            Function<MessageTransaction, T> operation) {
        return execute(user, tx -> {
            tx.lock(List.of(room));
            List<String> found = jdbc.queryForList("""
                    SELECT view_id FROM chat_view_runtime WHERE environment=? AND epoch=? AND user_id=?
                        AND room_id=? AND membership_id=? AND owner_task_id=? AND socket_token=?
                    """, String.class, environment, epoch, user, room, member, taskId, socket);
            if (found.size() != 1) {
                throw denied();
            }
            RuntimeView current = owned(user, room, member, found.getFirst());
            requireOwner(current, socket);
            if (!current.state.equals("CONNECTED")) {
                throw denied();
            }
            authorizeUser(user, false);
            access.authorize(user, sid(member));
            return operation.apply(new MessageTransaction(tx, room));
        });
    }

    public final class MessageTransaction {
        private final Transition transition;
        private final long room;

        private MessageTransaction(Transition transition, long room) {
            this.transition = transition;
            this.room = room;
        }

        public ChatMessageResponse save(Supplier<ChatMessageResponse> write) {
            transition.changed(room);
            var message = write.get();
            jdbc.update("UPDATE chat_room_messages SET created_at=UTC_TIMESTAMP(6) WHERE id=?", message.messageId());
            boundaries.recordMessage(message.messageId(), room, epoch);
            LocalDateTime savedAt = jdbc.queryForObject("SELECT created_at FROM chat_room_messages WHERE id=?",
                    LocalDateTime.class, message.messageId());
            return new ChatMessageResponse(message.messageId(),
                    message.clientMessageId(), message.roomId(), message.userId(), message.nickname(),
                    message.content(),
                    savedAt.atOffset(ZoneOffset.UTC));
        }
    }

    /** One primary DB query; the actual outbound handler always rechecks current DB authority. */
    public Set<String> messageRecipients(long room, long message) {
        return messageRecipientPermissions(room, message).keySet();
    }

    /** The broker may defer enqueue to another thread; the final handler must still read current DB authority. */
    public MessageEnqueuePermissions messageEnqueuePermissions(long room, long message) {
        return new MessageEnqueuePermissions(this, room, message, epoch, messageRecipientPermissions(room, message));
    }

    public boolean permitsMessageEnqueue(long user, String socket, long room, long message, Object evidence) {
        if (evidence == null) {
            return permitsMessage(user, socket, room, message);
        }
        if (!(evidence instanceof MessageEnqueuePermissions permissions) || permissions.issuer != this
                || permissions.room != room || permissions.message != message || permissions.epoch != epoch) {
            return false;
        }
        var recipient = permissions.recipients.get(socket);
        return available() && recipient != null && recipient.user() == user
                && claimGeneration(socket) == recipient.generation();
    }

    private Map<String, RecipientPermission> messageRecipientPermissions(long room, long message) {
        if (!available()) {
            throw new ChatParticipationUnavailableException();
        }
        List<Map<String, Object>> eligible = measured(ChatDeliveryMetrics.Stage.RECIPIENT_LOOKUP_DB,
                () -> jdbc.queryForList("SELECT v.socket_token,v.owner_generation,v.user_id "
                        + VIEW_JOINS
                        + """
                                JOIN chat_runtime_control c ON c.environment=v.environment
                                JOIN users u ON u.id=v.user_id AND u.deleted_at IS NULL
                                JOIN chat_message_order o ON o.environment=v.environment
                                    AND o.room_id=v.room_id AND o.message_id=?
                                JOIN chat_room_messages msg ON msg.id=o.message_id AND msg.room_id=v.room_id
                                JOIN chat_view_intervals i ON i.view_session_id=v.view_id AND i.ends_at IS NULL
                                    AND i.owner_task_id=v.owner_task_id AND i.owner_generation=v.owner_generation
                                WHERE v.environment=? AND v.room_id=? AND v.owner_task_id=? AND v.state='CONNECTED'
                                    AND m.user_id=v.user_id AND m.room_id=v.room_id
                                    AND c.epoch=v.epoch AND c.mode='RUNNING' AND o.room_seq>i.start_after_room_seq
                                    AND msg.created_at>=i.started_at
                                    AND msg.created_at>TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6))
                                    AND NOT EXISTS(SELECT 1 FROM chat_bans b WHERE b.user_id=v.user_id
                                        AND b.deleted_at IS NULL AND b.created_at<=UTC_TIMESTAMP(6)
                                        AND b.expires_at>UTC_TIMESTAMP(6)) AND
                                """
                        + EFFECTIVE + " GROUP BY v.socket_token,v.owner_generation,v.user_id HAVING COUNT(*)=1",
                        message, environment, room, taskId, epoch));
        Map<String, RecipientPermission> result = new HashMap<>();
        eligible.forEach(row -> {
            String socket = (String) row.get("socket_token");
            if (claimGeneration(socket) == ((Number) row.get("owner_generation")).longValue()) {
                result.put(socket, new RecipientPermission(((Number) row.get("user_id")).longValue(),
                        ((Number) row.get("owner_generation")).longValue()));
            }
        });
        if (!available()) {
            throw new ChatParticipationUnavailableException();
        }
        return Map.copyOf(result);
    }

    public static final class SocketEnqueuePermissions {
        private final ChatParticipationService issuer;
        private final long epoch;
        private final long room;
        private final Map<String, RecipientPermission> recipients;

        private SocketEnqueuePermissions(ChatParticipationService issuer, long room,
                Map<String, RecipientPermission> recipients) {
            this.issuer = issuer;
            this.epoch = issuer.epoch;
            this.room = room;
            this.recipients = Map.copyOf(recipients);
        }
    }

    public SocketEnqueuePermissions issueSocketEnqueuePermissions(long room) {
        return issueSocketEnqueuePermissions(room, false);
    }

    public SocketEnqueuePermissions issueMusicEnqueuePermissions(long room) {
        return issueSocketEnqueuePermissions(room, true);
    }

    private SocketEnqueuePermissions issueSocketEnqueuePermissions(long room, boolean music) {
        if (!available()) {
            throw new ChatParticipationUnavailableException();
        }
        var rows = measured(ChatDeliveryMetrics.Stage.PRESENCE_RECIPIENT_LOOKUP_DB,
                () -> jdbc.queryForList("SELECT v.socket_token,v.owner_generation,v.user_id " + VIEW_JOINS + """
                        JOIN users u ON u.id=v.user_id
                        JOIN chat_runtime_control c ON c.environment=v.environment
                        WHERE v.environment=? AND v.room_id=? AND v.owner_task_id=?
                            AND m.user_id=v.user_id AND m.room_id=v.room_id
                            AND u.deleted_at IS NULL AND c.epoch=v.epoch AND c.mode='RUNNING'
                            AND NOT EXISTS(SELECT 1 FROM chat_bans b WHERE b.user_id=v.user_id
                                AND b.deleted_at IS NULL AND b.created_at<=UTC_TIMESTAMP(6)
                                AND b.expires_at>UTC_TIMESTAMP(6)) AND
                        """ + EFFECTIVE + (music ? " AND v.state='CONNECTED'" : "")
                        + " GROUP BY v.socket_token,v.owner_generation,v.user_id HAVING COUNT(*)=1",
                        environment, room, taskId, epoch));
        var permitted = new HashMap<String, RecipientPermission>();
        rows.forEach(row -> {
            String socket = (String) row.get("socket_token");
            long generation = ((Number) row.get("owner_generation")).longValue();
            if (socket != null && claimGeneration(socket) == generation) {
                permitted.put(socket, new RecipientPermission(((Number) row.get("user_id")).longValue(), generation));
            }
        });
        if (!available()) {
            throw new ChatParticipationUnavailableException();
        }
        return new SocketEnqueuePermissions(this, room, permitted);
    }

    public boolean permitsSocketEnqueue(long user, String socket, long room, Object evidence) {
        if (evidence == null) {
            return permitsSocket(user, socket, room);
        }
        if (!(evidence instanceof SocketEnqueuePermissions proof) || proof.issuer != this
                || proof.epoch != epoch || proof.room != room) {
            return false;
        }
        var recipient = proof.recipients.get(socket);
        return available() && recipient != null && recipient.user() == user
                && claimGeneration(socket) == recipient.generation();
    }

    public record MessagePermission(long user, String socket, long room, long message) {
    }

    /** Fresh primary read for requests already waiting at actual handlers; no previous result is reused. */
    public List<Boolean> permitsMessageBatch(List<MessagePermission> requests) {
        return permitsDeliveryBatch(requests, true);
    }

    public List<Boolean> permitsSocketBatch(List<MessagePermission> requests) {
        return permitsDeliveryBatch(requests, false);
    }

    /** Fresh primary read for requests already waiting at actual handlers; no previous result is reused. */
    private List<Boolean> permitsDeliveryBatch(List<MessagePermission> requests, boolean messages) {
        return permitsDeliveryBatch(requests, messages, false);
    }

    public List<Boolean> permitsMusicBatch(List<MessagePermission> requests) {
        return permitsDeliveryBatch(requests, false, true);
    }

    private List<Boolean> permitsDeliveryBatch(List<MessagePermission> requests, boolean messages, boolean music) {
        if (requests.size() > 32) {
            throw new IllegalArgumentException("At most 32 current delivery requests per DB read");
        }
        var results = new ArrayList<>(java.util.Collections.nCopies(requests.size(), Boolean.FALSE));
        if (!available() || requests.isEmpty()) {
            return List.copyOf(results);
        }
        var snapshots = new HashMap<Integer, LocalClaim>();
        var rows = new ArrayList<String>();
        var args = new ArrayList<Object>();
        for (int index = 0; index < requests.size(); index++) {
            var request = requests.get(index);
            var claim = request.socket() == null ? null : claims.get(request.socket());
            if (claim == null || messages && request.message() <= 0) {
                continue;
            }
            snapshots.put(index, claim);
            String view = "CAST(? AS CHAR(36) CHARACTER SET ascii) COLLATE ascii_bin";
            rows.add(rows.isEmpty()
                    ? "SELECT ? AS slot," + view + " AS view_id,? AS user_id,? AS room_id,"
                            + "? AS socket_token,? AS generation,? AS message_id"
                    : "SELECT ?," + view + ",?,?,?,?,?");
            args.addAll(List.of(index, claim.view(), request.user(), request.room(), request.socket(),
                    claim.generation(), request.message()));
        }
        if (rows.isEmpty()) {
            return List.copyOf(results);
        }
        // Six SQL shapes (1/2/4/8/16/32), rather than one prepared statement per exact batch size.
        // NULL view keys never join a real authority row; padding cannot create a granted recipient.
        int bucket = Integer.highestOneBit(rows.size() - 1) << 1;
        while (rows.size() < bucket) {
            rows.add("SELECT ?,CAST(? AS CHAR(36) CHARACTER SET ascii) COLLATE ascii_bin,?,?,?,?,?");
            args.addAll(java.util.Arrays.asList(-1, null, 0L, 0L, "", 0L, 0L));
        }
        args.addAll(List.of(environment, taskId, epoch));
        String messageJoins = messages ? """
                JOIN chat_message_order o ON o.environment=v.environment AND o.room_id=v.room_id
                    AND o.message_id=q.message_id
                JOIN chat_room_messages msg ON msg.id=o.message_id AND msg.room_id=v.room_id
                JOIN chat_view_intervals i ON i.view_session_id=v.view_id AND i.ends_at IS NULL
                    AND i.owner_task_id=v.owner_task_id AND i.owner_generation=v.owner_generation
                """ : "";
        String messageBoundary = messages ? """
                AND v.state='CONNECTED' AND o.room_seq>i.start_after_room_seq AND msg.created_at>=i.started_at
                AND msg.created_at>TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6))
                """ : "";
        // Start from the bounded request table; never scan a room to authorize a handler batch.
        String query = "SELECT STRAIGHT_JOIN q.slot FROM (" + String.join(" UNION ALL ", rows) + ") q "
                + """
                        JOIN chat_view_runtime v FORCE INDEX (PRIMARY) ON v.view_id=q.view_id
                        JOIN chat_view_sessions s ON s.id=v.view_id
                        JOIN chat_room_members m ON m.id=v.membership_id
                        JOIN users u ON u.id=v.user_id
                        JOIN chat_runtime_control c ON c.environment=v.environment
                        JOIN chat_runtime_tasks t ON t.environment=v.environment AND t.task_id=v.owner_task_id
                        """ + messageJoins + """
                        WHERE v.environment=? AND v.owner_task_id=? AND v.epoch=?
                            AND v.user_id=q.user_id AND v.room_id=q.room_id
                            AND v.socket_token=CAST(q.socket_token AS BINARY) AND v.owner_generation=q.generation
                            AND m.user_id=v.user_id AND m.room_id=v.room_id
                            AND m.deleted_at IS NULL AND s.ended_at IS NULL AND u.deleted_at IS NULL
                            AND c.epoch=v.epoch AND c.mode='RUNNING'
                            AND t.state='ACTIVE' AND t.lease_until>UTC_TIMESTAMP(6)
                            AND ((v.state IN ('RESERVED','GRACE') AND v.deadline>UTC_TIMESTAMP(6))
                                OR (v.state='CONNECTED' AND t.epoch=v.epoch))
                            AND NOT EXISTS(SELECT 1 FROM chat_bans b WHERE b.user_id=v.user_id
                                AND b.deleted_at IS NULL AND b.created_at<=UTC_TIMESTAMP(6)
                                AND b.expires_at>UTC_TIMESTAMP(6))
                        """ + messageBoundary + (music ? " AND v.state='CONNECTED'" : "")
                + " GROUP BY q.slot HAVING COUNT(*)=1";
        var permitted = measured(
                messages
                        ? ChatDeliveryMetrics.Stage.MESSAGE_PERMISSION_BATCH_DB
                        : ChatDeliveryMetrics.Stage.PRESENCE_PERMISSION_BATCH_DB,
                () -> jdbc.queryForList(query, Integer.class, args.toArray()));
        if (available()) {
            for (int index : permitted) {
                var request = requests.get(index);
                if (snapshots.get(index).equals(claims.get(request.socket()))) {
                    results.set(index, Boolean.TRUE);
                }
            }
        }
        return List.copyOf(results);
    }

    public boolean permitsMessage(long user, String socket, long room, long message) {
        return permitsDelivery(user, socket, room, message);
    }

    public boolean permitsSocket(long user, String socket, long room) {
        return permitsDelivery(user, socket, room, null);
    }

    private boolean permitsDelivery(long user, String socket, long room, Long message) {
        var claim = claims.get(socket);
        if (claim == null || !available()) {
            return false;
        }
        String messageJoins = message == null ? "" : """
                JOIN chat_message_order o ON o.environment=v.environment AND o.room_id=v.room_id AND o.message_id=?
                JOIN chat_room_messages msg ON msg.id=o.message_id AND msg.room_id=v.room_id
                JOIN chat_view_intervals i ON i.view_session_id=v.view_id AND i.ends_at IS NULL
                    AND i.owner_task_id=v.owner_task_id AND i.owner_generation=v.owner_generation
                """;
        String messageBoundary = message == null ? "" : """
                AND v.state='CONNECTED' AND o.room_seq>i.start_after_room_seq
                AND msg.created_at>=i.started_at
                                    AND msg.created_at>TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6))
                """;
        List<Object> arguments = new ArrayList<>();
        if (message != null) {
            arguments.add(message);
        }
        arguments.addAll(List.of(claim.view(), environment, user, room, socket, taskId, claim.generation(), epoch));
        Integer count = measured(message == null
                ? ChatDeliveryMetrics.Stage.PRESENCE_PERMISSION_DB
                : ChatDeliveryMetrics.Stage.MESSAGE_PERMISSION_DB,
                () -> jdbc.queryForObject("SELECT COUNT(*) " + VIEW_JOINS + messageJoins
                        + """
                                JOIN users u ON u.id=v.user_id
                                JOIN chat_runtime_control c ON c.environment=v.environment
                                WHERE v.view_id=? AND v.environment=? AND v.user_id=? AND v.room_id=?
                                    AND v.socket_token=?
                                    AND v.owner_task_id=? AND v.owner_generation=?
                                    AND m.user_id=v.user_id AND m.room_id=v.room_id
                                    AND c.epoch=v.epoch AND c.mode='RUNNING' AND u.deleted_at IS NULL
                                    AND NOT EXISTS(SELECT 1 FROM chat_bans b WHERE b.user_id=v.user_id
                                        AND b.deleted_at IS NULL AND b.created_at<=UTC_TIMESTAMP(6)
                                        AND b.expires_at>UTC_TIMESTAMP(6))
                                    AND
                                """
                        + EFFECTIVE + messageBoundary, Integer.class, arguments.toArray()));
        return count != null && count == 1 && claim.equals(claims.get(socket)) && available();
    }

    /** Local transport guard: no cache, replica read or Redis snapshot can extend the DB lease. */
    public boolean socketValid(long user, long member, String socket) {
        var claim = claims.get(socket);
        if (claim == null || !available()) {
            return false;
        }
        long started = System.nanoTime();
        try {
            Integer count = measured(ChatDeliveryMetrics.Stage.SOCKET_VALIDATION_DB,
                    () -> jdbc.queryForObject(
                            "SELECT COUNT(*) " + VIEW_JOINS
                                    + """
                                            JOIN chat_runtime_control c ON c.environment=v.environment
                                            WHERE v.view_id=? AND v.environment=? AND v.user_id=? AND v.membership_id=?
                                              AND v.owner_task_id=? AND v.socket_token=? AND v.owner_generation=?
                                              AND c.epoch=v.epoch AND c.mode='RUNNING' AND
                                            """
                                    + EFFECTIVE,
                            Integer.class, claim.view(), environment, user, member, taskId, socket,
                            claim.generation(), epoch));
            return count != null && count == 1 && claim.equals(claims.get(socket)) && available();
        } catch (RuntimeException unavailable) {
            failClosed("socket_validation", unavailable, started);
            return false;
        }
    }

    public int connectedCount(long room) {
        if (!available()) {
            return 0;
        }
        long started = System.nanoTime();
        try {
            return snapshot(room).connected();
        } catch (RuntimeException unavailable) {
            failClosed("connected_count", unavailable, started);
            return 0;
        }
    }

    public void heartbeat() {
        if (draining) {
            return;
        }
        if (!available()) {
            failClosed("heartbeat_unavailable", null, System.nanoTime());
            return;
        }
        long started = System.nanoTime();
        try {
            transactions.executeWithoutResult(status -> {
                control();
                int updated = jdbc.update("""
                        UPDATE chat_runtime_tasks SET lease_until=TIMESTAMPADD(SECOND,15,UTC_TIMESTAMP(6))
                        WHERE environment=? AND task_id=? AND epoch=? AND state='ACTIVE'
                          AND lease_until>UTC_TIMESTAMP(6)
                        """, environment, taskId, epoch);
                if (updated != 1) {
                    throw new ChatParticipationUnavailableException();
                }
            });
            localLease = started + TimeUnit.SECONDS.toNanos(14);
        } catch (RuntimeException unavailable) {
            failClosed("heartbeat", unavailable, started);
        }
    }

    /** Lazy count filtering is authoritative even if physical OFFLINE cleanup is delayed. */
    public Set<String> validSockets() {
        long started = System.nanoTime();
        if (!available() || jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_runtime_control c JOIN chat_runtime_tasks t
                    ON t.environment=c.environment AND t.epoch=c.epoch
                WHERE c.environment=? AND c.epoch=? AND c.mode='RUNNING' AND t.task_id=?
                    AND t.state='ACTIVE' AND t.lease_until>UTC_TIMESTAMP(6)
                """, Integer.class, environment, epoch, taskId) != 1) {
            failClosed("valid_sockets_guard", null, started);
            throw new ChatParticipationUnavailableException();
        }
        return Set.copyOf(jdbc.queryForList("SELECT v.socket_token " + VIEW_JOINS
                + " WHERE v.environment=? AND v.owner_task_id=? AND " + EFFECTIVE,
                String.class, environment, taskId, epoch));
    }

    public void sweep() {
        if (!available()) {
            return;
        }
        long started = System.nanoTime();
        try {
            transactions.executeWithoutResult(status -> {
                control();
                // Expired leases already deny authority. Defer busy physical fencing rather than
                // blocking a healthy peer behind another task's still-running transaction.
                List<String> expiredTasks = jdbc.queryForList("""
                        SELECT task_id FROM chat_runtime_tasks WHERE environment=? AND state='ACTIVE'
                            AND lease_until<=UTC_TIMESTAMP(6) ORDER BY task_id FOR UPDATE SKIP LOCKED
                        """, String.class, environment);
                expiredTasks.forEach(id -> jdbc.update("""
                        UPDATE chat_runtime_tasks SET state='FENCED' WHERE environment=? AND task_id=?
                          AND lease_until<=UTC_TIMESTAMP(6)
                        """, environment, id));
            });
            List<Long> users = jdbc.queryForList("SELECT DISTINCT v.user_id " + VIEW_JOINS + """
                    WHERE v.environment=? AND v.state NOT IN ('LEFT','OFFLINE') AND
                      (v.epoch<>? OR (v.deadline IS NOT NULL AND v.deadline<=UTC_TIMESTAMP(6))
                       OR (v.owner_task_id IS NOT NULL AND
                           (t.state<>'ACTIVE' OR t.lease_until<=UTC_TIMESTAMP(6)))) LIMIT 100
                    """, Long.class, environment, epoch);
            for (long user : users) {
                execute(user, tx -> {
                    List<Long> rooms = jdbc.queryForList("""
                            SELECT DISTINCT room_id FROM chat_view_runtime WHERE environment=? AND user_id=?
                              AND state NOT IN ('LEFT','OFFLINE')
                            """, Long.class, environment, user);
                    tx.lock(rooms);
                    List<String> expired = jdbc.queryForList("SELECT v.view_id " + VIEW_JOINS + """
                            WHERE v.environment=? AND v.user_id=? AND v.state NOT IN ('LEFT','OFFLINE') AND
                              (v.epoch<>? OR (v.deadline IS NOT NULL AND v.deadline<=UTC_TIMESTAMP(6))
                               OR (v.owner_task_id IS NOT NULL AND
                                   (t.state<>'ACTIVE' OR t.lease_until<=UTC_TIMESTAMP(6))))
                            """, String.class, environment, user, epoch);
                    for (String view : expired) {
                        // Missing disconnect evidence never invents a new 30 second reading interval.
                        jdbc.update(
                                """
                                        UPDATE chat_view_intervals SET ends_at=verified_at,
                                            end_message_id=verified_through_message_id,
                                            end_room_seq=verified_through_room_seq,end_reason='RESTART'
                                        WHERE view_session_id=? AND ends_at IS NULL
                                        """,
                                view);
                        jdbc.update("""
                                UPDATE chat_view_runtime SET state='OFFLINE',deadline=NULL,owner_task_id=NULL,
                                    socket_token=NULL,owner_generation=owner_generation+1 WHERE view_id=?
                                """, view);
                    }
                    if (!expired.isEmpty()) {
                        rooms.forEach(tx::presenceChanged);
                        for (String view : expired) {
                            long room = jdbc.queryForObject("SELECT room_id FROM chat_view_runtime WHERE view_id=?",
                                    Long.class, view);
                            mark(view, tx.version(room));
                        }
                    }
                    return null;
                });
            }
        } catch (RuntimeException unavailable) {
            failClosed("expired_participation_sweep", unavailable, started);
        }
    }

    public void refreshProjections() {
        if (available()) {
            touchedRooms.forEach(this::project);
        }
    }

    private <T> T execute(Long user, Function<Transition, T> action) {
        if (!available()) {
            throw new ChatParticipationUnavailableException();
        }
        var tx = new Transition();
        T result;
        boolean admitted = false;
        try {
            tx.phase("local_transition_admission");
            long admissionStarted = System.nanoTime();
            try {
                admitted = transitionAdmission.tryAcquire(1, TimeUnit.SECONDS);
            } finally {
                ChatDeliveryMetrics.record(deliveryMetrics, ChatDeliveryMetrics.Stage.TRANSITION_ADMISSION,
                        admissionStarted);
            }
            if (!admitted) {
                diagnostics.admissionBlocked(activeTransitions.keySet().stream().limit(2)
                        .map(active -> active.phase).toList());
                throw new ChatParticipationUnavailableException();
            }
            activeTransitions.put(tx, Boolean.TRUE);
            tx.phase("transaction_start");
            result = measured(ChatDeliveryMetrics.Stage.TRANSITION_TRANSACTION, () -> transactions.execute(status -> {
                tx.phase("transaction_configuration");
                jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                tx.phase("control_lock");
                control();
                // Small P1 task set: ordered SHARE locks also pin any old owner lease during a transition.
                tx.phase("task_locks");
                jdbc.queryForList("""
                        SELECT task_id FROM chat_runtime_tasks WHERE environment=? ORDER BY task_id FOR SHARE
                        """, String.class, environment);
                tx.phase("task_guard");
                requireTask();
                tx.phase("user_lock");
                if (user != null) {
                    List<Long> users = jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class,
                            user);
                    if (users.isEmpty()) {
                        throw error(ChatRoomErrorCode.USER_NOT_FOUND);
                    }
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        tx.phase("commit_budget");
                        if (System.nanoTime() - tx.started >= TimeUnit.SECONDS.toNanos(2)) {
                            throw new ChatParticipationUnavailableException();
                        }
                        tx.phase("commit_task_guard");
                        requireTask();
                        tx.phase("commit_auth_guard");
                        ChatAuthOperation.check();
                        tx.phase("commit_boundary");
                    }

                    @Override
                    public void afterCommit() {
                        tx.phase("committed");
                    }
                });
                tx.phase("transition_action");
                T applied = action.apply(tx);
                tx.phase("control_outbox");
                for (long room : tx.presence) {
                    var hook = musicPresenceHook;
                    if (hook != null) {
                        hook.accept(new MusicTransaction(
                                new ReadContext(environment, epoch, 0, tx.version(room), now()), room, tx));
                    }
                    tx.projectionVersions.put(room, tx.version(room));
                    recordControl("CHAT_PRESENCE_CHANGED", room, tx.version(room),
                            Map.of("room_id", room, "room_seq", tx.version(room)));
                }
                return applied;
            }));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            diagnostics.transactionFailed(tx.phase.stage(), interrupted, tx.started);
            throw new ChatParticipationUnavailableException();
        } catch (ChatRoomException | ChatQueryException | ChatMessageRejection
                | ChatMusicException rejected) {
            throw rejected;
        } catch (ChatParticipationUnavailableException rejected) {
            diagnostics.transactionFailed(tx.phase.stage(), rejected, tx.started);
            throw rejected;
        } catch (RuntimeException unavailable) {
            diagnostics.transactionFailed(tx.phase.stage(), unavailable, tx.started);
            throw new ChatParticipationUnavailableException();
        } finally {
            if (admitted) {
                activeTransitions.remove(tx);
                transitionAdmission.release();
            }
        }
        // Release admission before projection/transport callbacks, which may take local presence locks.
        // Message ordering/checkpoints advance room_seq without changing participation or presence.
        tx.presence.forEach(room -> project(room, tx.projectionVersions.get(room)));
        return result;
    }

    private Map<String, Object> control() {
        Map<String, Object> control = jdbc.queryForMap("""
                SELECT epoch,mode FROM chat_runtime_control WHERE environment=? FOR SHARE
                """, environment);
        if (!"RUNNING".equals(control.get("mode"))
                || epoch != 0 && epoch != ((Number) control.get("epoch")).longValue()) {
            throw new ChatParticipationUnavailableException();
        }
        return control;
    }

    private void requireTask() {
        if (!available() || !ownerLive(taskId)) {
            throw new ChatParticipationUnavailableException();
        }
    }

    private boolean ownerLive(String owner) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_runtime_tasks WHERE environment=? AND task_id=? AND epoch=?
                  AND state='ACTIVE' AND lease_until>UTC_TIMESTAMP(6)
                """, Integer.class, environment, owner, epoch) == 1;
    }

    private void authorizeUser(long user, boolean allowInactive) {
        if (!allowInactive && jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND deleted_at IS NULL",
                Integer.class, user) != 1) {
            throw error(ChatRoomErrorCode.USER_NOT_FOUND);
        }
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_bans WHERE user_id=? AND deleted_at IS NULL
                  AND created_at<=UTC_TIMESTAMP(6) AND expires_at>UTC_TIMESTAMP(6)
                """, Integer.class, user) != 0) {
            throw error(ChatRoomErrorCode.CHAT_BANNED);
        }
    }

    private RuntimeView owned(long user, long room, long member, String view) {
        List<RuntimeView> found = jdbc.query("""
                SELECT v.state,v.deadline,v.owner_task_id,v.socket_token,v.owner_generation
                FROM chat_view_runtime v JOIN chat_view_sessions s ON s.id=v.view_id
                JOIN chat_room_members m ON m.id=v.membership_id
                WHERE v.environment=? AND v.epoch=? AND v.view_id=? AND v.user_id=? AND v.room_id=?
                  AND v.membership_id=?
                """, (r, i) -> new RuntimeView(r.getString(1), r.getTimestamp(2) == null
                ? null
                : r.getTimestamp(2).toLocalDateTime(), r.getString(3), r.getString(4), r.getLong(5)),
                environment, epoch, view, user, room, member);
        if (found.size() != 1) {
            throw denied();
        }
        return found.getFirst();
    }

    private void requireOwner(RuntimeView view, String socket) {
        if (!taskId.equals(view.owner) || !socket.equals(view.socket) || !ownerLive(taskId)
                || view.generation != claimGeneration(socket)
                || !(view.state.equals("CONNECTED") || view.state.equals("RESERVED"))) {
            throw denied();
        }
    }

    private String sid(long member) {
        Map<String, Object> found = jdbc.queryForMap("""
                SELECT m.auth_session_id,u.deleted_at AS user_deleted,m.deleted_at
                FROM chat_room_members m JOIN users u ON u.id=m.user_id WHERE m.id=?
                """, member);
        if (found.get("deleted_at") != null || found.get("user_deleted") != null) {
            throw denied();
        }
        return (String) found.get("auth_session_id");
    }

    private List<Member> members(long user, String sid, boolean activeOnly) {
        String query = "SELECT id,room_id,created_at FROM chat_room_members WHERE user_id=?";
        var args = new ArrayList<Object>();
        args.add(user);
        if (sid != null) {
            query += " AND auth_session_id=?";
            args.add(sid);
        }
        if (activeOnly) {
            query += " AND deleted_at IS NULL";
        }
        return jdbc.query(query, (r, i) -> new Member(r.getLong(1), r.getLong(2),
                r.getTimestamp(3).toLocalDateTime()), args.toArray());
    }

    private void endMembership(Transition tx, Member member, String reason) {
        var identity = jdbc.queryForMap("SELECT user_id,auth_session_id,deleted_at FROM chat_room_members WHERE id=?",
                member.id);
        if (identity.get("deleted_at") != null) {
            return;
        }
        tx.presenceChanged(member.room);
        jdbc.queryForList("SELECT id FROM chat_view_sessions WHERE membership_id=?", String.class, member.id)
                .forEach(view -> boundaries.close(view, member.room, "LEFT"));
        storage.endedShared(member.id);
        jdbc.update("UPDATE chat_room_members SET deleted_at=UTC_TIMESTAMP(6),end_reason=? WHERE id=?",
                reason, member.id);
        jdbc.update("""
                UPDATE chat_view_runtime SET state='LEFT',deadline=NULL,owner_task_id=NULL,socket_token=NULL,
                    owner_generation=owner_generation+1,end_reason=?
                WHERE membership_id=? AND environment=? AND state<>'LEFT'
                """, reason, member.id, environment);
        tx.presenceChanged(member.room);
        jdbc.update("UPDATE chat_view_runtime SET changed_seq=? WHERE membership_id=?", tx.version(member.room),
                member.id);
        terminalEvent(tx, "CHAT_MEMBERSHIP_ENDED", ((Number) identity.get("user_id")).longValue(),
                (String) identity.get("auth_session_id"), member.room, member.id, null, reason);
    }

    private void terminalEvent(Transition tx, String type, long user, String sid, long room, long member,
            String view, String reason) {
        var payload = new java.util.TreeMap<String, Object>();
        payload.put("user_id", user);
        payload.put("sid", sid);
        payload.put("room_id", room);
        payload.put("membership_id", member);
        payload.put("room_seq", tx.version(room));
        payload.put("reason", type.equals("CHAT_MEMBERSHIP_ENDED") && reason.equals("LEFT")
                ? "LAST_VIEW_LEFT"
                : reason);
        if (view != null) {
            payload.put("view_id", view);
        }
        recordControl(type, room, tx.version(room), payload);
    }

    private void recordControl(String type, long room, long seq, Map<String, Object> payload) {
        if (!controlEvents) {
            return;
        }
        LocalDateTime occurred = now();
        var event = new ChatControlEvent(UUID.randomUUID().toString(), type, "chat:" + taskId,
                occurred.toInstant(ZoneOffset.UTC), epoch, payload);
        ChatControlEvent.decode(event.encode());
        jdbc.update("""
                INSERT INTO chat_message_outbox(event_id,environment,epoch,producer_id,room_id,room_seq,event_type,
                    event_body,occurred_at,expires_at,next_attempt_at)
                VALUES (?,?,?,?,?,?,?,?,?,TIMESTAMPADD(HOUR,24,?),UTC_TIMESTAMP(6))
                """, event.eventId(), environment, epoch, event.producerId(), room, seq, type, event.encode(), occurred,
                occurred);
    }

    public long publishPresence(long room) {
        validSockets();
        var snapshot = snapshot(room);
        events.publishEvent(new ChatPresenceChangedEvent(room, snapshot.connected(), snapshot.version(), null, null));
        return snapshot.version();
    }

    private long createMembership(long user, String sid, long room, LocalDateTime now) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chat_room_members(user_id,room_id,auth_session_id,created_at) VALUES (?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, user);
            statement.setLong(2, room);
            statement.setString(3, sid);
            statement.setObject(4, now);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private boolean capacity(long user, long room, int capacity) {
        List<Long> accounts = jdbc.queryForList("SELECT DISTINCT v.user_id " + VIEW_JOINS
                + " WHERE v.environment=? AND v.room_id=? AND " + EFFECTIVE,
                Long.class, environment, room, epoch);
        return accounts.contains(user) || accounts.size() < capacity;
    }

    private int roomCapacity(long room) {
        return jdbc.queryForObject("SELECT capacity FROM chat_rooms WHERE id=? AND status='ACTIVE'", Integer.class,
                room);
    }

    private void mark(String view, long seq) {
        jdbc.update("UPDATE chat_view_runtime SET changed_seq=? WHERE view_id=?", seq, view);
    }

    private LocalDateTime now() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
    }

    public ChatParticipationProjection.Snapshot snapshot(long room) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.room_seq,v.view_id,v.changed_seq,v.user_id,v.membership_id,v.state,v.deadline,
                    v.owner_task_id,v.owner_generation,
                """ + " CASE WHEN " + EFFECTIVE + " THEN 1 ELSE 0 END AS effective " + """
                FROM chat_room_runtime r LEFT JOIN chat_view_runtime v
                  ON v.environment=r.environment AND v.room_id=r.room_id AND v.epoch=?
                LEFT JOIN chat_view_sessions s ON s.id=v.view_id
                LEFT JOIN chat_room_members m ON m.id=v.membership_id
                LEFT JOIN chat_runtime_tasks t ON t.environment=v.environment AND t.task_id=v.owner_task_id
                WHERE r.environment=? AND r.room_id=?
                """, epoch, epoch, environment, room);
        if (rows.isEmpty()) {
            return new ChatParticipationProjection.Snapshot(epoch, room, 0, 0, 0, List.of());
        }
        Set<Long> occupied = new HashSet<>();
        Set<Long> connected = new HashSet<>();
        List<ChatParticipationProjection.View> views = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get("view_id") == null) {
                continue;
            }
            long user = ((Number) row.get("user_id")).longValue();
            if (((Number) row.get("effective")).intValue() == 1) {
                occupied.add(user);
                if ("CONNECTED".equals(row.get("state"))) {
                    connected.add(user);
                }
            }
            views.add(new ChatParticipationProjection.View((String) row.get("view_id"),
                    ((Number) row.get("changed_seq")).longValue(), user,
                    ((Number) row.get("membership_id")).longValue(), (String) row.get("state"),
                    row.get("deadline") == null ? null : row.get("deadline").toString(),
                    (String) row.get("owner_task_id"), ((Number) row.get("owner_generation")).longValue()));
        }
        return new ChatParticipationProjection.Snapshot(epoch, room,
                ((Number) rows.getFirst().get("room_seq")).longValue(), occupied.size(), connected.size(), views);
    }

    private void project(long room) {
        project(room, 0);
    }

    private void project(long room, long minimumViewVersion) {
        long started = System.nanoTime();
        String stage = "projection_snapshot";
        try {
            touchedRooms.add(room);
            var snapshot = snapshot(room);
            stage = "redis_projection";
            // Counts stay authoritative for the entire room. A transition must not synchronously rewrite
            // every unchanged view while its caller holds the local connection lifecycle monitor.
            // Periodic refresh uses minimum version zero to repair missing projection keys.
            projection.write(new ChatParticipationProjection.Snapshot(snapshot.epoch(), snapshot.roomId(),
                    snapshot.version(), snapshot.occupied(), snapshot.connected(), snapshot.views().stream()
                            .filter(view -> view.version() >= minimumViewVersion).toList()));
            stage = "presence_event";
            if (!controlEvents) {
                // Distributed mode already publishes the committed outbox hint through the coalescing receiver.
                events.publishEvent(
                        new ChatPresenceChangedEvent(room, snapshot.connected(), snapshot.version(), null, null));
            }
        } catch (RuntimeException unavailable) {
            failClosed(stage, unavailable, started);
            throw new ChatParticipationUnavailableException();
        }
    }

    public void failClosed() {
        failClosed("external_guard", null, System.nanoTime());
    }

    public void failClosed(String stage, Throwable failure, long started) {
        failed = true;
        diagnostics.closed(stage, failure, started);
        sockets.closeAll(1013, "CHAT_SERVICE_UNAVAILABLE");
    }

    /** No fresh authority is manufactured during shutdown: only the last verified reading edge is retained. */
    public boolean releaseDrainingViews() {
        if (!draining) {
            throw new IllegalStateException("Chat drain must close ingress first");
        }
        return transactions.execute(status -> {
            jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
            control();
            jdbc.queryForList("SELECT task_id FROM chat_runtime_tasks WHERE environment=? ORDER BY task_id FOR SHARE",
                    String.class, environment);
            var users = jdbc.queryForList("""
                    SELECT DISTINCT user_id FROM chat_view_runtime WHERE environment=? AND owner_task_id=?
                    ORDER BY user_id LIMIT 25
                    """, Long.class, environment, taskId);
            for (long user : users) {
                jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, user);
                var rooms = jdbc.queryForList("""
                        SELECT DISTINCT room_id FROM chat_view_runtime WHERE environment=? AND owner_task_id=?
                            AND user_id=? ORDER BY room_id
                        """, Long.class, environment, taskId, user);
                for (long room : rooms) {
                    jdbc.queryForObject("""
                            SELECT room_seq FROM chat_room_runtime WHERE environment=? AND room_id=? FOR UPDATE
                            """, Long.class, environment, room);
                    // A stopping Task is not a normal client disconnect: no new 30-second reading allowance.
                    jdbc.update("""
                            UPDATE chat_view_intervals i JOIN chat_view_runtime v ON v.view_id=i.view_session_id
                            SET i.ends_at=i.verified_at,i.end_message_id=i.verified_through_message_id,
                                i.end_room_seq=i.verified_through_room_seq,i.end_reason='RESTART'
                            WHERE v.environment=? AND v.owner_task_id=? AND v.user_id=? AND v.room_id=?
                                AND i.ends_at IS NULL
                            """, environment, taskId, user, room);
                    jdbc.update("UPDATE chat_room_runtime SET room_seq=room_seq+1 WHERE environment=? AND room_id=?",
                            environment, room);
                    long version = jdbc.queryForObject(
                            "SELECT room_seq FROM chat_room_runtime WHERE environment=? AND room_id=?",
                            Long.class, environment, room);
                    jdbc.update("""
                            UPDATE chat_view_runtime SET state='OFFLINE',deadline=NULL,owner_task_id=NULL,
                                socket_token=NULL,owner_generation=owner_generation+1,changed_seq=?
                            WHERE environment=? AND owner_task_id=? AND user_id=? AND room_id=?
                            """, version, environment, taskId, user, room);
                    recordControl("CHAT_PRESENCE_CHANGED", room, version,
                            Map.of("room_id", room, "room_seq", version));
                }
            }
            return !users.isEmpty();
        });
    }

    public void finishDrain() {
        transactions.executeWithoutResult(status -> {
            control();
            jdbc.update("UPDATE chat_runtime_tasks SET state='FENCED' WHERE environment=? AND task_id=? AND epoch=?",
                    environment, taskId, epoch);
        });
        claims.clear();
    }

    private ChatRoomException denied() {
        return error(ChatRoomErrorCode.CHAT_VIEW_UNAVAILABLE);
    }

    private ChatRoomException error(ChatRoomErrorCode code) {
        return new ChatRoomException(code);
    }

    private record RuntimeView(String state, LocalDateTime deadline, String owner, String socket, long generation) {
    }

    private record Member(long id, long room, LocalDateTime created) {
    }

    private record Join(ChatRoomJoinResult result, boolean full) {
    }

    private final class Transition {
        private final long started = System.nanoTime();
        private volatile ChatParticipationDiagnostics.Phase phase = new ChatParticipationDiagnostics.Phase(
                "transaction_start", started, started);

        private void phase(String stage) {
            phase = new ChatParticipationDiagnostics.Phase(stage, System.nanoTime(), started);
        }
        private final Set<Long> changed = new LinkedHashSet<>();
        private final Set<Long> presence = new LinkedHashSet<>();
        private final Map<Long, Long> projectionVersions = new HashMap<>();

        private void presenceChanged(long room) {
            changed(room);
            presence.add(room);
        }

        private void lock(List<Long> rooms) {
            phase("room_lock_and_boundaries");
            measured(ChatDeliveryMetrics.Stage.ROOM_LOCK_AND_BOUNDARIES, () -> {
                rooms.stream().distinct().sorted().forEach(room -> {
                    phase("room_exists");
                    if (jdbc.queryForObject("SELECT COUNT(*) FROM chat_rooms WHERE id=?", Integer.class, room) != 1) {
                        throw error(ChatRoomErrorCode.CHAT_ROOM_NOT_FOUND);
                    }
                    // Take X immediately; IGNORE can deadlock during the S-to-X upgrade below.
                    phase("room_upsert");
                    measured(ChatDeliveryMetrics.Stage.ROOM_UPSERT_DB, () -> jdbc.update("""
                            INSERT INTO chat_room_runtime(environment,room_id) VALUES (?,?)
                            ON DUPLICATE KEY UPDATE room_id=room_id
                            """, environment, room));
                    // The upsert already holds the room X lock through commit; do not reacquire it.
                    phase("room_boundaries");
                    measured(ChatDeliveryMetrics.Stage.BOUNDARY_INIT_DB, () -> {
                        boundaries.initialize(room, epoch);
                        return null;
                    });
                });
                return null;
            });
            phase("transition_action");
        }

        private void changed(long room) {
            if (changed.add(room)) {
                jdbc.update("UPDATE chat_room_runtime SET room_seq=room_seq+1 WHERE environment=? AND room_id=?",
                        environment, room);
            }
        }

        private long version(long room) {
            return jdbc.queryForObject("SELECT room_seq FROM chat_room_runtime WHERE environment=? AND room_id=?",
                    Long.class, environment, room);
        }
    }
}
