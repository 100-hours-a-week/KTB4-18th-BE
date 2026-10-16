package com.muse.meomuneum.auth.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

final class AuthQueuePositionTracker {
    private static final int MAP_BATCH_SIZE = 100;
    private static final long SNAPSHOT_TTL_SECONDS = 30;
    private static final String TICKETS = "login-queue-tickets-v2";
    private static final String ORDER = "login-queue-order-v2";

    private final HazelcastAuthStateAccess stateAccess;
    private volatile QueuePositionSnapshot snapshot = QueuePositionSnapshot.empty();

    AuthQueuePositionTracker(HazelcastAuthStateAccess stateAccess) {
        this.stateAccess = stateAccess;
    }

    Integer estimatedUsersAhead(HazelcastAuthStateStore.Ticket ticket) {
        QueuePositionSnapshot current = snapshot;
        if (current.createdAt().plusSeconds(SNAPSHOT_TTL_SECONDS).isBefore(Instant.now())) {
            return null;
        }
        return current.positions().get(new HazelcastAuthStateStore.QueueToken(ticket.ticketId(),
                ticket.enqueueGeneration()));
    }

    void refreshQueuePositionSnapshot() {
        try {
            HazelcastInstance hz = stateAccess.requireHazelcast();
            List<HazelcastAuthStateStore.QueueToken> orderedTokens = new java.util.ArrayList<>(
                    hz.<HazelcastAuthStateStore.QueueToken>getQueue(ORDER));
            IMap<String, HazelcastAuthStateStore.Ticket> tickets = hz.getMap(TICKETS);
            Set<String> candidateIds = new LinkedHashSet<>();
            for (HazelcastAuthStateStore.QueueToken token : orderedTokens) {
                candidateIds.add(token.ticketId());
            }

            Map<String, HazelcastAuthStateStore.Ticket> currentTickets = new HashMap<>();
            List<String> candidateIdList = new java.util.ArrayList<>(candidateIds);
            for (int start = 0; start < candidateIdList.size(); start += MAP_BATCH_SIZE) {
                int end = Math.min(start + MAP_BATCH_SIZE, candidateIdList.size());
                currentTickets.putAll(tickets.getAll(new HashSet<>(candidateIdList.subList(start, end))));
            }

            Map<HazelcastAuthStateStore.QueueToken, Integer> positions = new HashMap<>();
            Set<String> countedTicketIds = new HashSet<>();
            int usersAhead = 0;
            for (HazelcastAuthStateStore.QueueToken token : orderedTokens) {
                HazelcastAuthStateStore.Ticket current = currentTickets.get(token.ticketId());
                if (current == null || current.enqueueGeneration() != token.generation()
                        || !ownsQueuePosition(current.status()) || !countedTicketIds.add(token.ticketId())) {
                    continue;
                }
                positions.put(token, usersAhead++);
            }
            snapshot = new QueuePositionSnapshot(Instant.now(), Map.copyOf(positions));
        } catch (RuntimeException exception) {
            throw stateAccess.unavailable(exception);
        }
    }

    HazelcastAuthStateStore.TicketView ticketView(HazelcastAuthStateStore.Ticket ticket) {
        Integer ahead = null;
        if (ticket.status() == HazelcastAuthStateStore.TicketStatus.READY
                || ticket.status() == HazelcastAuthStateStore.TicketStatus.VERIFYING
                || isTerminal(ticket.status())) {
            ahead = 0;
        } else if (ticket.status() == HazelcastAuthStateStore.TicketStatus.WAITING) {
            ahead = estimatedUsersAhead(ticket);
        }
        HazelcastAuthStateStore.LoginOutcome outcome = ticket.status() == HazelcastAuthStateStore.TicketStatus.CONSUMED
                ? ticket.loginOutcome()
                : null;
        HazelcastAuthStateStore.TicketStatus status = ticket.status();
        HazelcastAuthStateStore.TicketStatus visible = isTerminal(status)
                || status == HazelcastAuthStateStore.TicketStatus.READY
                || status == HazelcastAuthStateStore.TicketStatus.VERIFYING
                || status == HazelcastAuthStateStore.TicketStatus.RETRYABLE
                        ? status
                        : HazelcastAuthStateStore.TicketStatus.WAITING;
        return new HazelcastAuthStateStore.TicketView(visible, outcome, ahead, 6, ticket.ticketId());
    }

    private boolean ownsQueuePosition(HazelcastAuthStateStore.TicketStatus status) {
        return status == HazelcastAuthStateStore.TicketStatus.CREATING
                || status == HazelcastAuthStateStore.TicketStatus.PENDING
                || status == HazelcastAuthStateStore.TicketStatus.OFFERING
                || status == HazelcastAuthStateStore.TicketStatus.WAITING
                || status == HazelcastAuthStateStore.TicketStatus.ADMITTING
                || status == HazelcastAuthStateStore.TicketStatus.REQUEUE_OFFERING
                || status == HazelcastAuthStateStore.TicketStatus.REQUEUING_UNKNOWN;
    }

    private boolean isTerminal(HazelcastAuthStateStore.TicketStatus status) {
        return status == HazelcastAuthStateStore.TicketStatus.CONSUMED
                || status == HazelcastAuthStateStore.TicketStatus.CANCELLED
                || status == HazelcastAuthStateStore.TicketStatus.EXPIRED;
    }

    private record QueuePositionSnapshot(Instant createdAt,
            Map<HazelcastAuthStateStore.QueueToken, Integer> positions) {
        private static QueuePositionSnapshot empty() {
            return new QueuePositionSnapshot(Instant.EPOCH, Map.of());
        }
    }
}
