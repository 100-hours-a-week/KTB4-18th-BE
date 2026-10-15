package com.muse.meomuneum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import com.hazelcast.cluster.Cluster;
import com.hazelcast.cluster.Member;
import com.hazelcast.config.Config;
import com.hazelcast.config.QueueConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateConflictException;
import com.muse.meomuneum.auth.exception.AuthStateExpiredException;
import com.muse.meomuneum.auth.exception.AuthStateTooEarlyException;
import com.muse.meomuneum.auth.exception.AuthStateTooManyRequestsException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.auth.queue.LoginQueueAdmissionScheduler;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.ReconciliationAccumulator;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class HazelcastAuthStateStoreTest {

    private HazelcastInstance member;
    private HazelcastAuthStateStore store;
    private AuthStateProperties properties;
    private String clusterName;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        clusterName = "auth-state-test-" + UUID.randomUUID();
        config.setClusterName(clusterName);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        config.getNetworkConfig().setPort(5901);
        config.getNetworkConfig().setPortAutoIncrement(true);
        member = Hazelcast.newHazelcastInstance(config);

        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        properties = new AuthStateProperties("01234567890123456789012345678901",
                Base64.getEncoder().encodeToString(new byte[32]), "MEO_QUEUE_V2", 1209600, 7776000,
                900, 60, 900, 20, 1, 64, 4, 4, 120, 1800, 1, 20, 4000, 86400, 1, 100000, 300, 45);
        store = new HazelcastAuthStateStore(provider, properties);
    }

    @AfterEach
    void tearDown() {
        if (member != null) {
            member.shutdown();
        }
    }

    @Test
    void facadeUsesCompositionAndKeepsThePublicTwoArgumentConstructor() throws NoSuchMethodException {
        assertThat(HazelcastAuthStateStore.class.getSuperclass()).isEqualTo(Object.class);
        assertThat(HazelcastAuthStateStore.class.getConstructor(ObjectProvider.class, AuthStateProperties.class))
                .isNotNull();
    }

    @Test
    void startupRecoveryRestoresSessionCountBeforeScanningQueueTickets() {
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        HazelcastInstance hazelcast = mock(HazelcastInstance.class);
        LifecycleService lifecycle = mock(LifecycleService.class);
        Cluster cluster = mock(Cluster.class);
        @SuppressWarnings("unchecked")
        IMap<String, HazelcastAuthStateStore.Ticket> tickets = mock(IMap.class);
        @SuppressWarnings("unchecked")
        IMap<String, HazelcastAuthStateStore.CreateOperation> operations = mock(IMap.class);
        AtomicBoolean restoredSessionCount = new AtomicBoolean();
        when(provider.getIfAvailable()).thenReturn(hazelcast);
        when(hazelcast.getLifecycleService()).thenReturn(lifecycle);
        when(lifecycle.isRunning()).thenReturn(true);
        when(hazelcast.getCluster()).thenReturn(cluster);
        when(cluster.getMembers()).thenReturn(Set.of(mock(Member.class)));
        when(hazelcast.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2"))
                .thenReturn(tickets);
        when(hazelcast.<String, HazelcastAuthStateStore.CreateOperation>getMap("login-queue-create-operations-v2"))
                .thenReturn(operations);
        when(tickets.iterator(anyInt())).thenAnswer(invocation -> {
            assertThat(restoredSessionCount.get()).isTrue();
            return List.<Map.Entry<String, HazelcastAuthStateStore.Ticket>>of().iterator();
        });
        when(operations.iterator(anyInt()))
                .thenReturn(List.<Map.Entry<String, HazelcastAuthStateStore.CreateOperation>>of().iterator());

        HazelcastAuthStateStore startupStore = new HazelcastAuthStateStore(provider, properties) {
            @Override
            protected void restoreSessionCount(IMap<String, AuthSession> sessions) {
                restoredSessionCount.set(true);
            }
        };

        startupStore.restoreActiveTicketReservations();
    }

    private HazelcastAuthStateStore.CreateTicket createTicket(String cookie, String idempotencyKey) {
        store.restoreActiveTicketReservations();
        HazelcastAuthStateStore.CreateTicket created = store.createOrReuseTicket(cookie, idempotencyKey);
        store.processPendingCreateBatch(20);
        return created;
    }

    private HazelcastAuthStateStore.Ticket makeRetryable(HazelcastAuthStateStore.CreateTicket created) {
        String ticketId = created.view().ticketId();
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        HazelcastAuthStateStore.Ticket current = tickets.get(ticketId);
        order.remove(new HazelcastAuthStateStore.QueueToken(ticketId, current.enqueueGeneration()));
        HazelcastAuthStateStore.Ticket retryable = current.withState(
                HazelcastAuthStateStore.TicketStatus.RETRYABLE, null, UUID.randomUUID().toString(), null,
                null, null, null, null);
        assertThat(tickets.replace(ticketId, current, retryable)).isTrue();
        return retryable;
    }

    private ObjectProvider<HazelcastInstance> memberProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        return provider;
    }

    private AuthStateProperties capacityProperties() {
        return new AuthStateProperties("01234567890123456789012345678901",
                Base64.getEncoder().encodeToString(new byte[32]), "MEO_QUEUE_V2", 1209600, 7776000,
                900, 60, 900, 2000, 10, 64, 150, 20, 120, 1800, 5, 2000, 4000, 86400, 1, 100000, 300, 45,
                150);
    }

    private void seedActiveTickets(int count) {
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        Instant now = Instant.now();
        for (int index = 0; index < count; index++) {
            String ticketId = "capacity-ticket-" + index;
            tickets.put(ticketId, new HazelcastAuthStateStore.Ticket(ticketId, index + 1L, now,
                    now.plusSeconds(900), null, HazelcastAuthStateStore.TicketStatus.WAITING, null,
                    "create-operation-" + index, null, null, null, null, null, 1));
        }
    }

    @Test
    void restoresOneHundredFiftyActiveTicketsAndRejectsTheNextReservation() {
        HazelcastAuthStateStore capacityStore = new HazelcastAuthStateStore(memberProvider(), capacityProperties());
        seedActiveTickets(150);

        capacityStore.restoreActiveTicketReservations();

        assertThat(capacityStore.activeTicketCount()).isEqualTo(150);
        assertThatThrownBy(() -> capacityStore.createOrReuseTicket(null, UUID.randomUUID().toString()))
                .isInstanceOf(AuthStateTooManyRequestsException.class);
    }

    @Test
    void rejectsRestoreWhenActiveTicketsExceedOneHundredFifty() {
        HazelcastAuthStateStore capacityStore = new HazelcastAuthStateStore(memberProvider(), capacityProperties());
        seedActiveTickets(151);

        assertThatThrownBy(capacityStore::restoreActiveTicketReservations)
                .isInstanceOf(AuthStateUnavailableException.class);
    }

    @Test
    void restoreExpiresStaleNonterminalTicketsBeforeApplyingTheActiveCap() {
        HazelcastAuthStateStore capacityStore = new HazelcastAuthStateStore(memberProvider(), capacityProperties());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        Instant now = Instant.now();
        HazelcastAuthStateStore.TicketStatus[] statuses = {
                HazelcastAuthStateStore.TicketStatus.WAITING,
                HazelcastAuthStateStore.TicketStatus.READY,
                HazelcastAuthStateStore.TicketStatus.RETRYABLE
        };

        for (int index = 0; index < 151; index++) {
            String ticketId = "expired-cap-ticket-" + index;
            String operationId = "expired-cap-operation-" + index;
            long generation = 1;
            var status = statuses[index % statuses.length];
            var ticket = new HazelcastAuthStateStore.Ticket(ticketId, index + 1L,
                    now.minusSeconds(1_000), now.minusSeconds(1),
                    status == HazelcastAuthStateStore.TicketStatus.READY ? now.minusSeconds(1) : null,
                    status, null, operationId, null, null, null, null, null, generation);
            tickets.put(ticketId, ticket);
            operations.put(operationId,
                    HazelcastAuthStateStore.CreateOperation.accepted(ticketId, "cookie", now));
            order.offer(new HazelcastAuthStateStore.QueueToken(ticketId, generation));
        }

        capacityStore.restoreActiveTicketReservations();

        assertThat(capacityStore.activeTicketCount()).isZero();
        assertThat(tickets.values()).allSatisfy(
                ticket -> assertThat(ticket.status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.EXPIRED));
        assertThat(operations.values()).allSatisfy(operation -> assertThat(operation.status())
                .isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.TERMINAL));
        assertThat(order.size()).isZero();
        assertThat(capacityStore.createOrReuseTicket(null, UUID.randomUUID().toString()).newlyCreated()).isTrue();
    }

    @Test
    void createFailsClosedUntilStartupReservationRestoreCompletes() {
        HazelcastAuthStateStore startupStore = new HazelcastAuthStateStore(memberProvider(), capacityProperties());

        assertThatThrownBy(() -> startupStore.createOrReuseTicket(null, UUID.randomUUID().toString()))
                .isInstanceOf(AuthStateUnavailableException.class);
        assertThat(startupStore.activeTicketCount()).isZero();
        assertThat(member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2").size()).isZero();

        startupStore.restoreActiveTicketReservations();

        var created = startupStore.createOrReuseTicket(null, UUID.randomUUID().toString());
        assertThat(created.newlyCreated()).isTrue();
        assertThat(startupStore.activeTicketCount()).isEqualTo(1);
    }

    @Test
    void configuredTicketLimitDoesNotChangeQueueCapacityOrBcryptConcurrency() {
        HazelcastAuthStateStore capacityStore = new HazelcastAuthStateStore(memberProvider(), capacityProperties());
        capacityStore.restoreActiveTicketReservations();
        List<String> cookies = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            cookies.add(capacityStore.createOrReuseTicket(null, UUID.randomUUID().toString()).queueCookie());
        }

        assertThat(capacityStore.activeTicketCount()).isEqualTo(150);
        int ticketCount = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2").size();
        assertThatThrownBy(() -> capacityStore.createOrReuseTicket(null, UUID.randomUUID().toString()))
                .isInstanceOf(AuthStateTooManyRequestsException.class)
                .hasMessage("QUEUE_FULL");
        assertThat(member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2").size())
                .isEqualTo(ticketCount);

        capacityStore.cancelCurrentTicket(cookies.get(0));
        assertThat(capacityStore.activeTicketCount()).isEqualTo(149);
        assertThat(capacityStore.createOrReuseTicket(null, UUID.randomUUID().toString()).newlyCreated()).isTrue();
    }

    @Test
    void concurrentSameKeyCreatesShareOneTicketAndOneReservation() throws Exception {
        store.restoreActiveTicketReservations();
        String key = UUID.randomUUID().toString();
        int callers = 12;
        CyclicBarrier start = new CyclicBarrier(callers);
        var executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<HazelcastAuthStateStore.CreateTicket>> results = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                results.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return store.createOrReuseTicket(null, key);
                }));
            }

            List<HazelcastAuthStateStore.CreateTicket> created = new ArrayList<>();
            for (Future<HazelcastAuthStateStore.CreateTicket> result : results) {
                created.add(result.get(10, TimeUnit.SECONDS));
            }

            assertThat(created).extracting(ticket -> ticket.view().ticketId()).containsOnly(created.get(0)
                    .view().ticketId());
            assertThat(store.activeTicketCount()).isEqualTo(1);
            assertThat(member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2").size())
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredClaimTakeoverChangesOwnerAndKeepsOneQueueToken() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        String operationId = created.view().ticketId();
        var accepted = operations.get(operationId);
        var expiredClaim = new HazelcastAuthStateStore.CreateOperation(
                HazelcastAuthStateStore.CreateOperationStatus.CLAIMED, accepted.ticketId(), accepted.cookieHash(),
                "stale-owner", accepted.reservationId(), Instant.now().minusSeconds(1),
                Instant.now().minusSeconds(31));
        assertThat(operations.replace(operationId, accepted, expiredClaim)).isTrue();

        store.reconcileVerifyingTickets();

        var recovered = operations.get(operationId);
        assertThat(recovered.ownerToken()).isNotEqualTo("stale-owner");
        assertThat(recovered.status()).isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.ACCEPTED);
        var token = new HazelcastAuthStateStore.QueueToken(created.view().ticketId(), 1);
        assertThat(order.stream().filter(token::equals).count()).isEqualTo(1);
        assertThat(store.activeTicketCount()).isEqualTo(1);
    }

    @Test
    void ticketCreateReturnsBeforeBackgroundAppenderOffersFifoToken() {
        store.restoreActiveTicketReservations();
        var created = store.createOrReuseTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");

        assertThat(created.view().status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(tickets.get(created.view().ticketId()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.CREATING);
        assertThat(store.nextWaitingTicket()).isEmpty();

        assertThat(store.processPendingCreateBatch(20)).isEqualTo(1);
        assertThat(store.nextWaitingTicket()).isPresent();
    }

    @Test
    void queueCreateIsIdempotentAndOnlyReadyTicketCanBeClaimed() {
        String idempotencyKey = UUID.randomUUID().toString();
        var created = createTicket(null, idempotencyKey);
        var retried = createTicket(null, idempotencyKey);
        assertThat(retried.queueCookie()).isEqualTo(created.queueCookie());
        assertThat(retried.view().ticketId()).isEqualTo(created.view().ticketId());

        assertThatThrownBy(() -> store.claimReadyTicket(created.queueCookie(), UUID.randomUUID().toString()))
                .isInstanceOf(AuthStateTooEarlyException.class);

        var waiting = store.nextWaitingTicket().orElseThrow();
        admitReadyTicket(waiting);
        String loginOperation = UUID.randomUUID().toString();
        var claim = store.claimReadyTicket(created.queueCookie(), loginOperation);
        assertThat(claim.bcryptPermitKey()).isNotBlank();
        assertThat(store.ownsBcryptPermit(claim.ticketId(), claim.bcryptPermitKey())).isTrue();
        store.completeTicket(claim, HazelcastAuthStateStore.LoginOutcome.INVALID_CREDENTIALS, null);
        store.releaseBcryptPermit(claim.ticketId(), claim.bcryptPermitKey());

        var terminal = store.currentTicket(created.queueCookie());
        assertThat(terminal.status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.CONSUMED);
        assertThat(terminal.loginOutcome()).isEqualTo(HazelcastAuthStateStore.LoginOutcome.INVALID_CREDENTIALS);
    }

    @Test
    void exceptionReconciliationImmediatelyRequeuesWithOneFencedQueueToken() {
        var created = createTicket(null, UUID.randomUUID().toString());
        new LoginQueueAdmissionScheduler(store).admitNextTicket();
        String operationId = UUID.randomUUID().toString();
        String identityHash = store.loginIdentityHash("user@example.com");
        var claim = store.claimReadyTicket(created.queueCookie(), operationId, null, identityHash);
        assertThat(claim.authIdentityHash()).isEqualTo(identityHash);
        store.releaseBcryptPermit(claim.ticketId(), claim.bcryptPermitKey());

        store.reconcileFailedLogin(claim);

        var current = ticket(created);
        var queueToken = new HazelcastAuthStateStore.QueueToken(current.ticketId(), current.enqueueGeneration());
        assertThat(current.status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2").stream()
                .filter(queueToken::equals).count()).isEqualTo(1);
        store.reconcileFailedLogin(claim);
        assertThat(member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2").stream()
                .filter(queueToken::equals).count()).isEqualTo(1);
    }

    @Test
    void expiredClaimIsRecoveredAndRequeuedDuringLoginRetry() {
        var created = createTicket(null, UUID.randomUUID().toString());
        new LoginQueueAdmissionScheduler(store).admitNextTicket();
        String operationId = UUID.randomUUID().toString();
        String identityHash = store.loginIdentityHash("user@example.com");
        var claim = store.claimReadyTicket(created.queueCookie(), operationId, null, identityHash);
        assertThat(claim.authIdentityHash()).isEqualTo(identityHash);
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var expired = claim.withState(HazelcastAuthStateStore.TicketStatus.VERIFYING, null, operationId,
                claim.claimId(), Instant.now().minusSeconds(1), null, claim.bcryptPermitKey(), claim.sid());
        assertThat(tickets.replace(claim.ticketId(), claim, expired)).isTrue();
        store.releaseBcryptPermit(claim.ticketId(), claim.bcryptPermitKey());

        assertThatThrownBy(() -> store.claimReadyTicket(created.queueCookie(), operationId, null, identityHash))
                .isInstanceOf(AuthStateTooEarlyException.class)
                .hasMessage("QUEUE_WAITING");

        var current = ticket(created);
        var queueToken = new HazelcastAuthStateStore.QueueToken(current.ticketId(), current.enqueueGeneration());
        assertThat(current.status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2").stream()
                .filter(queueToken::equals).count()).isEqualTo(1);
    }

    @Test
    void retryClaimPreservesPreviouslyBoundUserIdentity() {
        var created = createTicket(null, UUID.randomUUID().toString());
        new LoginQueueAdmissionScheduler(store).admitNextTicket();
        String operationId = UUID.randomUUID().toString();
        String identityHash = store.loginIdentityHash("user@example.com");
        var firstClaim = store.claimReadyTicket(created.queueCookie(), operationId, null, identityHash);
        firstClaim = store.bindAuthenticationIdentity(firstClaim, identityHash);
        firstClaim = store.bindAuthenticatedUser(firstClaim, 100L);
        store.releaseBcryptPermit(firstClaim.ticketId(), firstClaim.bcryptPermitKey());
        store.reconcileFailedLogin(firstClaim);

        new LoginQueueAdmissionScheduler(store).admitNextTicket();
        var retryClaim = store.claimReadyTicket(created.queueCookie(), operationId, null, identityHash);
        retryClaim = store.bindAuthenticationIdentity(retryClaim, identityHash);
        var boundRetryClaim = retryClaim;

        assertThat(retryClaim.authPhase()).isEqualTo("AUTHENTICATING");
        assertThat(retryClaim.authenticatedUserId()).isEqualTo(100L);
        assertThatThrownBy(() -> store.bindAuthenticatedUser(boundRetryClaim, 101L))
                .isInstanceOf(AuthStateConflictException.class)
                .hasMessage("LOGIN_IDENTITY_MISMATCH");
    }

    @Test
    void currentTicketResolvesReservedOperationBeforeTicketMaterializes() {
        String key = UUID.randomUUID().toString();
        var created = createTicket(null, key);
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var ticket = tickets.remove(created.view().ticketId());
        var operation = operations.get(created.view().ticketId());
        assertThat(ticket).isNotNull();
        assertThat(operation).isNotNull();
        operations.replace(created.view().ticketId(), operation,
                operation.withStatus(HazelcastAuthStateStore.CreateOperationStatus.PENDING, Instant.now()));

        var current = store.currentTicket(created.queueCookie());

        assertThat(current.status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(current.usersAhead()).isNull();
    }

    @Test
    void idempotentRetryAndSchedulerRecoverClaimWhoseFirstResponseWasLost() {
        String key = UUID.randomUUID().toString();
        var created = createTicket(null, key);
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var ticket = tickets.remove(created.view().ticketId());
        var accepted = operations.get(created.view().ticketId());
        var staleClaim = HazelcastAuthStateStore.CreateOperation.claimed(ticket.ticketId(), accepted.cookieHash(),
                UUID.randomUUID().toString(), Instant.now().minusSeconds(31), Instant.now().minusSeconds(1));
        assertThat(operations.replace(ticket.ticketId(), accepted, staleClaim)).isTrue();

        var retry = createTicket(null, key);
        assertThat(retry.queueCookie()).isEqualTo(created.queueCookie());
        assertThat(retry.view().status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(retry.view().usersAhead()).isNull();

        store.reconcileVerifyingTickets();

        assertThat(store.currentTicket(retry.queueCookie()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(operations.get(ticket.ticketId()).status())
                .isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.ACCEPTED);
    }

    @Test
    void currentTicketDoesNotUnboxNullQueuePositionWhileRequeueRecoveryIsPending() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var waiting = ticket(created);
        var recovering = waiting.withState(HazelcastAuthStateStore.TicketStatus.REQUEUING_UNKNOWN,
                null, null, null, null, null, null, null);
        assertThat(tickets.replace(waiting.ticketId(), waiting, recovering)).isTrue();

        var current = store.currentTicket(created.queueCookie());

        assertThat(current.status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(current.usersAhead()).isNull();
    }

    @Test
    void waitingPositionCountsValidFifoPredecessorsWithoutGlobalSequenceMap() {
        var first = createTicket(null, UUID.randomUUID().toString());
        var second = createTicket(null, UUID.randomUUID().toString());
        var third = createTicket(null, UUID.randomUUID().toString());
        store.refreshQueuePositionSnapshot();

        assertThat(store.currentTicket(first.queueCookie()).usersAhead()).isZero();
        assertThat(store.currentTicket(second.queueCookie()).usersAhead()).isEqualTo(1);
        assertThat(store.currentTicket(third.queueCookie()).usersAhead()).isEqualTo(2);
        assertThat(member.<String, Long>getMap("login-queue-sequence-v2").size()).isZero();
    }

    @Test
    void waitingPositionIgnoresStaleAndDuplicatePredecessorTokens() {
        var first = createTicket(null, UUID.randomUUID().toString());
        var second = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        var firstTicket = ticket(first);
        order.clear();
        order.offer(new HazelcastAuthStateStore.QueueToken(firstTicket.ticketId(), 0));
        order.offer(new HazelcastAuthStateStore.QueueToken(firstTicket.ticketId(), firstTicket.enqueueGeneration()));
        order.offer(new HazelcastAuthStateStore.QueueToken(firstTicket.ticketId(), firstTicket.enqueueGeneration()));
        order.offer(
                new HazelcastAuthStateStore.QueueToken(ticket(second).ticketId(), ticket(second).enqueueGeneration()));
        store.refreshQueuePositionSnapshot();

        assertThat(store.currentTicket(second.queueCookie()).usersAhead()).isEqualTo(1);
        assertThat(tickets.get(firstTicket.ticketId()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
    }

    @Test
    void oldestPendingAgePublishesOnlyAfterFullCursorCycleWithMoreThanOnePage() {
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        store.reconcileVerifyingTickets();
        assertThat(store.oldestPendingAgeSeconds()).isZero();

        Instant now = Instant.now();
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 250; index++) {
            String id = "age-cycle-" + UUID.randomUUID();
            ids.add(id);
            operations.put(id, HazelcastAuthStateStore.CreateOperation.accepted(id, "cookie", now)
                    .withStatus(HazelcastAuthStateStore.CreateOperationStatus.TERMINAL, now));
        }
        Set<String> firstPage = new java.util.HashSet<>();
        var iterator = operations.iterator(100);
        while (iterator.hasNext() && firstPage.size() < 100) {
            firstPage.add(iterator.next().getKey());
        }
        String oldestId = ids.stream().filter(id -> !firstPage.contains(id)).findFirst().orElseThrow();
        Instant oldestCreatedAt = Instant.now().minusSeconds(180);
        operations.put(oldestId, HazelcastAuthStateStore.CreateOperation.accepted(oldestId, "cookie", oldestCreatedAt)
                .withReservation(oldestId, UUID.randomUUID().toString(), oldestCreatedAt)
                .withStatus(HazelcastAuthStateStore.CreateOperationStatus.PENDING, oldestCreatedAt));
        tickets.put(oldestId, new HazelcastAuthStateStore.Ticket(oldestId, 0, oldestCreatedAt,
                Instant.now().plusSeconds(3600), null, HazelcastAuthStateStore.TicketStatus.CREATING, null,
                oldestId, null, null, null, null, null, 1));
        assertThat(store.oldestPendingAge(List.of(Map.entry(oldestId, operations.get(oldestId))), tickets,
                Instant.now())).isGreaterThanOrEqualTo(170);

        store.reconcileVerifyingTickets();
        assertThat(store.oldestPendingAgeSeconds()).isZero();
        for (int pass = 0; pass < 10 && store.oldestPendingAgeSeconds() == 0; pass++) {
            store.reconcileVerifyingTickets();
            store.processPendingCreateBatch(20);
        }

        assertThat(store.oldestPendingAgeSeconds())
                .isGreaterThanOrEqualTo(170);
        assertThat(operations.get(oldestId).status()).isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.ACCEPTED);
        assertThat(tickets.get(oldestId).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
    }

    @Test
    void activeTicketReservationRestoreKeepsReadyTicketWithoutBcryptPermit() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var waiting = store.nextWaitingTicket().orElseThrow();
        admitReadyTicket(waiting);

        store.restoreActiveTicketReservations();

        assertThat(store.activeTicketCount()).isEqualTo(1);
        assertThat(store.currentTicket(created.queueCookie()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.READY);
        assertThat(ticket(created).bcryptPermitKey()).isNull();
    }

    @Test
    void startupRecoversExpiredVerificationClaimBeforeRestoringPermitCapacity() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var waiting = store.nextWaitingTicket().orElseThrow();
        admitReadyTicket(waiting);
        String loginOperationId = UUID.randomUUID().toString();
        var claim = store.claimReadyTicket(created.queueCookie(), loginOperationId);
        String permitKey = claim.bcryptPermitKey();

        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var verifying = tickets.get(created.view().ticketId());
        var expired = verifying.withState(HazelcastAuthStateStore.TicketStatus.VERIFYING, null,
                loginOperationId, verifying.claimId(), Instant.now().minusSeconds(1), null, permitKey, null);
        assertThat(tickets.replace(verifying.ticketId(), verifying, expired)).isTrue();

        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        HazelcastAuthStateStore restartedStore = new HazelcastAuthStateStore(provider, properties);

        assertThatNoException().isThrownBy(restartedStore::restoreActiveTicketReservations);
        assertThat(tickets.get(created.view().ticketId()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.RETRYABLE);
    }

    @Test
    void startupReservationRestoreSkipsTicketRemovedDuringHazelcastIteration() {
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        HazelcastInstance hazelcast = mock(HazelcastInstance.class);
        LifecycleService lifecycle = mock(LifecycleService.class);
        Cluster cluster = mock(Cluster.class);
        @SuppressWarnings("unchecked")
        IMap<String, HazelcastAuthStateStore.Ticket> tickets = mock(IMap.class);
        @SuppressWarnings("unchecked")
        IMap<String, HazelcastAuthStateStore.CreateOperation> operations = mock(IMap.class);
        when(provider.getIfAvailable()).thenReturn(hazelcast);
        when(hazelcast.getLifecycleService()).thenReturn(lifecycle);
        when(lifecycle.isRunning()).thenReturn(true);
        when(hazelcast.getCluster()).thenReturn(cluster);
        when(cluster.getMembers()).thenReturn(java.util.Set.of(mock(Member.class)));
        when(hazelcast.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2"))
                .thenReturn(tickets);
        when(hazelcast.<String, HazelcastAuthStateStore.CreateOperation>getMap("login-queue-create-operations-v2"))
                .thenReturn(operations);
        Map.Entry<String, HazelcastAuthStateStore.Ticket> missingTicket = new AbstractMap.SimpleImmutableEntry<>(
                "expired-during-iteration", null);
        when(tickets.iterator(anyInt())).thenReturn(List.of(missingTicket).iterator());
        when(operations.iterator(anyInt()))
                .thenReturn(List.<Map.Entry<String, HazelcastAuthStateStore.CreateOperation>>of().iterator());

        HazelcastAuthStateStore recoveringStore = new HazelcastAuthStateStore(provider, properties) {
            @Override
            protected void restoreSessionCount(IMap<String, AuthSession> sessions) {
            }
        };

        assertThatNoException().isThrownBy(recoveringStore::restoreActiveTicketReservations);
    }

    @Test
    void perUserSessionReservationEnforcesConfiguredCap() {
        store.restoreActiveTicketReservations();
        Instant absoluteExpiry = store.newAbsoluteSessionExpiry();
        store.createSession(51L, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "hash-a",
                UUID.randomUUID().toString(), "encrypted-token", Instant.now().plusSeconds(3600), absoluteExpiry);

        assertThatThrownBy(() -> store.createSession(51L, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "hash-b", UUID.randomUUID().toString(), "encrypted-token",
                Instant.now().plusSeconds(3600), absoluteExpiry))
                .isInstanceOf(AuthStateConflictException.class)
                .hasMessage("DEVICE_SESSION_LIMIT");
    }

    @Test
    void reconciliationPromotesLiveSessionFromPendingUserSlotAfterInjectedFailure() {
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        HazelcastAuthStateStore recoveringStore = new HazelcastAuthStateStore(provider, properties) {
            @Override
            protected void promoteUserSlot(Long userId, String sid) {
                if (failOnce.compareAndSet(true, false)) {
                    throw new AuthStateUnavailableException();
                }
                super.promoteUserSlot(userId, sid);
            }
        };
        recoveringStore.restoreActiveTicketReservations();
        String sid = UUID.randomUUID().toString();

        assertThatThrownBy(() -> recoveringStore.createSession(53L, sid, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "encrypted-token",
                Instant.now().plusSeconds(3600), recoveringStore.newAbsoluteSessionExpiry()))
                .isInstanceOf(AuthStateUnavailableException.class);
        var sessions = member.<String, HazelcastAuthStateStore.AuthSession>getMap("auth-sessions-v2");
        var indexes = member.<String, HazelcastAuthStateStore.UserSessionIndex>getMap("auth-user-session-index-v2");
        assertThat(sessions.get(sid).status()).isEqualTo(HazelcastAuthStateStore.SessionStatus.ACTIVE);
        assertThat(indexes.get("53").sessions().get(sid).status())
                .isEqualTo(HazelcastAuthStateStore.SlotStatus.PENDING);

        recoveringStore.reconcileVerifyingTickets();

        assertThat(indexes.get("53").sessions().get(sid).status())
                .isEqualTo(HazelcastAuthStateStore.SlotStatus.ACTIVE);
        assertThat(member.<String, String>getMap("auth-login-session-index-v2").get(
                sessions.get(sid).loginOperationId())).isEqualTo(sid);
    }

    @Test
    void concurrentUserSlotPromotionsMergeWithoutLosingSessions() throws Exception {
        int updateCount = 64;
        Long userId = 540L;
        List<String> sids = java.util.stream.IntStream.range(0, updateCount)
                .mapToObj(ignored -> UUID.randomUUID().toString())
                .toList();
        CountDownLatch ready = new CountDownLatch(updateCount);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(updateCount)) {
            List<Future<Object>> updates = sids.stream().map(sid -> executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                store.promoteUserSlot(userId, sid);
                return null;
            })).toList();

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> update : updates) {
                update.get(20, TimeUnit.SECONDS);
            }
        }

        var indexes = member.<String, HazelcastAuthStateStore.UserSessionIndex>getMap("auth-user-session-index-v2");
        assertThat(indexes.get(userId.toString()).sessions()).hasSize(updateCount)
                .allSatisfy((sid, slot) -> assertThat(slot.status())
                        .isEqualTo(HazelcastAuthStateStore.SlotStatus.ACTIVE));
    }

    @Test
    void reconciliationRepairsLoginSessionIndexAfterInjectedWriteFailure() {
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        HazelcastAuthStateStore recoveringStore = new HazelcastAuthStateStore(provider, properties) {
            @Override
            protected void indexLoginSession(HazelcastInstance hz, String loginOperationId, String sid,
                    Instant refreshExpiresAt) {
                if (failOnce.compareAndSet(true, false)) {
                    throw new AuthStateUnavailableException();
                }
                super.indexLoginSession(hz, loginOperationId, sid, refreshExpiresAt);
            }
        };
        var created = createTicket(null, UUID.randomUUID().toString());
        var waiting = store.nextWaitingTicket().orElseThrow();
        recoveringStore.restoreActiveTicketReservations();
        String permitKey = recoveringStore.reserveBcryptPermit(waiting.ticketId()).orElseThrow();
        assertThat(recoveringStore.admit(waiting, permitKey)).isTrue();
        String loginOperationId = UUID.randomUUID().toString();
        var claim = recoveringStore.claimReadyTicket(created.queueCookie(), loginOperationId);
        recoveringStore.bindAuthenticationIdentity(claim, "email-hmac");
        recoveringStore.bindAuthenticatedUser(claim, 59L);
        String sid = claim.sid();

        assertThatThrownBy(() -> recoveringStore.createSession(59L, sid, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), loginOperationId, "encrypted-token", Instant.now().plusSeconds(3600),
                recoveringStore.newAbsoluteSessionExpiry(), "test device", claim.ticketId(), claim.claimId(),
                claim.claimLeaseUntil())).isInstanceOf(AuthStateUnavailableException.class);
        recoveringStore.releaseBcryptPermit(claim.ticketId(), claim.bcryptPermitKey());
        var sessions = member.<String, HazelcastAuthStateStore.AuthSession>getMap("auth-sessions-v2");
        assertThat(sessions.get(sid).status()).isEqualTo(HazelcastAuthStateStore.SessionStatus.ACTIVE);
        assertThat(member.<String, String>getMap("auth-login-session-index-v2").get(loginOperationId)).isNull();

        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var latestClaim = tickets.get(claim.ticketId());
        var expiredClaim = latestClaim.withState(HazelcastAuthStateStore.TicketStatus.VERIFYING, null,
                loginOperationId, latestClaim.claimId(), Instant.now().minusSeconds(1), null, permitKey, sid);
        assertThat(tickets.replace(claim.ticketId(), latestClaim, expiredClaim)).isTrue();
        recoveringStore.reconcileVerifyingTickets();

        assertThat(member.<String, String>getMap("auth-login-session-index-v2").get(loginOperationId))
                .isEqualTo(sid);
        assertThat(tickets.get(claim.ticketId()).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.CONSUMED);
        assertThat(tickets.get(claim.ticketId()).loginOutcome())
                .isEqualTo(HazelcastAuthStateStore.LoginOutcome.SUCCESS);
    }

    @Test
    void recoveredAdmittingTicketBecomesReadyWithItsExistingPermit() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var waiting = store.nextWaitingTicket().orElseThrow();
        String permitKey = store.reserveBcryptPermit(waiting.ticketId()).orElseThrow();
        var admitting = waiting.withState(HazelcastAuthStateStore.TicketStatus.ADMITTING,
                null, null, null, null, null, permitKey, null);
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        assertThat(tickets.replace(waiting.ticketId(), waiting, admitting)).isTrue();

        store.reconcileVerifyingTickets();

        assertThat(ticket(created).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.READY);
        assertThat(store.ownsBcryptPermit(waiting.ticketId(), permitKey)).isTrue();
    }

    @Test
    void admittingTicketWithoutLocalPermitReturnsToFifoWaiting() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var waiting = store.nextWaitingTicket().orElseThrow();
        var admitting = waiting.withState(HazelcastAuthStateStore.TicketStatus.ADMITTING,
                null, null, null, null, null, "lost-local-permit", null);
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        assertThat(tickets.replace(waiting.ticketId(), waiting, admitting)).isTrue();

        store.reconcileVerifyingTickets();

        assertThat(ticket(created).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(order.contains(new HazelcastAuthStateStore.QueueToken(waiting.ticketId(),
                waiting.enqueueGeneration()))).isTrue();
    }

    @Test
    void unknownRequeueWithExactQueueTokenRecoversToWaitingWithoutOfferingAgain() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var ticket = ticket(created);
        long generation = ticket.enqueueGeneration() + 1;
        var unknown = ticket.withEnqueue(ticket.order() + 1, generation,
                HazelcastAuthStateStore.TicketStatus.REQUEUING_UNKNOWN, null)
                .withState(HazelcastAuthStateStore.TicketStatus.REQUEUING_UNKNOWN, null, null, null,
                        Instant.now().plusSeconds(30), null, null, null);
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        assertThat(tickets.replace(ticket.ticketId(), ticket, unknown)).isTrue();
        member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2")
                .offer(new HazelcastAuthStateStore.QueueToken(ticket.ticketId(), generation));

        store.reconcileVerifyingTickets();

        assertThat(tickets.get(ticket.ticketId()).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(store.nextWaitingTicket().orElseThrow().ticketId()).isEqualTo(ticket.ticketId());
    }

    @Test
    void logoutErasesRefreshReplayCiphertextsImmediately() {
        store.restoreActiveTicketReservations();
        String sid = UUID.randomUUID().toString();
        String loginOperationId = UUID.randomUUID().toString();
        var active = store.createSession(54L, sid, UUID.randomUUID().toString(), "refresh-hash",
                loginOperationId, "encrypted-login-refresh", Instant.now().plusSeconds(3600),
                store.newAbsoluteSessionExpiry());
        var withRefreshReplay = active.withRotation("next-jti", "next-refresh-hash", "old-jti", "old-refresh-hash",
                UUID.randomUUID().toString(), Instant.now().plusSeconds(45), "encrypted-refresh-replay",
                Instant.now().plusSeconds(3600), store.nextRefreshIdleExpiry(active.refreshAbsoluteExpiresAt()));
        assertThat(store.replaceSession(active, withRefreshReplay)).isTrue();

        var revoked = store.revokeSession(sid, 54L);

        assertThat(revoked.encryptedReplayRefreshToken()).isNull();
        assertThat(revoked.encryptedLoginRecoveryRefreshToken()).isNull();
    }

    @Test
    void createSessionFailsBeforeStartupRestoreAndDoesNotTriggerSessionMapScan() {
        store.restoreActiveTicketReservations();
        store.createSession(55L, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "existing-hash",
                UUID.randomUUID().toString(), "existing-ciphertext", Instant.now().plusSeconds(3600),
                store.newAbsoluteSessionExpiry());

        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        AuthStateProperties bounded = new AuthStateProperties(properties.hmacKey(), properties.replayEncryptionKey(),
                properties.queueCookieName(), properties.sessionIdleSeconds(), properties.sessionAbsoluteSeconds(),
                properties.queueTicketSeconds(), properties.readyTicketSeconds(), properties.queueTombstoneSeconds(),
                properties.queueCapacity(), properties.maxDeviceSessions(), properties.localAuthConcurrency(),
                properties.localBcryptConcurrency(), properties.globalBcryptPermits(),
                properties.bcryptPermitLeaseSeconds(), properties.queueCookieSeconds(),
                properties.statusMinimumIntervalSeconds(), properties.queueCreateRequestsPerMinute(),
                properties.operationRecordCapacity(), properties.idempotencyIdleHorizonSeconds(),
                properties.expectedHazelcastMembers(), 1, properties.revokedSessionRetentionSeconds(),
                properties.refreshReplayWindowSeconds());
        AtomicInteger sessionScans = new AtomicInteger();
        HazelcastAuthStateStore startupStore = new HazelcastAuthStateStore(provider, bounded) {
            @Override
            protected void restoreSessionCount(IMap<String, AuthSession> sessions) {
                sessionScans.incrementAndGet();
                super.restoreSessionCount(sessions);
            }
        };

        assertThatThrownBy(() -> startupStore.createSession(56L, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "new-hash", UUID.randomUUID().toString(), "new-ciphertext",
                Instant.now().plusSeconds(3600), startupStore.newAbsoluteSessionExpiry()))
                .isInstanceOf(AuthStateUnavailableException.class);
        assertThat(sessionScans).hasValue(0);

        startupStore.restoreActiveTicketReservations();

        assertThat(sessionScans).hasValue(1);
        assertThatThrownBy(() -> startupStore.createSession(56L, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "new-hash", UUID.randomUUID().toString(), "new-ciphertext",
                Instant.now().plusSeconds(3600), startupStore.newAbsoluteSessionExpiry()))
                .isInstanceOf(AuthStateUnavailableException.class);
        assertThat(sessionScans).hasValue(1);
    }

    @Test
    void boundedRecoveryIteratorsSkipRemovedEntriesAndNullValuesWithoutExceedingInspectionLimit() {
        @SuppressWarnings("unchecked")
        IMap<String, String> nullableMap = mock(IMap.class);
        when(nullableMap.getName()).thenReturn("nullable-recovery-test-map");
        List<Map.Entry<String, String>> rows = new ArrayList<>();
        rows.add(null);
        rows.add(new AbstractMap.SimpleEntry<>("expired", null));
        rows.add(Map.entry("present", "value"));
        when(nullableMap.iterator(3)).thenReturn(rows.iterator(), rows.iterator());

        assertThat(store.boundedEntries(nullableMap, 3)).containsExactly(Map.entry("present", "value"));
        assertThat(store.boundedValues(nullableMap, 3)).containsExactly("value");
    }

    @Test
    void globalSessionCapacityBoundsRowsIncludingRevokedRetention() throws InterruptedException {
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        AuthStateProperties bounded = new AuthStateProperties(properties.hmacKey(), properties.replayEncryptionKey(),
                properties.queueCookieName(), properties.sessionIdleSeconds(), properties.sessionAbsoluteSeconds(),
                properties.queueTicketSeconds(), properties.readyTicketSeconds(), properties.queueTombstoneSeconds(),
                properties.queueCapacity(), properties.maxDeviceSessions(), properties.localAuthConcurrency(),
                properties.localBcryptConcurrency(), properties.globalBcryptPermits(),
                properties.bcryptPermitLeaseSeconds(), properties.queueCookieSeconds(),
                properties.statusMinimumIntervalSeconds(), properties.queueCreateRequestsPerMinute(),
                properties.operationRecordCapacity(), properties.idempotencyIdleHorizonSeconds(),
                properties.expectedHazelcastMembers(), 1, properties.revokedSessionRetentionSeconds(),
                properties.refreshReplayWindowSeconds());
        HazelcastAuthStateStore boundedStore = new HazelcastAuthStateStore(provider, bounded);
        boundedStore.restoreActiveTicketReservations();
        String sid = UUID.randomUUID().toString();
        boundedStore.createSession(55L, sid, UUID.randomUUID().toString(), "hash-one",
                UUID.randomUUID().toString(), "encrypted-one", Instant.now().plusSeconds(3600),
                boundedStore.newAbsoluteSessionExpiry());

        assertThatThrownBy(() -> boundedStore.createSession(56L, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "hash-two", UUID.randomUUID().toString(), "encrypted-two",
                Instant.now().plusSeconds(3600), boundedStore.newAbsoluteSessionExpiry()))
                .isInstanceOf(AuthStateUnavailableException.class);

        assertThat(boundedStore.revokeSession(sid, 55L)).isNotNull();
        assertThatThrownBy(() -> boundedStore.createSession(56L, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "hash-two", UUID.randomUUID().toString(), "encrypted-two",
                Instant.now().plusSeconds(3600), boundedStore.newAbsoluteSessionExpiry()))
                .isInstanceOf(AuthStateUnavailableException.class);

        var sessions = member.<String, HazelcastAuthStateStore.AuthSession>getMap("auth-sessions-v2");
        assertThat(sessions.setTtl(sid, 1, TimeUnit.SECONDS)).isTrue();
        HazelcastAuthStateStore.AuthSession admitted = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (admitted == null && System.nanoTime() < deadline) {
            Thread.sleep(100);
            try {
                admitted = boundedStore.createSession(56L, UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), "hash-two", UUID.randomUUID().toString(), "encrypted-two",
                        Instant.now().plusSeconds(3600), boundedStore.newAbsoluteSessionExpiry());
            } catch (AuthStateUnavailableException unavailable) {
            }
        }
        assertThat(admitted).isNotNull();
    }

    @Test
    void reconciliationRevokesExpiredSessionWithoutReleasingRetainedMapCapacity() {
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        AuthStateProperties bounded = new AuthStateProperties(properties.hmacKey(), properties.replayEncryptionKey(),
                properties.queueCookieName(), properties.sessionIdleSeconds(), properties.sessionAbsoluteSeconds(),
                properties.queueTicketSeconds(), properties.readyTicketSeconds(), properties.queueTombstoneSeconds(),
                properties.queueCapacity(), properties.maxDeviceSessions(), properties.localAuthConcurrency(),
                properties.localBcryptConcurrency(), properties.globalBcryptPermits(),
                properties.bcryptPermitLeaseSeconds(), properties.queueCookieSeconds(),
                properties.statusMinimumIntervalSeconds(), properties.queueCreateRequestsPerMinute(),
                properties.operationRecordCapacity(), properties.idempotencyIdleHorizonSeconds(),
                properties.expectedHazelcastMembers(), 1, properties.revokedSessionRetentionSeconds(),
                properties.refreshReplayWindowSeconds());
        HazelcastAuthStateStore boundedStore = new HazelcastAuthStateStore(provider, bounded);
        boundedStore.restoreActiveTicketReservations();
        String sid = UUID.randomUUID().toString();
        var active = boundedStore.createSession(57L, sid, UUID.randomUUID().toString(), "hash-one",
                UUID.randomUUID().toString(), "encrypted-one", Instant.now().plusSeconds(3600),
                boundedStore.newAbsoluteSessionExpiry());
        var expired = new HazelcastAuthStateStore.AuthSession(active.userId(), active.sid(), active.userAgentSummary(),
                active.status(), active.currentJtiHash(), active.currentRefreshHash(), active.previousJtiHash(),
                active.previousRefreshHash(), active.lastRefreshOperationId(), active.replayUntil(),
                active.encryptedReplayRefreshToken(), active.loginOperationId(), active.loginRecoveryUntil(),
                active.loginRecoveryGeneration(), active.loginRecoveryRefreshHash(),
                active.encryptedLoginRecoveryRefreshToken(), active.generation(), active.createdAt(),
                active.lastRefreshedAt(), active.accessExpiresAt(), Instant.now().minusSeconds(1),
                active.refreshAbsoluteExpiresAt(), active.revokedAt());
        var sessions = member.<String, HazelcastAuthStateStore.AuthSession>getMap("auth-sessions-v2");
        assertThat(sessions.replace(sid, active, expired)).isTrue();

        boundedStore.reconcileVerifyingTickets();

        assertThat(boundedStore.getSession(sid).status()).isEqualTo(HazelcastAuthStateStore.SessionStatus.REVOKED);
        assertThatThrownBy(() -> boundedStore.createSession(58L, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "hash-two", UUID.randomUUID().toString(), "encrypted-two",
                Instant.now().plusSeconds(3600), boundedStore.newAbsoluteSessionExpiry()))
                .isInstanceOf(AuthStateUnavailableException.class);
    }

    @Test
    void expiredSessionEntryDoesNotKeepItsDeviceSlotReserved() {
        String firstSid = UUID.randomUUID().toString();
        createSession(51L, firstSid);
        member.<String, HazelcastAuthStateStore.AuthSession>getMap("auth-sessions-v2").remove(firstSid);
        store.reconcileVerifyingTickets();

        String nextSid = UUID.randomUUID().toString();
        createSession(51L, nextSid);

        assertThat(store.activeSessions(51L)).extracting(HazelcastAuthStateStore.AuthSession::sid)
                .containsExactly(nextSid);
        assertThat(member.<String, HazelcastAuthStateStore.UserSessionIndex>getMap("auth-user-session-index-v2")
                .get("51").sessions()).containsOnlyKeys(nextSid);
    }

    @Test
    void retryingRevocationReleasesAnyStaleUserIndexSlot() {
        store.restoreActiveTicketReservations();
        String sid = UUID.randomUUID().toString();
        createSession(52L, sid);
        store.revokeSession(sid, 52L);
        var indexes = member.<String, HazelcastAuthStateStore.UserSessionIndex>getMap("auth-user-session-index-v2");
        indexes.put("52", new HazelcastAuthStateStore.UserSessionIndex(java.util.Map.of(sid,
                new HazelcastAuthStateStore.IndexSlot(HazelcastAuthStateStore.SlotStatus.ACTIVE, null))));

        store.revokeSession(sid, 52L);

        assertThat(indexes.get("52")).isNull();
    }

    @Test
    void cookieBindingsKeepTheirOriginalAbsoluteRetentionOnReuse() {
        String key = UUID.randomUUID().toString();
        var created = createTicket(null, key);
        var bindings = member.<String, String>getMap("login-queue-cookie-bindings-v2");
        String cookieHash = bindings.entrySet().stream()
                .filter(entry -> entry.getValue().equals(created.view().ticketId()))
                .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
        var initial = bindings.getEntryView(cookieHash);
        assertThat(initial).isNotNull();
        assertThat(initial.getExpirationTime()).isGreaterThan(System.currentTimeMillis());

        createTicket(null, key);

        assertThat(bindings.getEntryView(cookieHash).getExpirationTime())
                .isEqualTo(initial.getExpirationTime());
    }

    @Test
    void queueFullRemovesOnlyClaimAndSameKeyCanRetryAfterCapacityOpens() {
        List<HazelcastAuthStateStore.CreateTicket> created = new ArrayList<>();
        for (int index = 0; index < properties.maxActiveTickets(); index++) {
            created.add(createTicket(null, UUID.randomUUID().toString()));
        }
        String retryKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> createTicket(null, retryKey))
                .isInstanceOf(AuthStateTooManyRequestsException.class)
                .hasMessage("QUEUE_FULL");

        store.cancelCurrentTicket(created.get(0).queueCookie());
        var retried = createTicket(null, retryKey);
        assertThat(retried.view().status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
    }

    @Test
    void pendingCreateBacklogUsesIndependentBoundedRecoveryCursor() {
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        Instant now = Instant.now();
        for (int index = 0; index < 250; index++) {
            String id = "recovery-" + UUID.randomUUID();
            var operation = HazelcastAuthStateStore.CreateOperation.accepted(id, "cookie", now);
            var ticket = new HazelcastAuthStateStore.Ticket(id, 0, now, now.plusSeconds(3600), null,
                    HazelcastAuthStateStore.TicketStatus.CREATING, null, id, null, null, null, null, null, 1);
            operations.put(id, operation);
            tickets.put(id, ticket);
        }

        for (int pass = 0; pass < 4; pass++) {
            long before = store.createRecoveryEntriesScanned();
            store.reconcileVerifyingTickets();
            assertThat(store.createRecoveryEntriesScanned() - before).isLessThanOrEqualTo(100);
        }

        assertThat(operations.values()).filteredOn(op -> op.ticketId().startsWith("recovery-"))
                .allMatch(op -> op.status() == HazelcastAuthStateStore.CreateOperationStatus.ACCEPTED);
        assertThat(tickets.values()).filteredOn(ticket -> ticket.ticketId().startsWith("recovery-"))
                .allMatch(ticket -> ticket.status() == HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(tickets.values()).filteredOn(ticket -> ticket.ticketId().startsWith("recovery-"))
                .allMatch(ticket -> order.contains(new HazelcastAuthStateStore.QueueToken(ticket.ticketId(),
                        ticket.enqueueGeneration())));
    }

    @Test
    void acceptedCreateWithCreatingTicketAndMissingTokenRetriesSameGeneration() {
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        String id = "accepted-creating-" + UUID.randomUUID();
        Instant now = Instant.now();
        var operation = HazelcastAuthStateStore.CreateOperation.accepted(id, "cookie", now);
        var ticket = new HazelcastAuthStateStore.Ticket(id, 0, now, now.plusSeconds(3600), null,
                HazelcastAuthStateStore.TicketStatus.CREATING, null, id, null, null, null, null, null, 1);
        operations.put(id, operation);
        tickets.put(id, ticket);

        store.reconcileVerifyingTickets();
        store.reconcileVerifyingTickets();

        assertThat(tickets.get(id).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(tickets.get(id).enqueueGeneration()).isEqualTo(1);
        assertThat(operations.get(id).status()).isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.ACCEPTED);
        assertThat(order.stream().filter(token -> token.equals(new HazelcastAuthStateStore.QueueToken(id, 1)))
                .count()).isEqualTo(1);
    }

    @Test
    void acceptedCreateRecoveryResumesAfterCrashBetweenCasAndQueueAppend() {
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        AtomicBoolean stopBeforeOffer = new AtomicBoolean(true);
        HazelcastAuthStateStore crashableStore = new HazelcastAuthStateStore(provider, properties) {
            @Override
            void beforeCreateQueueOffer(String operationId) {
                if (stopBeforeOffer.get()) {
                    throw new IllegalStateException("simulated process stop");
                }
            }
        };
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        String id = "accepted-create-crash-" + UUID.randomUUID();
        Instant now = Instant.now();
        var operation = HazelcastAuthStateStore.CreateOperation.accepted(id, "cookie", now);
        var ticket = new HazelcastAuthStateStore.Ticket(id, 0, now, now.plusSeconds(3600), null,
                HazelcastAuthStateStore.TicketStatus.CREATING, null, id, null, null, null, null, null, 1);
        operations.put(id, operation);
        tickets.put(id, ticket);

        assertThatThrownBy(crashableStore::reconcileVerifyingTickets).isInstanceOf(IllegalStateException.class);
        var offering = operations.get(id);
        assertThat(offering.status()).isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.OFFERING);
        assertThat(offering.reservationId()).isEqualTo("retry-same-generation:1");
        stopBeforeOffer.set(false);

        crashableStore.reconcileVerifyingTickets();

        assertThat(tickets.get(id).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(tickets.get(id).enqueueGeneration()).isEqualTo(1);
        assertThat(order.stream().filter(token -> token.equals(new HazelcastAuthStateStore.QueueToken(id, 1)))
                .count()).isEqualTo(1);
    }

    @Test
    void oldestPendingAgeRetainsLastCompleteCycleAfterRecoveryFailure() {
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        AtomicBoolean failBeforeCas = new AtomicBoolean(false);
        HazelcastAuthStateStore observableStore = new HazelcastAuthStateStore(provider, properties) {
            @Override
            synchronized List<Map.Entry<String, HazelcastAuthStateStore.CreateOperation>> boundedCreateOperations(
                    IMap<String, HazelcastAuthStateStore.CreateOperation> map) {
                if (failBeforeCas.get()) {
                    throw new IllegalStateException("simulated recovery failure");
                }
                return super.boundedCreateOperations(map);
            }
        };
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        String baselineId = "oldest-baseline-" + UUID.randomUUID();
        Instant baselineCreatedAt = Instant.now().minusSeconds(40);
        operations.put(baselineId, HazelcastAuthStateStore.CreateOperation.accepted(baselineId, "cookie",
                baselineCreatedAt).withStatus(HazelcastAuthStateStore.CreateOperationStatus.PENDING,
                        baselineCreatedAt));
        tickets.put(baselineId, new HazelcastAuthStateStore.Ticket(baselineId, 0, baselineCreatedAt,
                Instant.now().plusSeconds(3600), null, HazelcastAuthStateStore.TicketStatus.CREATING, null,
                baselineId, null, null, null, null, null, 1));
        observableStore.reconcileVerifyingTickets();
        long lastCompleteCycleAge = observableStore.oldestPendingAgeSeconds();
        assertThat(lastCompleteCycleAge).isGreaterThanOrEqualTo(30);

        String interruptedId = "oldest-interrupted-" + UUID.randomUUID();
        Instant interruptedCreatedAt = Instant.now().minusSeconds(180);
        operations.put(interruptedId, HazelcastAuthStateStore.CreateOperation.accepted(interruptedId, "cookie",
                interruptedCreatedAt).withStatus(HazelcastAuthStateStore.CreateOperationStatus.PENDING,
                        interruptedCreatedAt));
        tickets.put(interruptedId, new HazelcastAuthStateStore.Ticket(interruptedId, 0, interruptedCreatedAt,
                Instant.now().plusSeconds(3600), null, HazelcastAuthStateStore.TicketStatus.CREATING, null,
                interruptedId, null, null, null, null, null, 1));
        failBeforeCas.set(true);

        assertThatThrownBy(observableStore::reconcileVerifyingTickets).isInstanceOf(IllegalStateException.class);
        assertThat(observableStore.oldestPendingAgeSeconds()).isEqualTo(lastCompleteCycleAge);

        failBeforeCas.set(false);
        observableStore.reconcileVerifyingTickets();
        assertThat(observableStore.oldestPendingAgeSeconds()).isGreaterThan(lastCompleteCycleAge);
    }

    @Test
    void schedulerExpiresAbandonedRetryableTicketAndReleasesCapacityWithoutClientPolling() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var ticket = ticket(created);
        var abandoned = new HazelcastAuthStateStore.Ticket(ticket.ticketId(), ticket.order(), ticket.createdAt(),
                Instant.now().minusSeconds(1), null, HazelcastAuthStateStore.TicketStatus.RETRYABLE, null,
                ticket.createOperationId(), UUID.randomUUID().toString(), null, null, null, null,
                ticket.enqueueGeneration());
        assertThat(tickets.replace(ticket.ticketId(), ticket, abandoned)).isTrue();
        assertThat(store.activeTicketCount()).isEqualTo(1);

        store.reconcileVerifyingTickets();

        assertThat(tickets.get(ticket.ticketId()).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.EXPIRED);
        assertThat(operations.get(ticket.createOperationId()).status())
                .isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.TERMINAL);
        assertThat(store.activeTicketCount()).isZero();
    }

    @Test
    void schedulerExpiresAbandonedCreatingTicketAndReleasesCapacityWithoutClientPolling() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        var current = ticket(created);
        var expiredCreating = new HazelcastAuthStateStore.Ticket(current.ticketId(), current.order(),
                current.createdAt(), Instant.now().minusSeconds(1), null,
                HazelcastAuthStateStore.TicketStatus.CREATING, null, current.createOperationId(), null, null,
                null, null, null, current.enqueueGeneration());
        assertThat(tickets.replace(current.ticketId(), current, expiredCreating)).isTrue();
        order.remove(new HazelcastAuthStateStore.QueueToken(current.ticketId(), current.enqueueGeneration()));
        var accepted = operations.get(current.createOperationId());
        assertThat(operations.replace(current.createOperationId(), accepted,
                accepted.withStatus(HazelcastAuthStateStore.CreateOperationStatus.PENDING, Instant.now()))).isTrue();
        assertThat(store.activeTicketCount()).isEqualTo(1);

        store.reconcileVerifyingTickets();

        assertThat(tickets.get(current.ticketId()).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.EXPIRED);
        assertThat(operations.get(current.createOperationId()).status())
                .isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.TERMINAL);
        assertThat(
                order.contains(new HazelcastAuthStateStore.QueueToken(current.ticketId(), current.enqueueGeneration())))
                .isFalse();
        assertThat(store.activeTicketCount()).isZero();
    }

    @Test
    void schedulerDoesNotRenewMissingExpiredCreatingTicketsDuringRecovery() {
        var pendingCreated = createTicket(null, UUID.randomUUID().toString());
        var claimedCreated = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        var pendingTicket = ticket(pendingCreated);
        var claimedTicket = ticket(claimedCreated);
        Instant originalRequest = Instant.now().minusSeconds(properties.queueTicketSeconds() + 1L);
        var pending = new HazelcastAuthStateStore.CreateOperation(
                HazelcastAuthStateStore.CreateOperationStatus.PENDING, pendingTicket.ticketId(), "pending-cookie",
                null, null, null, originalRequest);
        var claimed = HazelcastAuthStateStore.CreateOperation.claimed(claimedTicket.ticketId(), "claimed-cookie",
                "owner", originalRequest, originalRequest.minusSeconds(1));
        operations.put(pendingTicket.createOperationId(), pending);
        operations.put(claimedTicket.createOperationId(), claimed);
        tickets.remove(pendingTicket.ticketId());
        tickets.remove(claimedTicket.ticketId());
        order.remove(new HazelcastAuthStateStore.QueueToken(pendingTicket.ticketId(),
                pendingTicket.enqueueGeneration()));
        order.remove(new HazelcastAuthStateStore.QueueToken(claimedTicket.ticketId(),
                claimedTicket.enqueueGeneration()));
        assertThat(store.activeTicketCount()).isEqualTo(2);

        store.reconcileVerifyingTickets();

        assertThat(tickets.get(pendingTicket.ticketId()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.EXPIRED);
        assertThat(tickets.get(pendingTicket.ticketId()).createdAt()).isEqualTo(originalRequest);
        assertThat(operations.get(pendingTicket.createOperationId()).status())
                .isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.TERMINAL);
        assertThat(tickets.get(claimedTicket.ticketId()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.EXPIRED);
        assertThat(tickets.get(claimedTicket.ticketId()).createdAt()).isEqualTo(originalRequest);
        assertThat(operations.get(claimedTicket.createOperationId()).status())
                .isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.TERMINAL);
        assertThat(store.activeTicketCount()).isZero();
    }

    @Test
    void expiredOfferingDefersRetryWhileDifferentGlobalFenceIsLive() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var fences = member.<String, HazelcastAuthStateStore.QueueOfferFence>getMap(
                "login-queue-append-fences-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        var ticket = ticket(created).withState(HazelcastAuthStateStore.TicketStatus.CREATING,
                null, null, null, null, null, null, null);
        tickets.put(ticket.ticketId(), ticket);
        order.remove(new HazelcastAuthStateStore.QueueToken(ticket.ticketId(), ticket.enqueueGeneration()));
        var accepted = operations.get(ticket.createOperationId());
        var marked = accepted.withRetryMarker(ticket.enqueueGeneration() + 1, Instant.now());
        operations.put(ticket.createOperationId(), marked);
        var foreignFence = new HazelcastAuthStateStore.QueueOfferFence(
                new HazelcastAuthStateStore.QueueToken("another-ticket", 8), Instant.now().plusSeconds(30));
        fences.put("active", foreignFence);

        store.reconcileVerifyingTickets();

        assertThat(operations.get(ticket.createOperationId())).isEqualTo(marked);
        assertThat(tickets.get(ticket.ticketId()).enqueueGeneration()).isEqualTo(ticket.enqueueGeneration());
        assertThat(fences.get("active")).isEqualTo(foreignFence);

        fences.remove("active", foreignFence);
        store.reconcileVerifyingTickets();
        assertThat(tickets.get(ticket.ticketId()).enqueueGeneration()).isEqualTo(ticket.enqueueGeneration() + 1);
        assertThat(operations.get(ticket.createOperationId()).status())
                .isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.PENDING);
    }

    @Test
    void expiredOfferingDefersRetryWhileMatchingGlobalFenceIsLive() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var fences = member.<String, HazelcastAuthStateStore.QueueOfferFence>getMap(
                "login-queue-append-fences-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        var ticket = ticket(created).withState(HazelcastAuthStateStore.TicketStatus.CREATING,
                null, null, null, null, null, null, null);
        tickets.put(ticket.ticketId(), ticket);
        order.remove(new HazelcastAuthStateStore.QueueToken(ticket.ticketId(), ticket.enqueueGeneration()));
        var accepted = operations.get(ticket.createOperationId());
        var marked = accepted.withRetryMarker(ticket.enqueueGeneration() + 1, Instant.now());
        operations.put(ticket.createOperationId(), marked);
        var matchingFence = new HazelcastAuthStateStore.QueueOfferFence(
                new HazelcastAuthStateStore.QueueToken(ticket.ticketId(), ticket.enqueueGeneration()),
                Instant.now().plusSeconds(30));
        fences.put("active", matchingFence);

        store.reconcileVerifyingTickets();

        assertThat(operations.get(ticket.createOperationId())).isEqualTo(marked);
        assertThat(tickets.get(ticket.ticketId()).enqueueGeneration()).isEqualTo(ticket.enqueueGeneration());
        assertThat(fences.get("active")).isEqualTo(matchingFence);

        fences.remove("active", matchingFence);
        store.reconcileVerifyingTickets();
        assertThat(tickets.get(ticket.ticketId()).enqueueGeneration()).isEqualTo(ticket.enqueueGeneration() + 1);
        assertThat(operations.get(ticket.createOperationId()).status())
                .isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.PENDING);
    }

    @Test
    void markerResumptionRemovesOnlyAnExpiredMatchingFenceBeforeRotation() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var fences = member.<String, HazelcastAuthStateStore.QueueOfferFence>getMap(
                "login-queue-append-fences-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        var ticket = ticket(created).withState(HazelcastAuthStateStore.TicketStatus.CREATING,
                null, null, null, null, null, null, null);
        tickets.put(ticket.ticketId(), ticket);
        order.remove(new HazelcastAuthStateStore.QueueToken(ticket.ticketId(), ticket.enqueueGeneration()));
        var accepted = operations.get(ticket.createOperationId());
        var marked = accepted.withRetryMarker(ticket.enqueueGeneration() + 1, Instant.now());
        operations.put(ticket.createOperationId(), marked);
        var expiredFence = new HazelcastAuthStateStore.QueueOfferFence(
                new HazelcastAuthStateStore.QueueToken(ticket.ticketId(), ticket.enqueueGeneration()),
                Instant.now().minusSeconds(1));
        fences.put("active", expiredFence);

        store.reconcileVerifyingTickets();

        assertThat(fences.get("active")).isNull();
        assertThat(tickets.get(ticket.ticketId()).enqueueGeneration()).isEqualTo(ticket.enqueueGeneration() + 1);
        assertThat(operations.get(ticket.createOperationId()).status())
                .isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.PENDING);
    }

    @Test
    void expiredCreateRecoveryRequeuesTicketThatReachedRetryableAfterTokenWasConsumed() {
        var created = createTicket(null, UUID.randomUUID().toString());
        String ticketId = created.view().ticketId();
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        HazelcastAuthStateStore.Ticket ticket = tickets.get(ticketId);
        HazelcastAuthStateStore.CreateOperation operation = operations.get(ticket.createOperationId());
        order.remove(new HazelcastAuthStateStore.QueueToken(ticketId, ticket.enqueueGeneration()));
        HazelcastAuthStateStore.Ticket retryable = ticket.withState(
                HazelcastAuthStateStore.TicketStatus.RETRYABLE, null, UUID.randomUUID().toString(), null,
                null, null, null, null);
        assertThat(tickets.replace(ticketId, ticket, retryable)).isTrue();
        operations.put(ticket.createOperationId(), new HazelcastAuthStateStore.CreateOperation(
                HazelcastAuthStateStore.CreateOperationStatus.OFFERING, operation.ticketId(), operation.cookieHash(),
                operation.ownerToken(), null, Instant.now().minusSeconds(1), operation.lastRequestedAt()));

        store.reconcileVerifyingTickets();

        HazelcastAuthStateStore.Ticket requeued = tickets.get(ticketId);
        assertThat(requeued.status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(requeued.enqueueGeneration()).isEqualTo(ticket.enqueueGeneration() + 1);
        assertThat(operations.get(ticket.createOperationId()).status())
                .isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.ACCEPTED);
        assertThat(order).containsExactly(
                new HazelcastAuthStateStore.QueueToken(ticketId, requeued.enqueueGeneration()));

        store.reconcileVerifyingTickets();

        assertThat(order).containsExactly(
                new HazelcastAuthStateStore.QueueToken(ticketId, requeued.enqueueGeneration()));
    }

    @Test
    void reconciliationAccumulatorCountsRetryableCasAndOfferTransitions() {
        var created = createTicket(null, UUID.randomUUID().toString());
        String ticketId = created.view().ticketId();
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        HazelcastAuthStateStore.Ticket ticket = tickets.get(ticketId);
        assertThat(order.remove(new HazelcastAuthStateStore.QueueToken(ticketId, ticket.enqueueGeneration()))).isTrue();
        HazelcastAuthStateStore.Ticket retryable = ticket.withState(
                HazelcastAuthStateStore.TicketStatus.RETRYABLE, null, UUID.randomUUID().toString(), null,
                null, null, null, null);
        assertThat(tickets.replace(ticketId, ticket, retryable)).isTrue();

        ReconciliationAccumulator observations = new ReconciliationAccumulator();
        store.reconcileVerifyingTickets(observations);

        assertThat(observations.retryableScannedCount()).isEqualTo(1);
        assertThat(observations.retryableRequeueCount(HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS))
                .isEqualTo(1);
        assertThat(observations.transitionCount(HazelcastAuthStateStore.ReconciliationStage.CLAIM_OFFERING,
                HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS)).isEqualTo(1);
        assertThat(observations.offerCount(HazelcastAuthStateStore.ReconciliationOutcome.OFFERED)).isEqualTo(1);
        assertThat(observations.transitionCount(HazelcastAuthStateStore.ReconciliationStage.OFFER_WAITING,
                HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS)).isEqualTo(1);
        assertThat(tickets.get(ticketId).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
    }

    @Test
    void reconciliationAccumulatorCountsExpiredRetryableTicketsAsScanned() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        HazelcastAuthStateStore.Ticket ticket = ticket(created);
        HazelcastAuthStateStore.Ticket expired = new HazelcastAuthStateStore.Ticket(ticket.ticketId(), ticket.order(),
                ticket.createdAt(), Instant.now().minusSeconds(1), null,
                HazelcastAuthStateStore.TicketStatus.RETRYABLE, null, ticket.createOperationId(),
                UUID.randomUUID().toString(),
                null, null, null, null, ticket.enqueueGeneration());
        assertThat(tickets.replace(ticket.ticketId(), ticket, expired)).isTrue();

        ReconciliationAccumulator observations = new ReconciliationAccumulator();
        store.reconcileVerifyingTickets(observations);

        assertThat(observations.retryableScannedCount()).isEqualTo(1);
        assertThat(observations.retryableRequeueCount(HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS)).isZero();
    }

    @Test
    void sanitizedDiagnosticPreservesThrowableShapeWithoutMessages() {
        IllegalStateException failure = new IllegalStateException("SECRET_SENTINEL");
        IllegalArgumentException cause = new IllegalArgumentException("SECRET_SENTINEL_CAUSE");
        failure.initCause(cause);
        failure.addSuppressed(new IllegalStateException("SECRET_SENTINEL_SUPPRESSED"));
        ReconciliationAccumulator observations = new ReconciliationAccumulator();

        observations.diagnostic(HazelcastAuthStateStore.ReconciliationStage.OFFER, failure);
        observations.diagnostic(HazelcastAuthStateStore.ReconciliationStage.OFFER, failure);
        String rendered = observations.diagnostics().get(HazelcastAuthStateStore.ReconciliationStage.OFFER).toString();

        assertThat(rendered).contains(IllegalStateException.class.getName(), IllegalArgumentException.class.getName(),
                "stackLocations", "cause", "suppressed");
        assertThat(rendered).doesNotContain("SECRET_SENTINEL");
        assertThat(observations.suppressedDiagnosticCount(HazelcastAuthStateStore.ReconciliationStage.OFFER))
                .isEqualTo(1);
    }

    @Test
    void retryableCasMissAndErrorAreDistinctFromResumeTransitionErrors() {
        var missedCreate = createTicket(null, UUID.randomUUID().toString());
        makeRetryable(missedCreate);
        HazelcastAuthStateStore missedStore = new HazelcastAuthStateStore(memberProvider(), properties) {
            @Override
            boolean replaceTicketState(IMap<String, HazelcastAuthStateStore.Ticket> tickets,
                    HazelcastAuthStateStore.Ticket current, HazelcastAuthStateStore.Ticket next) {
                return current.status() == HazelcastAuthStateStore.TicketStatus.RETRYABLE
                        ? false
                        : super.replaceTicketState(tickets, current, next);
            }
        };
        var miss = new HazelcastAuthStateStore.ReconciliationAccumulator();
        missedStore.reconcileVerifyingTickets(miss);
        assertThat(miss.retryableRequeueCount(HazelcastAuthStateStore.ReconciliationOutcome.MISS)).isEqualTo(1);
        assertThat(miss.retryableRequeueCount(HazelcastAuthStateStore.ReconciliationOutcome.ERROR)).isZero();

        var errorCreate = createTicket(null, UUID.randomUUID().toString());
        makeRetryable(errorCreate);
        HazelcastAuthStateStore errorStore = new HazelcastAuthStateStore(memberProvider(), properties) {
            @Override
            boolean replaceTicketState(IMap<String, HazelcastAuthStateStore.Ticket> tickets,
                    HazelcastAuthStateStore.Ticket current, HazelcastAuthStateStore.Ticket next) {
                if (current.status() == HazelcastAuthStateStore.TicketStatus.RETRYABLE) {
                    throw new IllegalStateException("CAS secret");
                }
                return super.replaceTicketState(tickets, current, next);
            }
        };
        var error = new HazelcastAuthStateStore.ReconciliationAccumulator();
        assertThatThrownBy(() -> errorStore.reconcileVerifyingTickets(error)).isInstanceOf(IllegalStateException.class);
        assertThat(error.retryableRequeueCount(HazelcastAuthStateStore.ReconciliationOutcome.ERROR)).isEqualTo(1);
        assertThat(error.retryableRequeueCount(HazelcastAuthStateStore.ReconciliationOutcome.MISS)).isZero();

        var transitionCreate = createTicket(null, UUID.randomUUID().toString());
        makeRetryable(transitionCreate);
        HazelcastAuthStateStore transitionErrorStore = new HazelcastAuthStateStore(memberProvider(), properties) {
            @Override
            boolean replaceTicketState(IMap<String, HazelcastAuthStateStore.Ticket> tickets,
                    HazelcastAuthStateStore.Ticket current, HazelcastAuthStateStore.Ticket next) {
                if (next.status() == HazelcastAuthStateStore.TicketStatus.REQUEUE_OFFERING) {
                    throw new IllegalStateException("transition secret");
                }
                return super.replaceTicketState(tickets, current, next);
            }
        };
        var transitionError = new HazelcastAuthStateStore.ReconciliationAccumulator();
        assertThatThrownBy(() -> transitionErrorStore.reconcileVerifyingTickets(transitionError))
                .isInstanceOf(IllegalStateException.class);
        assertThat(transitionError.retryableRequeueCount(HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS))
                .isEqualTo(1);
        assertThat(transitionError.retryableRequeueCount(HazelcastAuthStateStore.ReconciliationOutcome.ERROR)).isZero();
        assertThat(transitionError.transitionCount(HazelcastAuthStateStore.ReconciliationStage.CLAIM_OFFERING,
                HazelcastAuthStateStore.ReconciliationOutcome.ERROR)).isEqualTo(1);
    }

    @Test
    void queueFullFenceAndOfferErrorKeepTheirDistinctRecoveryOutcomes() {
        var fullCreate = createTicket(null, UUID.randomUUID().toString());
        var fullTicket = makeRetryable(fullCreate);
        HazelcastAuthStateStore fullStore = new HazelcastAuthStateStore(memberProvider(), properties) {
            @Override
            boolean offerQueueToken(HazelcastAuthStateStore.QueueToken token) {
                return false;
            }
        };
        var full = new HazelcastAuthStateStore.ReconciliationAccumulator();
        fullStore.reconcileVerifyingTickets(full);
        assertThat(ticket(fullCreate).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.REQUEUING);
        assertThat(full.offerCount(HazelcastAuthStateStore.ReconciliationOutcome.QUEUE_FULL)).isEqualTo(1);
        assertThat(full.transitionCount(HazelcastAuthStateStore.ReconciliationStage.OFFER_RETRY,
                HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS)).isEqualTo(1);
        member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2").remove(fullTicket.ticketId());

        var fencedCreate = createTicket(null, UUID.randomUUID().toString());
        makeRetryable(fencedCreate);
        HazelcastAuthStateStore fencedStore = new HazelcastAuthStateStore(memberProvider(), properties) {
            @Override
            boolean offerQueueToken(HazelcastAuthStateStore.QueueToken token) {
                throw new HazelcastAuthStateStore.AppendFencedException();
            }
        };
        var fenced = new HazelcastAuthStateStore.ReconciliationAccumulator();
        fencedStore.reconcileVerifyingTickets(fenced);
        assertThat(ticket(fencedCreate).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.REQUEUING);
        assertThat(fenced.offerCount(HazelcastAuthStateStore.ReconciliationOutcome.APPEND_FENCED)).isEqualTo(1);
        assertThat(fenced.offerCount(HazelcastAuthStateStore.ReconciliationOutcome.ERROR)).isZero();
        member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2").remove(
                fencedCreate.view().ticketId());

        var failedCreate = createTicket(null, UUID.randomUUID().toString());
        makeRetryable(failedCreate);
        HazelcastAuthStateStore failedStore = new HazelcastAuthStateStore(memberProvider(), properties) {
            @Override
            boolean offerQueueToken(HazelcastAuthStateStore.QueueToken token) {
                throw new IllegalStateException("offer secret");
            }
        };
        var failed = new HazelcastAuthStateStore.ReconciliationAccumulator();
        failedStore.reconcileVerifyingTickets(failed);
        assertThat(ticket(failedCreate).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.REQUEUING_UNKNOWN);
        assertThat(failed.offerCount(HazelcastAuthStateStore.ReconciliationOutcome.ERROR)).isEqualTo(1);
        assertThat(failed.transitionCount(HazelcastAuthStateStore.ReconciliationStage.OFFER_UNKNOWN,
                HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS)).isEqualTo(1);
    }

    @Test
    void followUpMissAndErrorPreserveKnownOfferAndUseUnknownFallbackOnlyForError() {
        var missedCreate = createTicket(null, UUID.randomUUID().toString());
        makeRetryable(missedCreate);
        HazelcastAuthStateStore missedStore = new HazelcastAuthStateStore(memberProvider(), properties) {
            @Override
            boolean offerQueueToken(HazelcastAuthStateStore.QueueToken token) {
                return true;
            }
            @Override
            boolean replaceTicketState(IMap<String, HazelcastAuthStateStore.Ticket> tickets,
                    HazelcastAuthStateStore.Ticket current, HazelcastAuthStateStore.Ticket next) {
                if (current.status() == HazelcastAuthStateStore.TicketStatus.REQUEUE_OFFERING
                        && next.status() == HazelcastAuthStateStore.TicketStatus.WAITING) {
                    return false;
                }
                return super.replaceTicketState(tickets, current, next);
            }
        };
        var miss = new HazelcastAuthStateStore.ReconciliationAccumulator();
        missedStore.reconcileVerifyingTickets(miss);
        assertThat(miss.offerCount(HazelcastAuthStateStore.ReconciliationOutcome.OFFERED)).isEqualTo(1);
        assertThat(miss.transitionCount(HazelcastAuthStateStore.ReconciliationStage.OFFER_WAITING,
                HazelcastAuthStateStore.ReconciliationOutcome.MISS)).isEqualTo(1);
        assertThat(ticket(missedCreate).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.REQUEUE_OFFERING);

        var errorCreate = createTicket(null, UUID.randomUUID().toString());
        makeRetryable(errorCreate);
        HazelcastAuthStateStore errorStore = new HazelcastAuthStateStore(memberProvider(), properties) {
            @Override
            boolean offerQueueToken(HazelcastAuthStateStore.QueueToken token) {
                return true;
            }
            @Override
            boolean replaceTicketState(IMap<String, HazelcastAuthStateStore.Ticket> tickets,
                    HazelcastAuthStateStore.Ticket current, HazelcastAuthStateStore.Ticket next) {
                if (current.status() == HazelcastAuthStateStore.TicketStatus.REQUEUE_OFFERING
                        && next.status() == HazelcastAuthStateStore.TicketStatus.WAITING) {
                    throw new IllegalStateException("waiting write secret");
                }
                return super.replaceTicketState(tickets, current, next);
            }
        };
        var error = new HazelcastAuthStateStore.ReconciliationAccumulator();
        errorStore.reconcileVerifyingTickets(error);
        assertThat(error.offerCount(HazelcastAuthStateStore.ReconciliationOutcome.OFFERED)).isEqualTo(1);
        assertThat(error.offerCount(HazelcastAuthStateStore.ReconciliationOutcome.ERROR)).isZero();
        assertThat(error.transitionCount(HazelcastAuthStateStore.ReconciliationStage.OFFER_WAITING,
                HazelcastAuthStateStore.ReconciliationOutcome.ERROR)).isEqualTo(1);
        assertThat(error.transitionCount(HazelcastAuthStateStore.ReconciliationStage.OFFER_UNKNOWN,
                HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS)).isEqualTo(1);
        assertThat(ticket(errorCreate).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.REQUEUING_UNKNOWN);
    }

    @Test
    void reconciliationCountsExpiredOfferingAndUnknownWaitingTransitions() {
        var offeringCreate = createTicket(null, UUID.randomUUID().toString());
        HazelcastAuthStateStore.Ticket offeringTicket = ticket(offeringCreate);
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        HazelcastAuthStateStore.Ticket expiredOffering = offeringTicket.withState(
                HazelcastAuthStateStore.TicketStatus.REQUEUE_OFFERING, null, null, null,
                Instant.now().minusSeconds(1), null, null, null);
        assertThat(tickets.replace(offeringTicket.ticketId(), offeringTicket, expiredOffering)).isTrue();
        var offeringObservations = new HazelcastAuthStateStore.ReconciliationAccumulator();
        store.reconcileVerifyingTickets(offeringObservations);
        assertThat(offeringObservations.transitionCount(HazelcastAuthStateStore.ReconciliationStage.OFFERING_UNKNOWN,
                HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS)).isEqualTo(1);
        assertThat(ticket(offeringCreate).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.REQUEUING_UNKNOWN);
        tickets.remove(offeringTicket.ticketId());

        var unknownCreate = createTicket(null, UUID.randomUUID().toString());
        HazelcastAuthStateStore.Ticket unknownTicket = ticket(unknownCreate);
        HazelcastAuthStateStore.Ticket unknown = unknownTicket.withState(
                HazelcastAuthStateStore.TicketStatus.REQUEUING_UNKNOWN, null, null, null,
                Instant.now().plusSeconds(30), null, null, null);
        assertThat(tickets.replace(unknownTicket.ticketId(), unknownTicket, unknown)).isTrue();
        var unknownObservations = new HazelcastAuthStateStore.ReconciliationAccumulator();
        store.reconcileVerifyingTickets(unknownObservations);
        assertThat(unknownObservations.transitionCount(HazelcastAuthStateStore.ReconciliationStage.UNKNOWN_WAITING,
                HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS)).isEqualTo(1);
        assertThat(ticket(unknownCreate).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
    }

    @Test
    void appendFenceCleanupFailureDoesNotRewriteOfferOrWaitingOutcome() {
        var created = createTicket(null, UUID.randomUUID().toString());
        makeRetryable(created);
        HazelcastAuthStateStore failingCleanupStore = new HazelcastAuthStateStore(memberProvider(), properties) {
            @Override
            boolean offerQueueToken(HazelcastAuthStateStore.QueueToken token) {
                return true;
            }
            @Override
            void clearAppendFence(HazelcastAuthStateStore.QueueToken token) {
                throw new IllegalStateException("cleanup secret");
            }
        };
        var observations = new HazelcastAuthStateStore.ReconciliationAccumulator();

        assertThatNoException().isThrownBy(() -> failingCleanupStore.reconcileVerifyingTickets(observations));

        assertThat(observations.offerCount(HazelcastAuthStateStore.ReconciliationOutcome.OFFERED)).isEqualTo(1);
        assertThat(observations.offerCount(HazelcastAuthStateStore.ReconciliationOutcome.ERROR)).isZero();
        assertThat(observations.transitionCount(HazelcastAuthStateStore.ReconciliationStage.OFFER_WAITING,
                HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS)).isEqualTo(1);
        assertThat(observations.transitionCount(HazelcastAuthStateStore.ReconciliationStage.OFFER_UNKNOWN,
                HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS)).isZero();
        assertThat(observations.diagnostics())
                .containsKey(HazelcastAuthStateStore.ReconciliationStage.APPEND_FENCE_CLEAR);
        assertThat(ticket(created).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
    }

    @Test
    void authRequeueCleanupFailureKeepsPriorFallbackWithoutAccumulator() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        HazelcastAuthStateStore.Ticket waiting = ticket(created);
        HazelcastAuthStateStore.Ticket retryable = waiting.withState(HazelcastAuthStateStore.TicketStatus.RETRYABLE,
                null, UUID.randomUUID().toString(), null, null, null, null, null);
        assertThat(tickets.replace(waiting.ticketId(), waiting, retryable)).isTrue();
        HazelcastAuthStateStore authStore = new HazelcastAuthStateStore(memberProvider(), properties) {
            @Override
            boolean offerQueueToken(HazelcastAuthStateStore.QueueToken token) {
                return true;
            }
            @Override
            void clearAppendFence(HazelcastAuthStateStore.QueueToken token) {
                throw new IllegalStateException("cleanup secret");
            }
        };

        assertThatNoException().isThrownBy(() -> authStore.requeueReadyTicket(tickets, retryable));

        assertThat(ticket(created).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
    }

    @Test
    void schedulerCapsRealHazelcastCleanupFailuresAtOneDiagnosticPerStagePerInvocation() {
        List<String> ticketIds = new ArrayList<>();
        var ticketMap = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        for (int index = 0; index < 100; index++) {
            String ticketId = "reconciliation-log-cap-" + UUID.randomUUID();
            Instant createdAt = Instant.now();
            ticketMap.put(ticketId, new HazelcastAuthStateStore.Ticket(ticketId, index, createdAt,
                    createdAt.plusSeconds(3600), null, HazelcastAuthStateStore.TicketStatus.RETRYABLE,
                    null, "operation-" + ticketId, UUID.randomUUID().toString(), null, null, null, null, 1));
            ticketIds.add(ticketId);
        }
        HazelcastAuthStateStore failingCleanupStore = new HazelcastAuthStateStore(memberProvider(), properties) {
            @Override
            boolean offerQueueToken(HazelcastAuthStateStore.QueueToken token) {
                return true;
            }
            @Override
            void clearAppendFence(HazelcastAuthStateStore.QueueToken token) {
                throw new IllegalStateException("SECRET_SENTINEL_CLEANUP");
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LoginQueueAdmissionScheduler scheduler = new LoginQueueAdmissionScheduler(failingCleanupStore, registry);
        Logger logger = (Logger) LoggerFactory.getLogger(LoginQueueAdmissionScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            scheduler.reconcileClaims();
            for (String ticketId : ticketIds) {
                HazelcastAuthStateStore.Ticket waiting = ticketMap.get(ticketId);
                HazelcastAuthStateStore.Ticket retryable = waiting.withState(
                        HazelcastAuthStateStore.TicketStatus.RETRYABLE, null, UUID.randomUUID().toString(), null,
                        null, null, null, null);
                assertThat(ticketMap.replace(ticketId, waiting, retryable)).isTrue();
            }
            scheduler.reconcileClaims();
        } finally {
            logger.detachAppender(appender);
        }

        var diagnosticLogs = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("reconciliation diagnostic"))
                .toList();
        var summaries = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("reconciliation summary"))
                .toList();
        assertThat(diagnosticLogs).hasSize(2);
        assertThat(summaries).hasSize(2);
        assertThat(diagnosticLogs).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("stage=append_fence_clear");
            assertThat(event.getFormattedMessage()).doesNotContain("SECRET_SENTINEL");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(summaries).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("errors=100", "suppressedDiagnostics=99"));
        assertThat(registry.get("auth.queue.reconcile.errors.suppressed_logs")
                .tag("stage", "append_fence_clear").counter().count()).isEqualTo(198);
        assertThat(registry.get("auth.queue.reconcile.transition")
                .tag("stage", "offer_waiting").tag("outcome", "success").counter().count()).isEqualTo(200);
        assertThat(registry.get("auth.queue.reconcile.offer")
                .tag("outcome", "offered").counter().count()).isEqualTo(200);
    }

    @Test
    void pendingCreateCasAllowsOnlyOneCurrentGenerationTokenAcrossMembers() throws Exception {
        String isolatedCluster = "auth-state-two-member-" + UUID.randomUUID();
        Config firstConfig = new Config();
        firstConfig.setClusterName(isolatedCluster);
        firstConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        firstConfig.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
        firstConfig.getNetworkConfig().setPort(5901);
        firstConfig.getNetworkConfig().setPortAutoIncrement(true);
        HazelcastInstance firstMember = Hazelcast.newHazelcastInstance(firstConfig);
        Config secondConfig = new Config();
        secondConfig.setClusterName(isolatedCluster);
        secondConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        String host = firstMember.getCluster().getLocalMember().getAddress().getHost();
        int port = firstMember.getCluster().getLocalMember().getAddress().getPort();
        secondConfig.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember(host + ":" + port);
        secondConfig.getNetworkConfig().setPort(5901);
        secondConfig.getNetworkConfig().setPortAutoIncrement(true);
        HazelcastInstance secondMember = Hazelcast.newHazelcastInstance(secondConfig);
        try {
            for (int attempt = 0; attempt < 50 && firstMember.getCluster().getMembers().size() != 2; attempt++) {
                Thread.sleep(100);
            }
            assertThat(firstMember.getCluster().getMembers()).hasSize(2);
            @SuppressWarnings("unchecked")
            ObjectProvider<HazelcastInstance> firstProvider = mock(ObjectProvider.class);
            when(firstProvider.getIfAvailable()).thenReturn(firstMember);
            @SuppressWarnings("unchecked")
            ObjectProvider<HazelcastInstance> secondProvider = mock(ObjectProvider.class);
            when(secondProvider.getIfAvailable()).thenReturn(secondMember);
            CyclicBarrier observedSamePendingValue = new CyclicBarrier(2);
            AuthStateProperties multiMemberProperties = new AuthStateProperties(properties.hmacKey(),
                    properties.replayEncryptionKey(), properties.queueCookieName(), properties.sessionIdleSeconds(),
                    properties.sessionAbsoluteSeconds(), properties.queueTicketSeconds(),
                    properties.readyTicketSeconds(), properties.queueTombstoneSeconds(), properties.queueCapacity(),
                    properties.maxDeviceSessions(), properties.localAuthConcurrency(),
                    properties.localBcryptConcurrency(), properties.globalBcryptPermits(),
                    properties.bcryptPermitLeaseSeconds(), properties.queueCookieSeconds(),
                    properties.statusMinimumIntervalSeconds(), properties.queueCreateRequestsPerMinute(),
                    properties.operationRecordCapacity(), properties.idempotencyIdleHorizonSeconds(), 2,
                    properties.globalSessionCapacity(), properties.revokedSessionRetentionSeconds(),
                    properties.refreshReplayWindowSeconds());
            HazelcastAuthStateStore firstStore = new HazelcastAuthStateStore(firstProvider, multiMemberProperties) {
                @Override
                void beforePendingCreateCas(String operationId) {
                    try {
                        observedSamePendingValue.await(10, TimeUnit.SECONDS);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            };
            HazelcastAuthStateStore secondStore = new HazelcastAuthStateStore(secondProvider, multiMemberProperties) {
                @Override
                void beforePendingCreateCas(String operationId) {
                    try {
                        observedSamePendingValue.await(10, TimeUnit.SECONDS);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            };
            var tickets = firstMember.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
            var operations = firstMember.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                    "login-queue-create-operations-v2");
            var order = firstMember.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
            String id = "two-member-" + UUID.randomUUID();
            Instant now = Instant.now();
            var operation = HazelcastAuthStateStore.CreateOperation.accepted(id, "cookie", now);
            operation = operation.withStatus(HazelcastAuthStateStore.CreateOperationStatus.PENDING, now);
            var ticket = new HazelcastAuthStateStore.Ticket(id, 0, now, now.plusSeconds(3600), null,
                    HazelcastAuthStateStore.TicketStatus.CREATING, null, id, null, null, null, null, null, 1);
            operations.put(id, operation);
            tickets.put(id, ticket);

            var executor = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                Future<?> first = executor.submit(() -> {
                    start.await();
                    firstStore.reconcileVerifyingTickets();
                    firstStore.processPendingCreateBatch(20);
                    return null;
                });
                Future<?> second = executor.submit(() -> {
                    start.await();
                    secondStore.reconcileVerifyingTickets();
                    secondStore.processPendingCreateBatch(20);
                    return null;
                });
                start.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            var expectedToken = new HazelcastAuthStateStore.QueueToken(id, 1);
            assertThat(order.stream().filter(expectedToken::equals).count()).isEqualTo(1);
            assertThat(tickets.get(id).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
            assertThat(operations.get(id).status()).isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.ACCEPTED);
        } finally {
            secondMember.shutdown();
            firstMember.shutdown();
        }
    }

    @Test
    void queueFullCreateRecoveryRemainsPendingUntilQueueCapacityOpens() {
        Config config = new Config();
        String isolatedCluster = "auth-state-queue-full-" + UUID.randomUUID();
        config.setClusterName(isolatedCluster);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        config.getNetworkConfig().setPort(5901);
        config.getNetworkConfig().setPortAutoIncrement(true);
        config.addQueueConfig(new QueueConfig("login-queue-order-v2").setMaxSize(1));
        HazelcastInstance limitedMember = Hazelcast.newHazelcastInstance(config);
        try {
            @SuppressWarnings("unchecked")
            ObjectProvider<HazelcastInstance> limitedProvider = mock(ObjectProvider.class);
            when(limitedProvider.getIfAvailable()).thenReturn(limitedMember);
            HazelcastAuthStateStore limitedStore = new HazelcastAuthStateStore(limitedProvider, properties);
            var tickets = limitedMember.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
            var operations = limitedMember.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                    "login-queue-create-operations-v2");
            var order = limitedMember.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
            order.offer(new HazelcastAuthStateStore.QueueToken("capacity-holder", 1));
            String id = "queue-full-" + UUID.randomUUID();
            Instant now = Instant.now();
            var operation = HazelcastAuthStateStore.CreateOperation.accepted(id, "cookie", now);
            var ticket = new HazelcastAuthStateStore.Ticket(id, 0, now, now.plusSeconds(3600), null,
                    HazelcastAuthStateStore.TicketStatus.CREATING, null, id, null, null, null, null, null, 1);
            operations.put(id, operation);
            tickets.put(id, ticket);

            limitedStore.reconcileVerifyingTickets();
            limitedStore.processPendingCreateBatch(20);

            assertThat(operations.get(id).status()).isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.PENDING);
            assertThat(operations.get(id).reservationId()).as("queue-full create operation: %s", operations.get(id))
                    .isEqualTo("retry-same-generation:1");
            assertThat(tickets.get(id).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.CREATING);
            order.clear();
            limitedStore.reconcileVerifyingTickets();
            limitedStore.processPendingCreateBatch(20);
            assertThat(operations.get(id).status()).isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.ACCEPTED);
            assertThat(tickets.get(id).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
            assertThat(tickets.get(id).enqueueGeneration()).isEqualTo(1);
            assertThat(order.stream().filter(token -> token.equals(new HazelcastAuthStateStore.QueueToken(id, 1)))
                    .count()).isEqualTo(1);
        } finally {
            limitedMember.shutdown();
        }
    }

    @Test
    void retryMarkerResumptionAcceptsTicketWonByPausedOldOwner() {
        var created = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var operations = member.<String, HazelcastAuthStateStore.CreateOperation>getMap(
                "login-queue-create-operations-v2");
        var order = member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2");
        var ticket = ticket(created).withState(HazelcastAuthStateStore.TicketStatus.CREATING,
                null, null, null, null, null, null, null);
        tickets.put(ticket.ticketId(), ticket);
        order.remove(new HazelcastAuthStateStore.QueueToken(ticket.ticketId(), ticket.enqueueGeneration()));
        var accepted = operations.get(ticket.createOperationId());
        var marked = accepted.withStatus(HazelcastAuthStateStore.CreateOperationStatus.OFFERING,
                Instant.now().minusSeconds(31)).withRetryMarker(ticket.enqueueGeneration() + 1, Instant.now());
        operations.put(ticket.createOperationId(), marked);
        @SuppressWarnings("unchecked")
        ObjectProvider<HazelcastInstance> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(member);
        HazelcastAuthStateStore raceStore = new HazelcastAuthStateStore(provider, properties) {
            @Override
            boolean replaceCreateGeneration(IMap<String, Ticket> ticketMap, Ticket expected, Ticket rotated) {
                var oldOwnerWaiting = expected.withState(TicketStatus.WAITING,
                        null, null, null, null, null, null, null);
                ticketMap.replace(expected.ticketId(), expected, oldOwnerWaiting);
                order.offer(new HazelcastAuthStateStore.QueueToken(expected.ticketId(), expected.enqueueGeneration()));
                return super.replaceCreateGeneration(ticketMap, expected, rotated);
            }
        };

        raceStore.reconcileVerifyingTickets();

        assertThat(tickets.get(ticket.ticketId()).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(tickets.get(ticket.ticketId()).enqueueGeneration()).isEqualTo(ticket.enqueueGeneration());
        assertThat(operations.get(ticket.createOperationId()).status())
                .isEqualTo(HazelcastAuthStateStore.CreateOperationStatus.ACCEPTED);
        assertThat(order).contains(new HazelcastAuthStateStore.QueueToken(ticket.ticketId(),
                ticket.enqueueGeneration()));
    }

    @Test
    void createRateLimitIsSharedAndExpiresByMinuteBucket() {
        for (int request = 0; request < 20; request++) {
            store.enforceTicketCreateRateLimit("192.0.2.10");
        }

        assertThatThrownBy(() -> store.enforceTicketCreateRateLimit("192.0.2.10"))
                .isInstanceOf(AuthStateTooManyRequestsException.class)
                .hasMessage("RATE_LIMITED");
    }

    @Test
    void createRateLimitHandlesConcurrentRequestsWithoutReturningUnavailable() throws Exception {
        int requestCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(32);
        try {
            List<Future<Boolean>> requests = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    try {
                        store.enforceTicketCreateRateLimit("192.0.2.11");
                        return true;
                    } catch (AuthStateTooManyRequestsException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            long admitted = 0;
            for (Future<Boolean> request : requests) {
                if (request.get(20, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }

            assertThat(admitted).isEqualTo(properties.queueCreateRequestsPerMinute());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedCreateReusesLiveTicketAndRecoversCookieAfterResponseLoss() {
        var initial = createTicket(null, UUID.randomUUID().toString());
        String repeatKey = UUID.randomUUID().toString();

        var repeated = createTicket(initial.queueCookie(), repeatKey);
        var recovered = createTicket(initial.queueCookie(), repeatKey);

        assertThat(repeated.view().ticketId()).isEqualTo(initial.view().ticketId());
        assertThat(recovered.view().ticketId()).isEqualTo(initial.view().ticketId());
        assertThat(recovered.queueCookie()).isEqualTo(repeated.queueCookie());
        assertThat(store.currentTicket(recovered.queueCookie()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
    }

    @Test
    void oneSchedulerPassAdmitsFifoTicketsWithoutReservingBcryptPermits() {
        List<HazelcastAuthStateStore.CreateTicket> created = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            created.add(createTicket(null, UUID.randomUUID().toString()));
        }

        assertThat(store.nextWaitingTicket().orElseThrow().ticketId())
                .isEqualTo(created.get(0).view().ticketId());
        new LoginQueueAdmissionScheduler(store).admitNextTicket();

        for (int index = 0; index < 5; index++) {
            assertThat(store.currentTicket(created.get(index).queueCookie()).status())
                    .isEqualTo(HazelcastAuthStateStore.TicketStatus.READY);
        }
        assertThat(ticket(created.get(4)).bcryptPermitKey()).isNull();

        List<HazelcastAuthStateStore.Ticket> claims = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            claims.add(store.claimReadyTicket(created.get(index).queueCookie(), UUID.randomUUID().toString()));
        }
        assertThatThrownBy(() -> store.claimReadyTicket(created.get(4).queueCookie(), UUID.randomUUID().toString()))
                .isInstanceOf(AuthStateTooManyRequestsException.class)
                .hasMessage("BCRYPT_CAPACITY");

        var releasedClaim = claims.get(0);
        store.releaseBcryptPermit(releasedClaim.ticketId(), releasedClaim.bcryptPermitKey());
        var fifthClaim = store.claimReadyTicket(created.get(4).queueCookie(), UUID.randomUUID().toString());
        assertThat(store.ownsBcryptPermit(fifthClaim.ticketId(), fifthClaim.bcryptPermitKey())).isTrue();
    }

    @Test
    void creatingHeadBlocksAdmissionOfLaterQueueTickets() {
        var first = createTicket(null, UUID.randomUUID().toString());
        var second = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var firstTicket = tickets.get(first.view().ticketId());
        var creating = firstTicket.withState(HazelcastAuthStateStore.TicketStatus.CREATING, null, null, null,
                null, null, null, null);
        assertThat(tickets.replace(firstTicket.ticketId(), firstTicket, creating)).isTrue();

        new LoginQueueAdmissionScheduler(store).admitNextTicket();

        assertThat(ticket(second).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);

        store.reconcileVerifyingTickets();
        new LoginQueueAdmissionScheduler(store).admitNextTicket();

        assertThat(ticket(first).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.READY);
        assertThat(ticket(second).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.READY);
    }

    @Test
    void expiresReadyTicketWithoutPermitAndLeavesOtherTicketsReady() {
        List<HazelcastAuthStateStore.CreateTicket> created = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            created.add(createTicket(null, UUID.randomUUID().toString()));
        }
        new LoginQueueAdmissionScheduler(store).admitNextTicket();
        var ready = ticket(created.get(0));
        var expiredReady = ready.withState(HazelcastAuthStateStore.TicketStatus.READY, null, null, null,
                null, Instant.now().minusSeconds(1), null, null);
        assertThat(member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2")
                .replace(ready.ticketId(), ready, expiredReady)).isTrue();

        store.renewLocalBcryptPermits();
        new LoginQueueAdmissionScheduler(store).admitNextTicket();

        assertThat(ticket(created.get(0)).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.EXPIRED);
        assertThat(ticket(created.get(0)).bcryptPermitKey()).isNull();
        assertThat(ticket(created.get(4)).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.READY);
    }

    @Test
    void insertionOrderWinsWhenTicketSequenceNumbersDisagree() {
        var insertedFirst = createTicket(null, UUID.randomUUID().toString());
        var insertedSecond = createTicket(null, UUID.randomUUID().toString());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var first = ticket(insertedFirst);
        var second = ticket(insertedSecond);
        assertThat(tickets.replace(first.ticketId(), first, withOrder(first, 200))).isTrue();
        assertThat(tickets.replace(second.ticketId(), second, withOrder(second, 100))).isTrue();

        var next = store.nextWaitingTicket().orElseThrow();
        assertThat(next.ticketId()).isEqualTo(insertedFirst.view().ticketId());
        assertThat(store.admit(next)).isTrue();

        assertThat(ticket(insertedFirst).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.READY);
        assertThat(ticket(insertedSecond).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
    }

    @Test
    void statusPollingDoesNotReleasePermitFromVerifyingLoginPastQueueLifetime() {
        var created = createTicket(null, UUID.randomUUID().toString());
        admitReadyTicket(store.nextWaitingTicket().orElseThrow());
        var claim = store.claimReadyTicket(created.queueCookie(), UUID.randomUUID().toString());
        var expiredQueueWindow = new HazelcastAuthStateStore.Ticket(claim.ticketId(), claim.order(),
                claim.createdAt(), Instant.now().minusSeconds(1), claim.readyExpiresAt(), claim.status(),
                claim.loginOutcome(), claim.createOperationId(), claim.loginOperationId(), claim.claimId(),
                claim.claimLeaseUntil(), claim.bcryptPermitKey(), claim.sid(), claim.enqueueGeneration());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        assertThat(tickets.replace(claim.ticketId(), claim, expiredQueueWindow)).isTrue();

        assertThat(store.currentTicket(created.queueCookie()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.VERIFYING);
        assertThat(store.ownsBcryptPermit(claim.ticketId(), claim.bcryptPermitKey())).isTrue();
    }

    @Test
    void staleReadyExpiryCannotReleasePermitAfterLoginClaimWins() {
        var created = createTicket(null, UUID.randomUUID().toString());
        admitReadyTicket(store.nextWaitingTicket().orElseThrow());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var staleReadySnapshot = ticket(created);

        var claim = store.claimReadyTicket(created.queueCookie(), UUID.randomUUID().toString());
        store.expireTicket(tickets, staleReadySnapshot);

        assertThat(ticket(created).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.VERIFYING);
        assertThat(store.ownsBcryptPermit(claim.ticketId(), claim.bcryptPermitKey())).isTrue();
    }

    @Test
    void concurrentPermitRenewalsDoNotReportHealthyContentionAsLeaseLoss() throws Exception {
        var created = createTicket(null, UUID.randomUUID().toString());
        admitReadyTicket(store.nextWaitingTicket().orElseThrow());
        var claim = store.claimReadyTicket(created.queueCookie(), UUID.randomUUID().toString());
        String permitKey = claim.bcryptPermitKey();
        int renewers = 12;
        var executor = Executors.newFixedThreadPool(renewers);
        var ready = new CountDownLatch(renewers);
        var start = new CountDownLatch(1);
        try {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < renewers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        return false;
                    }
                    return store.renewBcryptPermit(created.view().ticketId(), permitKey);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (var result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(ticket(created).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.VERIFYING);
        assertThat(store.ownsBcryptPermit(created.view().ticketId(), permitKey)).isTrue();
    }

    @Test
    void concurrentSchedulerInvocationsAdmitTicketsWithoutReservingBcryptPermits() throws Exception {
        List<HazelcastAuthStateStore.CreateTicket> created = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            created.add(createTicket(null, UUID.randomUUID().toString()));
        }
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    return false;
                }
                new LoginQueueAdmissionScheduler(store).admitNextTicket();
                return true;
            });
            var second = executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    return false;
                }
                new LoginQueueAdmissionScheduler(store).admitNextTicket();
                return true;
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        for (int index = 0; index < 5; index++) {
            assertThat(ticket(created.get(index)).status())
                    .isEqualTo(HazelcastAuthStateStore.TicketStatus.READY);
        }
        assertThat(created)
                .allSatisfy(createdTicket -> assertThat(ticket(createdTicket).bcryptPermitKey()).isNull());
    }

    @Test
    void concurrentClaimAndCancellationHaveOneTicketStateWinner() throws Exception {
        var created = createTicket(null, UUID.randomUUID().toString());
        admitReadyTicket(store.nextWaitingTicket().orElseThrow());
        String operationId = UUID.randomUUID().toString();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var claim = executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    return "NOT_STARTED";
                }
                try {
                    store.claimReadyTicket(created.queueCookie(), operationId);
                    return "CLAIMED";
                } catch (AuthStateConflictException | AuthStateTooEarlyException exception) {
                    return "REJECTED";
                }
            });
            var cancel = executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    return "NOT_STARTED";
                }
                try {
                    store.cancelCurrentTicket(created.queueCookie());
                    return "CANCELLED";
                } catch (AuthStateConflictException exception) {
                    return "REJECTED";
                }
            });
            start.countDown();
            String claimResult = claim.get(10, TimeUnit.SECONDS);
            String cancelResult = cancel.get(10, TimeUnit.SECONDS);
            var result = ticket(created);

            if (result.status() == HazelcastAuthStateStore.TicketStatus.VERIFYING) {
                assertThat(claimResult).isEqualTo("CLAIMED");
                assertThat(cancelResult).isEqualTo("REJECTED");
                assertThat(result.bcryptPermitKey()).isNotBlank();
                assertThat(store.ownsBcryptPermit(result.ticketId(), result.bcryptPermitKey())).isTrue();
            } else {
                assertThat(result.status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.CANCELLED);
                assertThat(claimResult).isEqualTo("REJECTED");
                assertThat(cancelResult).isEqualTo("CANCELLED");
                assertThat(store.ownsBcryptPermit(result.ticketId(), result.bcryptPermitKey())).isFalse();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredReadyTicketIsExpiredWithoutPermitReservation() throws Exception {
        var created = createTicket(null, UUID.randomUUID().toString());
        admitReadyTicket(store.nextWaitingTicket().orElseThrow());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var ready = ticket(created);
        var expired = ready.withState(HazelcastAuthStateStore.TicketStatus.READY, null, null, null,
                null, Instant.now().minusSeconds(1), null, null);
        assertThat(tickets.replace(ready.ticketId(), ready, expired)).isTrue();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var claim = executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    return "NOT_STARTED";
                }
                try {
                    store.claimReadyTicket(created.queueCookie(), UUID.randomUUID().toString());
                    return "CLAIMED";
                } catch (AuthStateExpiredException exception) {
                    return "EXPIRED";
                } catch (AuthStateConflictException exception) {
                    return "CONFLICT";
                }
            });
            var poll = executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    return "NOT_STARTED";
                }
                try {
                    var current = store.currentTicket(created.queueCookie());
                    return current.status() == HazelcastAuthStateStore.TicketStatus.EXPIRED ? "EXPIRED_STATE" : "READ";
                } catch (AuthStateExpiredException exception) {
                    return "EXPIRED";
                }
            });
            start.countDown();
            assertThat(claim.get(10, TimeUnit.SECONDS)).isIn("EXPIRED", "CONFLICT");
            assertThat(poll.get(10, TimeUnit.SECONDS)).isIn("EXPIRED", "EXPIRED_STATE");
        } finally {
            executor.shutdownNow();
        }
        assertThat(ticket(created).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.EXPIRED);
        assertThat(ticket(created).bcryptPermitKey()).isNull();
    }

    @Test
    void expiredVerifyingClaimReleasesCapacityAndAutomaticallyRequeuesWithoutClientLoginRetry() {
        List<HazelcastAuthStateStore.CreateTicket> created = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            created.add(createTicket(null, UUID.randomUUID().toString()));
        }
        new LoginQueueAdmissionScheduler(store).admitNextTicket();
        var claim = store.claimReadyTicket(created.get(0).queueCookie(), UUID.randomUUID().toString());
        String permitKey = claim.bcryptPermitKey();
        var expiredClaim = new HazelcastAuthStateStore.Ticket(claim.ticketId(), claim.order(), claim.createdAt(),
                claim.expiresAt(), claim.readyExpiresAt(), claim.status(), claim.loginOutcome(),
                claim.createOperationId(), claim.loginOperationId(), claim.claimId(), Instant.now().minusSeconds(1),
                claim.bcryptPermitKey(), claim.sid(), claim.enqueueGeneration());
        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        assertThat(tickets.replace(claim.ticketId(), claim, expiredClaim)).isTrue();
        store.releaseBcryptPermit(claim.ticketId(), claim.bcryptPermitKey());

        var observations = new HazelcastAuthStateStore.ReconciliationAccumulator();
        store.reconcileVerifyingTickets(observations);
        assertThat(observations.expiredClaimsScannedCount()).isEqualTo(1);
        assertThat(observations.expiredClaimsRecoveredCount()).isEqualTo(1);
        new LoginQueueAdmissionScheduler(store).admitNextTicket();

        assertThat(ticket(created.get(0)).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.RETRYABLE);
        assertThat(store.ownsBcryptPermit(claim.ticketId(), permitKey)).isFalse();
        assertThat(ticket(created.get(4)).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.READY);

        long previousGeneration = ticket(created.get(0)).enqueueGeneration();
        store.reconcileVerifyingTickets();

        var requeued = ticket(created.get(0));
        var token = new HazelcastAuthStateStore.QueueToken(requeued.ticketId(), requeued.enqueueGeneration());
        assertThat(requeued.status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThat(requeued.enqueueGeneration()).isEqualTo(previousGeneration + 1);
        assertThat(member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2").stream()
                .filter(token::equals).count()).isEqualTo(1);

        store.reconcileVerifyingTickets();
        assertThat(ticket(created.get(0)).enqueueGeneration()).isEqualTo(previousGeneration + 1);
        assertThat(member.<HazelcastAuthStateStore.QueueToken>getQueue("login-queue-order-v2").stream()
                .filter(token::equals).count()).isEqualTo(1);
    }

    @Test
    void successorTicketCreateCanRecoverLostResponseUsingOldTerminalCookie() {
        var initial = createTicket(null, UUID.randomUUID().toString());
        var initialTicket = store.nextWaitingTicket().orElseThrow();
        admitReadyTicket(initialTicket);
        var initialClaim = store.claimReadyTicket(initial.queueCookie(), UUID.randomUUID().toString());
        store.completeTicket(initialClaim, HazelcastAuthStateStore.LoginOutcome.INVALID_CREDENTIALS, null);
        store.releaseBcryptPermit(initialClaim.ticketId(), initialClaim.bcryptPermitKey());

        String successorKey = UUID.randomUUID().toString();
        var successor = createTicket(initial.queueCookie(), successorKey);
        var recovered = createTicket(initial.queueCookie(), successorKey);

        assertThat(successor.queueCookie()).isNotEqualTo(initial.queueCookie());
        assertThat(recovered.queueCookie()).isEqualTo(successor.queueCookie());
        assertThat(recovered.view().ticketId()).isEqualTo(successor.view().ticketId());
        assertThat(store.currentTicket(recovered.queueCookie()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
    }

    @Test
    void loginRecoveryRequiresConsumedSuccessAndUnchangedActiveSessionGeneration() throws InterruptedException {
        store.restoreActiveTicketReservations();
        var ticket = createTicket(null, UUID.randomUUID().toString());
        admitReadyTicket(store.nextWaitingTicket().orElseThrow());
        String operationId = UUID.randomUUID().toString();
        var claim = store.claimReadyTicket(ticket.queueCookie(), operationId);
        String sid = UUID.randomUUID().toString();
        Instant accessExpiry = Instant.now().plusSeconds(3600);
        var activeSession = store.createSession(83L, sid, "initial-jti", "initial-refresh-hash", operationId,
                "encrypted-refresh-token", accessExpiry, store.newAbsoluteSessionExpiry());
        store.completeTicket(claim, HazelcastAuthStateStore.LoginOutcome.SUCCESS, sid);
        store.releaseBcryptPermit(claim.ticketId(), claim.bcryptPermitKey());

        assertThat(store.loginRecoverySession(ticket.queueCookie(), operationId).sid()).isEqualTo(sid);

        var rotatedSession = activeSession.withRotation("next-jti-hash", "next-refresh-hash", "old-jti-hash",
                "old-refresh-hash", UUID.randomUUID().toString(), Instant.now().plusSeconds(30),
                "encrypted-next-refresh", accessExpiry,
                store.nextRefreshIdleExpiry(activeSession.refreshAbsoluteExpiresAt()));
        assertThat(store.replaceSession(activeSession, rotatedSession)).isTrue();
        Thread.sleep(1_100);
        assertThat(store.loginRecoverySession(ticket.queueCookie(), operationId).sid()).isEqualTo(sid);
    }

    @Test
    void failedLoginClaimRequeuesOnlyForTheBoundOperationAndEmailIdentity() {
        var created = createTicket(null, UUID.randomUUID().toString());
        admitReadyTicket(store.nextWaitingTicket().orElseThrow());
        String operationId = UUID.randomUUID().toString();
        String identityHash = store.loginIdentityHash("User@example.com");
        var claim = store.claimReadyTicket(created.queueCookie(), operationId, null, identityHash);
        store.bindAuthenticationIdentity(claim, identityHash);
        store.releaseBcryptPermit(claim.ticketId(), claim.bcryptPermitKey());

        store.reconcileFailedLogin(claim);

        assertThat(store.currentTicket(created.queueCookie()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
        assertThatThrownBy(() -> store.claimReadyTicket(created.queueCookie(), UUID.randomUUID().toString(), null,
                identityHash)).isInstanceOf(AuthStateConflictException.class);
        assertThatThrownBy(() -> store.claimReadyTicket(created.queueCookie(), operationId, null,
                store.loginIdentityHash("different@example.com")))
                .isInstanceOf(AuthStateConflictException.class);
        assertThatThrownBy(() -> store.claimReadyTicket(created.queueCookie(), operationId, null, identityHash))
                .isInstanceOf(AuthStateTooEarlyException.class)
                .hasMessage("QUEUE_WAITING");
        assertThat(member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2")
                .get(claim.ticketId()).status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.WAITING);
    }

    @Test
    void invalidCredentialsMarkerDoesNotBecomeRetryableDuringClaimReconciliation() {
        var created = createTicket(null, UUID.randomUUID().toString());
        admitReadyTicket(store.nextWaitingTicket().orElseThrow());
        String operationId = UUID.randomUUID().toString();
        String identityHash = store.loginIdentityHash("user@example.com");
        var claim = store.claimReadyTicket(created.queueCookie(), operationId, null, identityHash);
        store.bindAuthenticationIdentity(claim, identityHash);
        store.markInvalidCredentials(claim);
        store.releaseBcryptPermit(claim.ticketId(), claim.bcryptPermitKey());
        assertThat(store.ownsBcryptPermit(claim.ticketId(), claim.bcryptPermitKey())).isFalse();

        store.reconcileFailedLogin(claim);

        var terminal = store.currentTicket(created.queueCookie());
        assertThat(terminal.status()).isEqualTo(HazelcastAuthStateStore.TicketStatus.CONSUMED);
        assertThat(terminal.loginOutcome()).isEqualTo(HazelcastAuthStateStore.LoginOutcome.INVALID_CREDENTIALS);
    }

    @Test
    void expiredClaimRecoveryRunsBeforeStatusReadRateLimit() {
        var created = createTicket(null, UUID.randomUUID().toString());
        admitReadyTicket(store.nextWaitingTicket().orElseThrow());
        String operationId = UUID.randomUUID().toString();
        String identityHash = store.loginIdentityHash("user@example.com");
        var claim = store.claimReadyTicket(created.queueCookie(), operationId, null, identityHash);
        store.bindAuthenticationIdentity(claim, identityHash);
        store.releaseBcryptPermit(claim.ticketId(), claim.bcryptPermitKey());
        assertThat(store.currentTicket(created.queueCookie()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.VERIFYING);

        var tickets = member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2");
        var latestClaim = tickets.get(claim.ticketId());
        var expiredClaim = latestClaim.withState(HazelcastAuthStateStore.TicketStatus.VERIFYING, null,
                latestClaim.loginOperationId(), latestClaim.claimId(), Instant.now().minusSeconds(1), null,
                latestClaim.bcryptPermitKey(), latestClaim.sid());
        assertThat(tickets.replace(claim.ticketId(), latestClaim, expiredClaim)).isTrue();

        assertThatThrownBy(() -> store.currentTicket(created.queueCookie()))
                .isInstanceOf(AuthStateTooManyRequestsException.class)
                .hasMessage("RATE_LIMITED");
        assertThat(tickets.get(claim.ticketId()).status())
                .isEqualTo(HazelcastAuthStateStore.TicketStatus.RETRYABLE);
    }

    @Test
    void duplicateSessionCreationReturnsTheCanonicalRefreshTokenRecord() {
        store.restoreActiveTicketReservations();
        var created = createTicket(null, UUID.randomUUID().toString());
        admitReadyTicket(store.nextWaitingTicket().orElseThrow());
        String operationId = UUID.randomUUID().toString();
        String identityHash = store.loginIdentityHash("user@example.com");
        var claim = store.claimReadyTicket(created.queueCookie(), operationId, null, identityHash);
        store.bindAuthenticationIdentity(claim, identityHash);
        store.bindAuthenticatedUser(claim, 59L);
        String sid = claim.sid();
        Instant accessExpiry = Instant.now().plusSeconds(3600);
        Instant absoluteExpiry = store.newAbsoluteSessionExpiry();

        var original = store.createSession(59L, sid, "jti-1", "refresh-hash-1", operationId,
                "encrypted-refresh-1", accessExpiry, absoluteExpiry, "device", claim.ticketId(), claim.claimId(),
                claim.claimLeaseUntil());
        var duplicate = store.createSession(59L, sid, "jti-2", "refresh-hash-2", operationId,
                "encrypted-refresh-2", accessExpiry, absoluteExpiry, "device", claim.ticketId(), claim.claimId(),
                claim.claimLeaseUntil());

        assertThat(duplicate).isEqualTo(original);
        assertThat(duplicate.currentRefreshHash()).isEqualTo("refresh-hash-1");
        assertThat(duplicate.encryptedLoginRecoveryRefreshToken()).isEqualTo("encrypted-refresh-1");
        store.releaseBcryptPermit(claim.ticketId(), claim.bcryptPermitKey());
    }

    private void admitReadyTicket(HazelcastAuthStateStore.Ticket ticket) {
        assertThat(store.admit(ticket)).isTrue();
    }

    private void createSession(Long userId, String sid) {
        store.restoreActiveTicketReservations();
        store.createSession(userId, sid, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "encrypted-token", Instant.now().plusSeconds(3600),
                store.newAbsoluteSessionExpiry());
    }

    private HazelcastAuthStateStore.Ticket ticket(HazelcastAuthStateStore.CreateTicket created) {
        return member.<String, HazelcastAuthStateStore.Ticket>getMap("login-queue-tickets-v2")
                .get(created.view().ticketId());
    }

    private HazelcastAuthStateStore.Ticket withOrder(HazelcastAuthStateStore.Ticket ticket, long order) {
        return new HazelcastAuthStateStore.Ticket(ticket.ticketId(), order, ticket.createdAt(), ticket.expiresAt(),
                ticket.readyExpiresAt(), ticket.status(), ticket.loginOutcome(), ticket.createOperationId(),
                ticket.loginOperationId(), ticket.claimId(), ticket.claimLeaseUntil(), ticket.bcryptPermitKey(),
                ticket.sid(), ticket.enqueueGeneration());
    }
}
