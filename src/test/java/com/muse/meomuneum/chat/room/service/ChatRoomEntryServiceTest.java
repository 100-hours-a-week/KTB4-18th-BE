package com.muse.meomuneum.chat.room.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.muse.meomuneum.chat.member.domain.ChatRoomMember;
import com.muse.meomuneum.chat.member.repository.ChatRoomMemberRepository;
import com.muse.meomuneum.chat.region.domain.Region;
import com.muse.meomuneum.chat.room.domain.ChatRoom;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.repository.ChatRoomRepository;
import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatRoomEntryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");

    private ChatRoomRepository chatRoomRepository;
    private ChatRoomMemberRepository memberRepository;
    private UserRepository userRepository;
    private LocationResolutionTokenProvider tokenProvider;
    private ChatRoomEntryService service;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        memberRepository = mock(ChatRoomMemberRepository.class);
        userRepository = mock(UserRepository.class);
        tokenProvider = mock(LocationResolutionTokenProvider.class);
        service = new ChatRoomEntryService(
                chatRoomRepository,
                memberRepository,
                userRepository,
                tokenProvider,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void returnsTheExistingMembershipForTheSameRoom() {
        User user = mock(User.class);
        ChatRoom room = room(700L, 25L, "41135", 25);
        ChatRoomMember membership = membership(900L, room);
        prepareJoin(user, room, claims(7L, 25L, "41135"));
        when(memberRepository.findByUser_IdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(membership));

        ChatRoomJoinResult result = service.join(7L, 700L, "loc_token");

        assertFalse(result.created());
        assertEquals(900L, result.membership().membershipId());
        verify(memberRepository, never()).findAllActiveByChatRoomIdForUpdate(any());
        verify(memberRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsATokenForAnotherRegionBeforeChangingMembership() {
        User user = mock(User.class);
        ChatRoom room = room(700L, 25L, "41135", 25);
        prepareJoin(user, room, claims(7L, 30L, "11680"));

        ChatRoomException exception = assertThrows(
                ChatRoomException.class,
                () -> service.join(7L, 700L, "loc_token")
        );

        assertEquals(ChatRoomErrorCode.LOCATION_REGION_MISMATCH, exception.getErrorCode());
        verify(memberRepository, never()).saveAndFlush(any());
    }

    @Test
    void endsThePreviousMembershipBeforeRejectingAFullDestination() {
        User user = mock(User.class);
        ChatRoom targetRoom = room(700L, 25L, "41135", 1);
        ChatRoom previousRoom = room(701L, 30L, "11680", 25);
        ChatRoomMember previousMembership = mock(ChatRoomMember.class);
        when(previousMembership.getChatRoom()).thenReturn(previousRoom);
        prepareJoin(user, targetRoom, claims(7L, 25L, "41135"));
        when(memberRepository.findByUser_IdAndDeletedAtIsNull(7L))
                .thenReturn(Optional.of(previousMembership));
        when(memberRepository.findAllActiveByChatRoomIdForUpdate(700L)).thenReturn(List.of(mock(ChatRoomMember.class)));

        ChatRoomException exception = assertThrows(
                ChatRoomException.class,
                () -> service.join(7L, 700L, "loc_token")
        );

        assertEquals(ChatRoomErrorCode.CHAT_ROOM_CAPACITY_EXCEEDED, exception.getErrorCode());
        verify(previousMembership).leave(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(memberRepository).saveAndFlush(previousMembership);
    }

    private void prepareJoin(User user, ChatRoom room, LocationResolutionClaims claims) {
        when(tokenProvider.validate("loc_token", 7L)).thenReturn(claims);
        when(userRepository.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(chatRoomRepository.findAllByIdForUpdate(any())).thenReturn(java.util.List.of(room));
    }

    private LocationResolutionClaims claims(Long userId, Long sigunguRegionId, String sigunguCode) {
        return new LocationResolutionClaims(
                userId,
                9L,
                "41",
                sigunguRegionId,
                sigunguCode,
                NOW.plusSeconds(300)
        );
    }

    private ChatRoom room(Long roomId, Long regionId, String regionCode, int capacity) {
        Region region = mock(Region.class);
        when(region.getId()).thenReturn(regionId);
        when(region.getCode()).thenReturn(regionCode);
        ChatRoom room = mock(ChatRoom.class);
        when(room.getId()).thenReturn(roomId);
        when(room.getRegion()).thenReturn(region);
        when(room.getCapacity()).thenReturn(capacity);
        when(room.isActive()).thenReturn(true);
        return room;
    }

    private ChatRoomMember membership(Long membershipId, ChatRoom room) {
        ChatRoomMember membership = mock(ChatRoomMember.class);
        when(membership.getId()).thenReturn(membershipId);
        when(membership.getChatRoom()).thenReturn(room);
        when(membership.getCreatedAt()).thenReturn(
                OffsetDateTime.parse("2026-09-25T00:00:00Z").toLocalDateTime()
        );
        return membership;
    }
}
