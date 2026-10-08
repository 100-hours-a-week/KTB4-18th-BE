package com.muse.meomuneum.chat.room.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.auth.service.ChatLogoutEvent;
import com.muse.meomuneum.chat.ban.service.ChatBanStorageService;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.member.domain.ChatRoomMember;
import com.muse.meomuneum.chat.member.repository.ChatRoomMemberRepository;
import com.muse.meomuneum.chat.room.domain.ChatRoom;
import com.muse.meomuneum.chat.room.dto.ChatRoomMembershipResponse;
import com.muse.meomuneum.chat.room.dto.ChatRoomResponse;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.repository.ChatRoomRepository;
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

    public ChatRoomEntryService(ChatRoomRepository chatRoomRepository, ChatRoomMemberRepository memberRepository,
            UserRepository userRepository, LocationResolutionTokenProvider tokenProvider, Clock clock,
            ChatPresenceRegistry presence,
            ChatSocketSessions sockets, ChatBanStorageService bans, PlatformTransactionManager transactionManager) {
        this.chatRoomRepository = chatRoomRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
        this.clock = clock;
        this.presence = presence;
        this.sockets = sockets;
        this.bans = bans;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public ChatRoomResponse findByRegion(Long regionId) {
        ChatRoom chatRoom = chatRoomRepository.findByRegion_Id(regionId).filter(ChatRoom::isActive)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.CHAT_ROOM_NOT_FOUND));
        return ChatRoomResponse.from(chatRoom);
    }

    public ChatRoomJoinResult join(Long userId, Long roomId, String locationResolutionToken) {
        LocationResolutionClaims claims = tokenProvider.validate(locationResolutionToken, userId);
        Transition transition = presence.exclusive(() -> {
            Transition committed = transactions.execute(status -> joinInTransaction(userId, roomId, claims));
            Set<String> ended = committed.previousId() == null
                    ? Set.of()
                    : presence.remove(userId, committed.previousId());
            if (committed.result() != null) {
                presence.reserve(userId, roomId, committed.result().membership().membershipId());
            }
            return new Transition(committed.result(), committed.error(), null, ended);
        });
        sockets.close(transition.sessions(), 4100, "CHAT_LEFT");
        if (transition.error() != null) {
            throw new ChatRoomException(transition.error());
        }
        return transition.result();
    }

    private Transition joinInTransaction(Long userId, Long roomId, LocationResolutionClaims claims) {
        User user = userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.USER_NOT_FOUND));
        checkBan(userId);
        ChatRoomMember active = memberRepository.findByUser_IdAndDeletedAtIsNull(userId).orElse(null);
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
                : memberRepository.saveAndFlush(ChatRoomMember.join(user, target, now()));
        return new Transition(new ChatRoomJoinResult(ChatRoomMembershipResponse.from(membership), !sameRoom),
                null, previousId, Set.of());
    }

    public void leave(Long userId, Long roomId, Long membershipId) {
        Set<String> ended = presence.exclusive(() -> {
            transactions.executeWithoutResult(status -> {
                userRepository.findActiveByIdForUpdate(userId)
                        .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.USER_NOT_FOUND));
                ChatRoomMember membership = memberRepository.findByIdAndUser_IdAndChatRoom_Id(
                        membershipId, userId, roomId)
                        .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND));
                membership.leave(now());
                memberRepository.saveAndFlush(membership);
            });
            return presence.remove(userId, membershipId);
        });
        sockets.close(ended, 4100, "CHAT_LEFT");
    }

    @EventListener
    public void loggedOut(ChatLogoutEvent event) {
        leaveAll(event.userId());
    }

    public void leaveAll(Long userId) {
        leaveAll(userId, 4100, "CHAT_LEFT");
    }

    public void leaveAll(Long userId, int code, String reason) {
        Set<String> ended = presence.exclusive(() -> {
            Long membershipId = transactions.execute(status -> {
                userRepository.findActiveByIdForUpdate(userId);
                ChatRoomMember active = memberRepository.findByUser_IdAndDeletedAtIsNull(userId).orElse(null);
                if (active == null) {
                    return null;
                }
                active.leave(now());
                memberRepository.saveAndFlush(active);
                return active.getId();
            });
            return membershipId == null ? Set.of() : presence.remove(userId, membershipId);
        });
        sockets.close(ended, code, reason);
    }

    public void connect(Long userId, Long roomId, Long membershipId, String sessionId) {
        presence.exclusive(() -> {
            Integer capacity = transactions
                    .execute(status -> authorizedMembership(userId, roomId, membershipId).getChatRoom().getCapacity());
            presence.connect(userId, roomId, membershipId, capacity, sessionId);
            return null;
        });
    }

    public void authorizeConnection(Long userId, Long roomId, Long membershipId, String sessionId) {
        presence.exclusive(() -> transactions.execute(status -> {
            authorizedMembership(userId, roomId, membershipId);
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

    private void checkBan(Long userId) {
        if (bans.hasActiveBan(userId)) {
            throw new ChatRoomException(ChatRoomErrorCode.CHAT_BANNED);
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
