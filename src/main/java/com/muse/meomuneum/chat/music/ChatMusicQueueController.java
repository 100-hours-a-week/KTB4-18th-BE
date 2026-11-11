package com.muse.meomuneum.chat.music;

import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.chat.music.ChatMusicQueueService.Caller;
import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.global.security.TokenClaims;

@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}")
@ConditionalOnProperty(name = "chat.music.queue.enabled", havingValue = "true")
public class ChatMusicQueueController {
    private static final Pattern ADD = Pattern.compile("\\s*\\{\\s*\"music_id\"\\s*:\\s*([1-9][0-9]{0,15})\\s*}\\s*");
    private static final String MUSIC = "\"music_id\"\\s*:\\s*([1-9][0-9]{0,15})";
    private static final String CANDIDATE = "\"candidate_id\"\\s*:\\s*\"([0-9a-f-]{36})\"";
    private static final Pattern SELECTED_ADD = Pattern.compile("\\s*\\{\\s*(?:" + MUSIC + "\\s*,\\s*" + CANDIDATE
            + "|" + CANDIDATE + "\\s*,\\s*" + MUSIC + ")\\s*}\\s*");
    private final ChatMusicQueueService service;

    public ChatMusicQueueController(ChatMusicQueueService service) {
        this.service = service;
    }

    @GetMapping("/requestable-music")
    public ResponseEntity<ApiResponse<Map<String, Object>>> candidates(@PathVariable long roomId,
            @RequestParam("membership_id") long member, @RequestParam("view_session_id") String view,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor, Authentication auth) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ApiResponse.of("requestable music retrieved",
                        service.candidates(caller(auth, roomId, member, view),
                                query, size, cursor)));
    }

    @GetMapping("/queue-items")
    public ResponseEntity<ApiResponse<Map<String, Object>>> queue(@PathVariable long roomId,
            @RequestParam("membership_id") long member, @RequestParam("view_session_id") String view,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String cursor,
            Authentication auth) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.of("music queue retrieved",
                service.queue(caller(auth, roomId, member, view), size, cursor)));
    }

    @PostMapping("/music/{musicId}/video-searches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> videoSearch(@PathVariable long roomId,
            @PathVariable long musicId, @RequestParam("membership_id") long member,
            @RequestParam("view_session_id") String view, Authentication auth) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ApiResponse.of("music video candidates retrieved",
                        service.videoSearch(caller(auth, roomId, member, view), musicId)));
    }

    @PostMapping(value = "/queue-items", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> add(@PathVariable long roomId, @RequestParam("membership_id") long member,
            @RequestParam("view_session_id") String view, @RequestHeader("Idempotency-Key") String command,
            @RequestBody String body, Authentication auth) {
        var caller = caller(auth, roomId, member, view);
        // Exact contracts reject duplicate properties, unknown fields, coercions and trailing documents.
        var selected = SELECTED_ADD.matcher(body.length() > 4096 ? "" : body);
        if (selected.matches()) {
            return response(service.add(caller, command,
                    Long.parseLong(selected.group(1) == null ? selected.group(4) : selected.group(1)),
                    selected.group(2) == null ? selected.group(3) : selected.group(2)));
        }
        var match = ADD.matcher(body.length() > 4096 ? "" : body);
        if (!match.matches()) {
            throw new ChatMusicException(400, "INVALID_ADD_REQUEST");
        }
        return response(service.add(caller, command, Long.parseLong(match.group(1))));
    }

    @DeleteMapping("/queue-items/{itemId}")
    public ResponseEntity<String> cancel(@PathVariable long roomId, @PathVariable long itemId,
            @RequestParam("membership_id") long member, @RequestParam("view_session_id") String view,
            @RequestParam("expected_version") long expectedVersion, @RequestHeader("Idempotency-Key") String command,
            Authentication auth) {
        return response(service.cancel(caller(auth, roomId, member, view), command, itemId, expectedVersion));
    }

    private ResponseEntity<String> response(ChatMusicQueueService.Result result) {
        var builder = ResponseEntity.status(result.status()).header("Cache-Control", "no-store");
        return result.status() == 204
                ? builder.build()
                : builder.contentType(MediaType.APPLICATION_JSON).body(result.body());
    }

    private Caller caller(Authentication auth, long room, long member, String view) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long user)
                || !(auth.getDetails() instanceof TokenClaims claims) || !claims.userId().equals(user)) {
            throw new ChatMusicException(401, "AUTH_REQUIRED");
        }
        return new Caller(user, claims.sessionId(), room, member, view);
    }
}
