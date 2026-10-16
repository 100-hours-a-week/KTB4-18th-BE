package com.muse.meomuneum.chat.room.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.room.dto.ChatRoomJoinRequest;
import com.muse.meomuneum.chat.room.dto.ChatRoomMembershipResponse;
import com.muse.meomuneum.chat.room.dto.ChatRoomResponse;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;
import com.muse.meomuneum.chat.room.service.ChatRoomJoinResult;
import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.global.security.TokenClaims;

@RestController
@RequestMapping("/api/v1")
public class ChatRoomController {

    private final ChatRoomEntryService chatRoomEntryService;

    public ChatRoomController(ChatRoomEntryService chatRoomEntryService) {
        this.chatRoomEntryService = chatRoomEntryService;
    }

    @GetMapping("/regions/{regionId}/chat-room")
    public ApiResponse<ChatRoomResponse> findByRegion(@PathVariable Long regionId) {
        return ApiResponse.of("chat room retrieved", chatRoomEntryService.findByRegion(regionId));
    }

    @DeleteMapping("/chat-rooms/{roomId}/members/me")
    public ResponseEntity<Void> leave(@PathVariable Long roomId,
            @RequestParam("membership_id") Long membershipId, Authentication authentication) {
        chatRoomEntryService.leave((Long) authentication.getPrincipal(), authSessionId(authentication), roomId,
                membershipId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/chat-rooms/{roomId}/members")
    public ResponseEntity<ApiResponse<ChatRoomMembershipResponse>> join(@PathVariable Long roomId,
            @RequestBody ChatRoomJoinRequest request, Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        ChatRoomJoinResult result = chatRoomEntryService.join(userId, authSessionId(authentication), roomId,
                request.locationResolutionToken());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        String message = result.created() ? "chat room joined" : "already joined";
        return ResponseEntity.status(status).body(ApiResponse.of(message, result.membership()));
    }

    private String authSessionId(Authentication authentication) {
        if (authentication.getDetails() instanceof TokenClaims claims
                && claims.userId().equals(authentication.getPrincipal())
                && ChatAuthSessionRegistry.isValidSessionId(claims.sessionId())) {
            return claims.sessionId();
        }
        throw new ChatRoomException(ChatRoomErrorCode.AUTH_REQUIRED);
    }
}
