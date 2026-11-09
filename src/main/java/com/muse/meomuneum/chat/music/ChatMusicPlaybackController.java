package com.muse.meomuneum.chat.music;

import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.chat.music.ChatMusicQueueService.Caller;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.global.security.TokenClaims;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}/playback/commands")
@ConditionalOnProperty(name = "chat.music.playback.enabled", havingValue = "true")
public class ChatMusicPlaybackController {
    private final ChatMusicPlaybackService playback;
    private final ChatParticipationService participation;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ChatMusicPlaybackController(ChatMusicPlaybackService playback, ChatParticipationService participation) {
        this.playback = playback;
        this.participation = participation;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> control(@PathVariable long roomId, @RequestParam("membership_id") long member,
            @RequestParam("view_session_id") String view, @RequestHeader("Idempotency-Key") String id,
            @RequestBody String body, Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long user)
                || !(auth.getDetails() instanceof TokenClaims claims) || !claims.userId().equals(user)) {
            throw new ChatMusicException(401, "AUTH_REQUIRED");
        }
        Caller caller = new Caller(user, claims.sessionId(), roomId, member, view);
        Command request = parse(body);
        var result = playback.control(participation, caller, id, request.action(), request.item(), request.version());
        return ResponseEntity.status(result.status()).header("Cache-Control", "no-store")
                .contentType(MediaType.APPLICATION_JSON).body(result.body());
    }

    private Command parse(String body) {
        try {
            if (body.length() > 4096) {
                throw new IllegalArgumentException();
            }
            var node = json.readTree(body);
            if (!node.isObject() || node.size() != 3 || !node.has("action") || !node.get("action").isString()
                    || !node.has("expected_version") || !node.get("expected_version").isIntegralNumber()
                    || !node.get("expected_version").canConvertToLong() || !node.has("queue_item_id")) {
                throw new IllegalArgumentException();
            }
            String action = node.get("action").asString();
            var item = node.get("queue_item_id");
            if (!Set.of("PLAY", "PAUSE", "RESUME", "SKIP").contains(action)
                    || (!item.isNull() && (!item.isIntegralNumber() || !item.canConvertToLong()))) {
                throw new IllegalArgumentException();
            }
            return new Command(action, item.isNull() ? null : item.asLong(), node.get("expected_version").asLong());
        } catch (RuntimeException invalid) {
            throw new ChatMusicException(400, "INVALID_PLAYBACK_COMMAND");
        }
    }

    private record Command(String action, Long item, long version) {
    }
}
