package com.muse.meomuneum.chat.room.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.chat.ban.service.ChatBanStorageService;
import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.connection.ChatSessionAccessPolicy;
import com.muse.meomuneum.chat.connection.ChatSessionLogoutEvent;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.member.domain.ChatRoomMember;
import com.muse.meomuneum.chat.member.repository.ChatRoomMemberRepository;
import com.muse.meomuneum.chat.room.domain.ChatRoom;
import com.muse.meomuneum.chat.room.dto.ChatRoomMembershipResponse;
import com.muse.meomuneum.chat.room.dto.ChatRoomResponse;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.repository.ChatRoomRepository;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;
import com.muse.meomuneum.chat.view.ChatViewStorageService;
import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;

@Service
public class ChatRoomEntryService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final LocationResolutionTokenProvider tokenProvider;
    private final Clock clock;
    private final ChatPresenceRegistry presence;
    private final ChatSocketSessions sockets;
    private final ChatBanStorageService bans;
    private final TransactionTemplate transactions;
    private final ChatViewStorageService viewStorage;
    private final ChatViewLifecycle views;
    private final ChatSessionAccessPolicy sessionAccess;
    private final ChatAuthSessionRegistry authSessions;

    public ChatRoomEntryService(ChatRoomRepository chatRoomRepository, ChatRoomMemberRepository memberRepository,
            UserRepository userRepository, LocationResolutionTokenProvider tokenProvider, Clock clock,
            ChatPresenceRegistry presence,
            ChatSocketSessions sockets, ChatBanStorageService bans, PlatformTransactionManager transactionManager,
            ChatViewStorageService viewStorage, ChatViewLifecycle views,
            ChatSessionAccessPolicy sessionAccess, ChatAuthSessionRegistry authSessions) {
        this.chatRoomRepository = chatRoomRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
        this.clock = clock;
        this.presence = presence;
        this.sockets = sockets;
        this.bans = bans;
        this.viewStorage = viewStorage;
        this.views = views;
        this.sessionAccess = sessionAccess;
        this.authSessions = authSessions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public ChatRoomResponse findByRegion(Long regionId) {
        ChatRoom chatRoom = chatRoomRepository.findByRegion_Id(regionId).filter(ChatRoom::isActive)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.CHAT_ROOM_NOT_FOUND));
        return ChatRoomResponse.from(chatRoom);
    }

    public ChatRoomJoinResult join(Long userId, String authSessionId, Long roomId, String locationResolutionToken) {
        requireAuthSession(authSessionId);
        LocationResolutionClaims claims = tokenProvider.validateForChat(locationResolutionToken, userId, authSessionId);
        Transition transition = presence.exclusive(() -> {
            sessionAccess.authorize(userId, authSessionId);
            Transition committed = transactions
                    .execute(status -> joinInTransaction(userId, authSessionId, roomId, claims));
            Set<String> ended = committed.previousId() == null
                    ? Set.of()
                    : presence.remove(userId, committed.previousId());
            if (committed.result() != null) {
                presence.reserveTab(userId, roomId, committed.result().membership().membershipId(),
                        committed.result().membership().viewSessionId());
            }
            return new Transition(committed.result(), committed.error(), null, ended);
        });
        sockets.close(transition.sessions(), 4100, "CHAT_LEFT");
        if (transition.error() != null) {
            throw new ChatRoomException(transition.error());
        }
        return transition.result();
    }

    private Transition joinInTransaction(Long userId, String authSessionId, Long roomId,
            LocationResolutionClaims claims) {
        User user = userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.USER_NOT_FOUND));
        checkBan(userId);
        ChatRoomMember active = memberRepository.findByUser_IdAndAuthSessionIdAndDeletedAtIsNull(userId, authSessionId)
                .orElse(null);
        ChatRoom target = chatRoomRepository.findAllByIdForUpdate(roomIdsToLock(active, roomId)).stream()
                .filter(room -> room.getId().equals(roomId)).filter(ChatRoom::isActive).findFirst()
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.CHAT_ROOM_NOT_FOUND));
        validateLocation(claims, target);
        boolean sameRoom = active != null && active.getChatRoom().getId().equals(roomId);
        Long previousId = null;
        if (active != null && !sameRoom) {
            previousId = active.getId();
            active.leave(now());
            memberRepository.saveAndFlush(active);
        }
        if (!presence.hasCapacity(userId, roomId, target.getCapacity())) {
            // Commit the confirmed departure even if the destination is full.
            return new Transition(null, ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED, previousId, Set.of());
        }
        ChatRoomMember membership = sameRoom
                ? active
                : memberRepository.saveAndFlush(ChatRoomMember.join(user, target, now(), authSessionId));
        return new Transition(new ChatRoomJoinResult(ChatRoomMembershipResponse.from(membership)
                .withView(viewStorage.create(userId, roomId, membership.getId())), !sameRoom),
                null, previousId, Set.of());
    }

    public void leaveTab(Long userId, String authSessionId, Long roomId, Long membershipId, String viewId) {
        requireAuthSession(authSessionId);
        Set<String> ended = presence.exclusive(() -> {
            transactions.executeWithoutResult(status -> ownedMembership(userId, authSessionId, roomId, membershipId));
            ChatViewLifecycle.Departure departure = views.leave(userId, roomId, membershipId, viewId);
            if (departure.lastView() && !presence.hasParticipation(membershipId)) {
                transactions.executeWithoutResult(status -> {
                    ChatRoomMember member = ownedMembership(userId, authSessionId, roomId, membershipId);
                    member.leave(now());
                    memberRepository.saveAndFlush(member);
                });
            }
            return departure.sockets();
        });
        sockets.close(ended, 4100, "CHAT_LEFT");
    }

    /** Location evidence, rather than navigation, ends all tabs of the moving device. */
    public void leaveForRegionChange(Long userId, String authSessionId, Long roomId, Long membershipId,
            String locationToken) {
        requireAuthSession(authSessionId);
        LocationResolutionClaims claims = tokenProvider.validateForChat(locationToken, userId, authSessionId);
        Set<String> ended = presence.exclusive(() -> {
            transactions.executeWithoutResult(status -> {
                ChatRoomMember member = ownedMembership(userId, authSessionId, roomId, membershipId);
                if (member.getChatRoom().getRegion().getId().equals(claims.sigunguRegionId())) {
                    throw new ChatRoomException(ChatRoomErrorCode.LOCATION_REGION_MISMATCH);
                }
                member.leave(now());
                memberRepository.saveAndFlush(member);
            });
            return presence.remove(userId, membershipId);
        });
        sockets.close(ended, 4100, "CHAT_LEFT");
    }

    private ChatRoomMember ownedMembership(Long userId, String authSessionId, Long roomId, Long membershipId) {
        userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.USER_NOT_FOUND));
        return memberRepository.findByIdAndUser_IdAndChatRoom_Id(membershipId, userId, roomId)
                .filter(member -> authSessionId.equals(member.getAuthSessionId()))
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND));
    }

    public void leave(Long userId, String authSessionId, Long roomId, Long membershipId) {
        requireAuthSession(authSessionId);
        Set<String> ended = presence.exclusive(() -> {
            transactions.executeWithoutResult(status -> {
                userRepository.findActiveByIdForUpdate(userId)
                        .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.USER_NOT_FOUND));
                ChatRoomMember membership = memberRepository.findByIdAndUser_IdAndChatRoom_Id(
                        membershipId, userId, roomId)
                        .filter(member -> authSessionId.equals(member.getAuthSessionId()))
                        .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND));
                membership.leave(now());
                memberRepository.saveAndFlush(membership);
            });
            return presence.remove(userId, membershipId);
        });
        sockets.close(ended, 4100, "CHAT_LEFT");
    }

    @EventListener
    public void loggedOut(ChatSessionLogoutEvent event) {
        requireAuthSession(event.authSessionId());
        sessionAccess.revoke(event.userId(), event.authSessionId());
        sockets.close(authSessions.connections(event.userId(), event.authSessionId()), 4100, "CHAT_LOGGED_OUT");
        Set<String> ended = presence.exclusive(() -> {
            sessionAccess.revoke(event.userId(), event.authSessionId());
            List<Long> membershipIds = transactions.execute(status -> {
                userRepository.findActiveByIdForUpdate(event.userId());
                List<ChatRoomMember> members = memberRepository.findAllByUser_IdAndAuthSessionId(
                        event.userId(), event.authSessionId());
                members.forEach(member -> member.leave(now()));
                memberRepository.saveAllAndFlush(members);
                return members.stream().map(ChatRoomMember::getId).toList();
            });
            Set<String> sessions = new HashSet<>(authSessions.connections(event.userId(), event.authSessionId()));
            membershipIds.forEach(id -> sessions.addAll(presence.remove(event.userId(), id)));
            return Set.copyOf(sessions);
        });
        sockets.close(ended, 4100, "CHAT_LOGGED_OUT");
    }

    public void leaveAll(Long userId) {
        leaveAll(userId, 4100, "CHAT_LEFT");
    }

    public void leaveAll(Long userId, int code, String reason) {
        Set<String> ended = presence.exclusive(() -> {
            List<Long> membershipIds = transactions.execute(status -> {
                userRepository.findActiveByIdForUpdate(userId);
                List<ChatRoomMember> active = memberRepository.findAllByUser_IdAndDeletedAtIsNull(userId);
                active.forEach(member -> member.leave(now()));
                memberRepository.saveAllAndFlush(active);
                return active.stream().map(ChatRoomMember::getId).toList();
            });
            Set<String> sessions = new HashSet<>();
            membershipIds.forEach(id -> sessions.addAll(presence.remove(userId, id)));
            return Set.copyOf(sessions);
        });
        sockets.close(ended, code, reason);
    }

    public void connect(Long userId, String authSessionId, Long roomId, Long membershipId, String sessionId) {
        requireAuthSession(authSessionId);
        presence.exclusive(() -> {
            sessionAccess.authorize(userId, authSessionId);
            Integer capacity = transactions.execute(status -> {
                ChatRoomMember member = authorizedMembership(userId, roomId, membershipId);
                if (!authSessionId.equals(member.getAuthSessionId())) {
                    throw new ChatRoomException(ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND);
                }
                return member.getChatRoom().getCapacity();
            });
            presence.connect(userId, roomId, membershipId, capacity, sessionId);
            return null;
        });
    }

    public void authorizeConnection(Long userId, Long roomId, Long membershipId, String sessionId) {
        presence.exclusive(() -> transactions.execute(status -> {
            ChatRoomMember membership = authorizedMembership(userId, roomId, membershipId);
            sessionAccess.authorize(userId, membership.getAuthSessionId());
            if (!presence.ownsConnection(userId, membershipId, sessionId)) {
                throw new ChatRoomException(ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND);
            }
            return null;
        }));
    }

    private ChatRoomMember authorizedMembership(Long userId, Long roomId, Long membershipId) {
        userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.USER_NOT_FOUND));
        checkBan(userId);
        return memberRepository.findByIdAndUser_IdAndChatRoom_Id(membershipId, userId, roomId)
                .filter(ChatRoomMember::isActive).filter(member -> member.getChatRoom().isActive())
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND));
    }

    private void requireAuthSession(String authSessionId) {
        if (!ChatAuthSessionRegistry.isValidSessionId(authSessionId)) {
            throw new ChatRoomException(ChatRoomErrorCode.AUTH_REQUIRED);
        }
    }

    private void checkBan(Long userId) {
        if (bans.hasActiveBan(userId)) {
            throw new ChatRoomException(ChatRoomErrorCode.CHAT_BANNED, bans.activeBanUntil(userId).orElse(null));
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record Transition(ChatRoomJoinResult result, ChatRoomErrorCode error, Long previousId,
            Set<String> sessions) {
    }

    private List<Long> roomIdsToLock(ChatRoomMember activeMembership, Long targetRoomId) {
        if (activeMembership == null || activeMembership.getChatRoom().getId().equals(targetRoomId)) {
            return List.of(targetRoomId);
        }
        return List.of(activeMembership.getChatRoom().getId(), targetRoomId).stream().sorted().toList();
    }

    private void validateLocation(LocationResolutionClaims claims, ChatRoom targetRoom) {
        Long targetRegionId = targetRoom.getRegion().getId();
        String targetRegionCode = targetRoom.getRegion().getCode();
        if (!targetRegionId.equals(claims.sigunguRegionId()) || !targetRegionCode.equals(claims.sigunguCode())) {
            throw new ChatRoomException(ChatRoomErrorCode.LOCATION_REGION_MISMATCH);
        }
    }
}
