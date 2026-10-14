package com.muse.meomuneum.chat.message.query;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;
import com.muse.meomuneum.global.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}/messages")
public class ChatMessageQueryController {
    private final ChatMessageQueryService queries;

    public ChatMessageQueryController(ChatMessageQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public ApiResponse<ChatMessagePage> query(Authentication authentication, @PathVariable Long roomId,
            @RequestParam("membership_id") Long membershipId, @RequestHeader("X-Chat-View-Session") String viewId,
            @RequestParam(defaultValue = "history") String mode, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.of("chat messages retrieved", queries.query((Long) authentication.getPrincipal(),
                roomId, membershipId, viewId, mode, cursor, size));
    }

    @GetMapping("/by-client-id/{clientId}")
    public ApiResponse<ChatMessageResponse> byClientId(Authentication authentication, @PathVariable Long roomId,
            @PathVariable String clientId, @RequestParam("membership_id") Long membershipId,
            @RequestHeader("X-Chat-View-Session") String viewId) {
        return ApiResponse.of("chat message retrieved", queries.byClientId((Long) authentication.getPrincipal(),
                roomId, membershipId, viewId, clientId));
    }
}
