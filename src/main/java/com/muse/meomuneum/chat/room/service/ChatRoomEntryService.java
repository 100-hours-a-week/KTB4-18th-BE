package com.muse.meomuneum.chat.room.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ChatRoomEntryService(
            ChatRoomRepository chatRoomRepository,
            ChatRoomMemberRepository memberRepository,
            UserRepository userRepository,
            LocationResolutionTokenProvider tokenProvider,
            Clock clock) {
        this.chatRoomRepository = chatRoomRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ChatRoomResponse findByRegion(Long regionId) {
        ChatRoom chatRoom = chatRoomRepository.findByRegion_Id(regionId)
                .filter(ChatRoom::isActive)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.CHAT_ROOM_NOT_FOUND));
        return ChatRoomResponse.from(chatRoom);
    }

    @Transactional(noRollbackFor = ChatRoomException.class)
    public ChatRoomJoinResult join(Long userId, Long roomId, String locationResolutionToken) {
        LocationResolutionClaims claims = tokenProvider.validate(locationResolutionToken, userId);
        User user = userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.USER_NOT_FOUND));
        ChatRoomMember activeMembership = memberRepository.findByUser_IdAndDeletedAtIsNull(userId).orElse(null);
        List<Long> roomIdsToLock = roomIdsToLock(activeMembership, roomId);
        List<ChatRoom> lockedRooms = chatRoomRepository.findAllByIdForUpdate(roomIdsToLock);
        ChatRoom targetRoom = lockedRooms.stream()
                .filter(room -> room.getId().equals(roomId))
                .filter(ChatRoom::isActive)
                .findFirst()
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.CHAT_ROOM_NOT_FOUND));
        validateLocation(claims, targetRoom);

        if (activeMembership != null && activeMembership.getChatRoom().getId().equals(roomId)) {
            return new ChatRoomJoinResult(ChatRoomMembershipResponse.from(activeMembership), false);
        }

        if (activeMembership != null) {
            activeMembership.leave(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            memberRepository.saveAndFlush(activeMembership);
        }

        int activeMemberCount = memberRepository.findAllActiveByChatRoomIdForUpdate(roomId).size();
        if (activeMemberCount >= targetRoom.getCapacity()) {
            throw new ChatRoomException(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED);
        }

        LocalDateTime joinedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        ChatRoomMember membership = memberRepository.saveAndFlush(ChatRoomMember.join(user, targetRoom, joinedAt));
        return new ChatRoomJoinResult(ChatRoomMembershipResponse.from(membership), true);
    }

    private List<Long> roomIdsToLock(ChatRoomMember activeMembership, Long targetRoomId) {
        if (activeMembership == null || activeMembership.getChatRoom().getId().equals(targetRoomId)) {
            return List.of(targetRoomId);
        }
        return List.of(activeMembership.getChatRoom().getId(), targetRoomId).stream()
                .sorted()
                .toList();
    }

    private void validateLocation(LocationResolutionClaims claims, ChatRoom targetRoom) {
        Long targetRegionId = targetRoom.getRegion().getId();
        String targetRegionCode = targetRoom.getRegion().getCode();
        if (!targetRegionId.equals(claims.sigunguRegionId())
                || !targetRegionCode.equals(claims.sigunguCode())) {
            throw new ChatRoomException(ChatRoomErrorCode.LOCATION_REGION_MISMATCH);
        }
    }
}
