package com.muse.meomuneum.chat.participation;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
import com.muse.meomuneum.chat.room.dto.ChatRoomMembershipResponse;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.service.ChatRoomJoinResult;
import com.muse.meomuneum.chat.view.ChatViewStorageService;
import com.muse.meomuneum.global.security.AuthSessionIds;
import com.muse.meomuneum.location.security.LocationResolutionClaims;

/** DB authority; JVM registries contain only local transports. No Redis/socket/Core I/O inside transactions. */
public final class ChatParticipationService {
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
    private final String taskId = UUID.randomUUID().toString();
    private final Set<Long> touchedRooms = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile long epoch;
    private volatile long localLease;
    private volatile boolean failed;

    public ChatParticipationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ChatViewStorageService storage, ChatSessionAccessPolicy access, ChatParticipationProjection projection,
            ApplicationEventPublisher events, ChatSocketSessions sockets, String environment) {
        if (environment == null || !environment.matches("[a-z0-9-]{1,32}")) {
            throw new IllegalArgumentException("chat participation environment required");
        }
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
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

    public boolean available() {
        return !failed && localLease - System.nanoTime() > 0;
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
                    endMembership(tx, member);
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
            tx.changed(room);
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
        if (!AuthSessionIds.isValid(view) || socket == null || socket.length() > 128) {
            throw denied();
        }
        execute(user, tx -> {
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
            tx.changed(room);
            mark(view, tx.version(room));
            return null;
        });
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
            storage.subscribedShared(user, room, member, view);
            jdbc.update("""
                    UPDATE chat_view_runtime SET state='CONNECTED',deadline=NULL
                    WHERE view_id=? AND owner_task_id=? AND socket_token=? AND owner_generation=?
                    """, view, taskId, socket, current.generation);
            tx.changed(room);
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
            storage.checkpointShared(view, room);
            return null;
        });
    }

    public void paused(long user, long room, long member, String view, String socket) {
        execute(user, tx -> {
            tx.lock(List.of(room));
            requireOwner(owned(user, room, member, view), socket);
            storage.unsubscribedShared(view, room);
            jdbc.update("""
                    UPDATE chat_view_runtime SET state='RESERVED',deadline=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6))
                    WHERE view_id=?
                    """, view);
            tx.changed(room);
            mark(view, tx.version(room));
            return null;
        });
    }

    public void disconnected(long user, long room, long member, String view, String socket) {
        execute(user, tx -> {
            tx.lock(List.of(room));
            RuntimeView current = owned(user, room, member, view);
            if (!taskId.equals(current.owner) || !socket.equals(current.socket)) {
                return null; // Delayed old socket callbacks cannot modify a new claim.
            }
            boolean connected = current.state.equals("CONNECTED");
            if (connected) {
                storage.disconnectedShared(view);
            }
            jdbc.update("""
                    UPDATE chat_view_runtime SET state=?,owner_task_id=NULL,socket_token=NULL,
                        deadline=CASE WHEN ? THEN TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) ELSE deadline END
                    WHERE view_id=? AND owner_task_id=? AND socket_token=? AND owner_generation=?
                    """, connected ? "GRACE" : "RESERVED", connected, view, taskId, socket, current.generation);
            tx.changed(room);
            mark(view, tx.version(room));
            return null;
        });
    }

    public boolean leave(long user, String sid, long room, long member, String view) {
        return execute(user, tx -> {
            tx.lock(List.of(room));
            owned(user, room, member, view);
            if (!sid.equals(jdbc.queryForObject("SELECT auth_session_id FROM chat_room_members WHERE id=?",
                    String.class, member))) {
                throw denied();
            }
            storage.leaveShared(user, room, member, view);
            jdbc.update("""
                    UPDATE chat_view_runtime SET state='LEFT',deadline=NULL,owner_task_id=NULL,socket_token=NULL,
                        owner_generation=owner_generation+1 WHERE view_id=? AND state<>'LEFT'
                    """, view);
            boolean last = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM chat_view_sessions WHERE membership_id=? AND ended_at IS NULL
                    """, Integer.class, member) == 0;
            if (last) {
                jdbc.update("UPDATE chat_room_members SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(6)) WHERE id=?",
                        member);
            }
            tx.changed(room);
            mark(view, tx.version(room));
            return last;
        });
    }

    public void end(long user, String sid, Long room, Long membership) {
        execute(user, tx -> {
            List<Member> selected = members(user, sid, membership == null).stream()
                    .filter(m -> membership == null || membership == m.id && room != null && room == m.room).toList();
            if (membership != null && selected.isEmpty()) {
                throw error(ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND);
            }
            tx.lock(selected.stream().map(Member::room).toList());
            selected.forEach(member -> endMembership(tx, member));
            return null;
        });
    }

    public void assertSocket(long user, long room, long member, String socket) {
        execute(user, tx -> {
            tx.lock(List.of(room));
            List<String> views = jdbc.queryForList("""
                    SELECT view_id FROM chat_view_runtime WHERE environment=? AND user_id=? AND room_id=?
                        AND membership_id=? AND owner_task_id=? AND socket_token=? AND epoch=?
                    """, String.class, environment, user, room, member, taskId, socket, epoch);
            if (views.size() != 1) {
                throw denied();
            }
            RuntimeView current = owned(user, room, member, views.getFirst());
            requireOwner(current, socket);
            if (current.state.equals("RESERVED") && !current.deadline.isAfter(now())) {
                throw denied();
            }
            authorizeUser(user, false);
            access.authorize(user, sid(member));
            return null;
        });
    }

    /** Local transport guard: no cache, replica read or Redis snapshot can extend the DB lease. */
    public boolean socketValid(long user, long member, String socket) {
        if (!available()) {
            return false;
        }
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) " + VIEW_JOINS + """
                    JOIN chat_runtime_control c ON c.environment=v.environment
                    WHERE v.environment=? AND v.user_id=? AND v.membership_id=? AND v.owner_task_id=?
                      AND v.socket_token=? AND c.epoch=v.epoch AND c.mode='RUNNING' AND
                    """ + EFFECTIVE, Integer.class, environment, user, member, taskId, socket, epoch);
            return count != null && count == 1;
        } catch (RuntimeException unavailable) {
            failClosed();
            return false;
        }
    }

    public int connectedCount(long room) {
        if (!available()) {
            return 0;
        }
        try {
            return snapshot(room).connected();
        } catch (RuntimeException unavailable) {
            failClosed();
            return 0;
        }
    }

    public void heartbeat() {
        if (!available()) {
            failClosed();
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
            failClosed();
        }
    }

    /** Lazy count filtering is authoritative even if physical OFFLINE cleanup is delayed. */
    public Set<String> validSockets() {
        if (!available() || jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_runtime_control c JOIN chat_runtime_tasks t
                    ON t.environment=c.environment AND t.epoch=c.epoch
                WHERE c.environment=? AND c.epoch=? AND c.mode='RUNNING' AND t.task_id=?
                    AND t.state='ACTIVE' AND t.lease_until>UTC_TIMESTAMP(6)
                """, Integer.class, environment, epoch, taskId) != 1) {
            failClosed();
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
        try {
            transactions.executeWithoutResult(status -> {
                control();
                List<String> expiredTasks = jdbc.queryForList("""
                        SELECT task_id FROM chat_runtime_tasks WHERE environment=? AND state='ACTIVE'
                            AND lease_until<=UTC_TIMESTAMP(6) ORDER BY task_id FOR UPDATE
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
                        jdbc.update("""
                                UPDATE chat_view_intervals SET ends_at=verified_at,
                                    end_message_id=verified_through_message_id,end_reason='RESTART'
                                WHERE view_session_id=? AND ends_at IS NULL
                                """, view);
                        jdbc.update("""
                                UPDATE chat_view_runtime SET state='OFFLINE',deadline=NULL,owner_task_id=NULL,
                                    socket_token=NULL,owner_generation=owner_generation+1 WHERE view_id=?
                                """, view);
                    }
                    if (!expired.isEmpty()) {
                        rooms.forEach(tx::changed);
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
            failClosed();
        }
    }

    public void refreshProjections() {
        if (available()) {
            touchedRooms.forEach(this::project);
        }
    }

    private <T> T execute(long user, Function<Transition, T> action) {
        if (!available()) {
            throw new ChatParticipationUnavailableException();
        }
        var tx = new Transition();
        T result;
        try {
            result = transactions.execute(status -> {
                jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                control();
                // Small P1 task set: ordered SHARE locks also pin any old owner lease during a transition.
                jdbc.queryForList("""
                        SELECT task_id FROM chat_runtime_tasks WHERE environment=? ORDER BY task_id FOR SHARE
                        """, String.class, environment);
                requireTask();
                List<Long> users = jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, user);
                if (users.isEmpty()) {
                    throw error(ChatRoomErrorCode.USER_NOT_FOUND);
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        if (System.nanoTime() - tx.started >= TimeUnit.SECONDS.toNanos(2)) {
                            throw new ChatParticipationUnavailableException();
                        }
                        requireTask();
                        ChatAuthOperation.check();
                    }
                });
                return action.apply(tx);
            });
        } catch (ChatRoomException | ChatParticipationUnavailableException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            throw new ChatParticipationUnavailableException();
        }
        tx.changed.forEach(this::project);
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

    private void endMembership(Transition tx, Member member) {
        storage.endedShared(member.id);
        jdbc.update("UPDATE chat_room_members SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(6)) WHERE id=?",
                member.id);
        jdbc.update("""
                UPDATE chat_view_runtime SET state='LEFT',deadline=NULL,owner_task_id=NULL,socket_token=NULL,
                    owner_generation=owner_generation+1 WHERE membership_id=? AND state<>'LEFT'
                """, member.id);
        tx.changed(member.room);
        jdbc.update("UPDATE chat_view_runtime SET changed_seq=? WHERE membership_id=?", tx.version(member.room),
                member.id);
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
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
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
        try {
            touchedRooms.add(room);
            var snapshot = snapshot(room);
            projection.write(snapshot);
            events.publishEvent(
                    new ChatPresenceChangedEvent(room, snapshot.connected(), snapshot.version(), null, null));
        } catch (RuntimeException unavailable) {
            failClosed();
            throw new ChatParticipationUnavailableException();
        }
    }

    public void failClosed() {
        failed = true;
        sockets.closeAll(1013, "CHAT_SERVICE_UNAVAILABLE");
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
        private final Set<Long> changed = new LinkedHashSet<>();

        private void lock(List<Long> rooms) {
            rooms.stream().distinct().sorted().forEach(room -> {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM chat_rooms WHERE id=?", Integer.class, room) != 1) {
                    throw error(ChatRoomErrorCode.CHAT_ROOM_NOT_FOUND);
                }
                jdbc.update("INSERT IGNORE INTO chat_room_runtime(environment,room_id) VALUES (?,?)", environment,
                        room);
                jdbc.queryForObject("""
                        SELECT room_seq FROM chat_room_runtime WHERE environment=? AND room_id=? FOR UPDATE
                        """, Long.class, environment, room);
            });
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
