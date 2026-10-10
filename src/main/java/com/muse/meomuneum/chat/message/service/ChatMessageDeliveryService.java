package com.muse.meomuneum.chat.message.service;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.message.dto.ChatMessageEvent;
import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;
import com.muse.meomuneum.chat.message.policy.ChatContentPolicy;
import com.muse.meomuneum.chat.message.policy.ChatMessageMasking;
import com.muse.meomuneum.chat.message.policy.ChatMessageRejection;
import com.muse.meomuneum.chat.message.policy.ChatTransmissionPolicy;
import com.muse.meomuneum.chat.message.repository.ChatRoomMessageRepository;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;

/** Handles SEND without allowing rejected payloads into framework error logging. */
@Service
public class ChatMessageDeliveryService {
    private final ChatPresenceRegistry presence;
    private final ChatRoomEntryService entry;
    private final ChatSocketSessions sockets;
    private final ChatMessageMasking masking;
    private final ChatContentPolicy contentPolicy;
    private final ChatTransmissionPolicy transmission;
    private final ChatMessageStorageService storage;
    private final ChatRoomMessageRepository messages;
    private final ObjectMapper mapper;
    private final ObjectProvider<SimpMessagingTemplate> broker;
    private final TransactionTemplate transactions;

    public ChatMessageDeliveryService(ChatPresenceRegistry presence,
            ChatRoomEntryService entry,
            ChatSocketSessions sockets, ChatContentPolicy contentPolicy, ChatMessageMasking masking,
            ChatTransmissionPolicy transmission,
            ChatMessageStorageService storage, ChatRoomMessageRepository messages, ObjectMapper mapper,
            ObjectProvider<SimpMessagingTemplate> broker, PlatformTransactionManager transactionManager) {
        this.presence = presence;
        this.entry = entry;
        this.sockets = sockets;
        this.contentPolicy = contentPolicy;
        this.masking = masking;
        this.transmission = transmission;
        this.storage = storage;
        this.messages = messages;
        this.mapper = mapper;
        this.broker = broker;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void send(Long userId, Long roomId, Long membershipId, String sessionId, String destination, byte[] body) {
        presence.exclusive(() -> {
            String clientId = "";
            try {
                entry.authorizeConnection(userId, roomId, membershipId, sessionId);
                if (!("/app/chat-rooms/" + roomId + "/messages").equals(destination)) {
                    throw new ChatMessageRejection("DESTINATION_DENIED");
                }
                if (!presence.hasRoomSubscription(sessionId, roomId)) {
                    throw new ChatMessageRejection("NOT_READY");
                }
                JsonNode request = parse(body);
                clientId = clientId(request.path("client_message_id"));
                if (!request.path("content").isTextual()) {
                    throw new ChatMessageRejection("INVALID_CONTENT");
                }
                String text = contentPolicy.validateText(request.path("content").textValue());
                transmission.purgeReceipts();
                ChatTransmissionPolicy.Receipt previous = transmission.previous(userId, clientId);
                if (previous != null) {
                    if (!previous.membershipId().equals(membershipId)
                            || !previous.contentHash().equals(ChatTransmissionPolicy.digest(text))) {
                        throw new ChatMessageRejection("CLIENT_ID_CONFLICT");
                    }
                    ChatMessageResponse saved = transactions.execute(status -> storage
                            .findUnexpiredById(previous.messageId()).map(ChatMessageResponse::from)
                            .orElseThrow(() -> new ChatMessageRejection("RETRY_UNAVAILABLE")));
                    acknowledge(userId, membershipId, sessionId, saved);
                    return null;
                }
                transmission.check(userId, membershipId, text);
                contentPolicy.validatePersonalContent(text);
                String masked = masking.mask(text);
                String id = clientId;
                ChatMessageResponse saved = transactions.execute(status -> {
                    // A UUID from a previous process/membership must never expose old room content.
                    if (messages.findByUser_IdAndClientMessageId(userId, id).isPresent()) {
                        throw new ChatMessageRejection("CLIENT_ID_CONFLICT");
                    }
                    return ChatMessageResponse.from(storage.store(userId, roomId, id, masked).message());
                });
                transmission.accepted(userId, membershipId, clientId, text, saved.messageId());
                SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
                headers.setHeader("chatRecipients", presence.recipients(roomId));
                headers.setHeader("chatRoomId", roomId);
                headers.setLeaveMutable(true);
                broker.getObject().convertAndSend("/topic/chat-rooms/" + roomId,
                        new ChatMessageEvent("CHAT_MESSAGE", saved), headers.getMessageHeaders());
                acknowledge(userId, membershipId, sessionId, saved);
            } catch (ChatMessageRejection rejection) {
                reject(userId, roomId, membershipId, sessionId, clientId, rejection);
            } catch (ChatRoomException exception) {
                if (exception.getErrorCode() == ChatRoomErrorCode.CHAT_BANNED) {
                    entry.leaveAll(userId, 4101, "CHAT_BANNED|" + exception.getBannedUntil());
                } else {
                    sockets.close(Set.of(sessionId), 4100, "CHAT_LEFT");
                }
            } catch (RuntimeException failure) {
                // Do not log rejected content or serialize exception details to clients.
                reject(userId, roomId, membershipId, sessionId, clientId,
                        new ChatMessageRejection("SEND_FAILED"));
            }
            return null;
        });
    }

    private JsonNode parse(byte[] body) {
        try {
            if (body.length > 8192) {
                throw new ChatMessageRejection("INVALID_REQUEST");
            }
            JsonNode request = mapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new ChatMessageRejection("INVALID_REQUEST");
            }
            return request;
        } catch (IOException invalid) {
            throw new ChatMessageRejection("INVALID_REQUEST");
        }
    }

    private String clientId(JsonNode value) {
        try {
            if (value.isTextual()) {
                String id = UUID.fromString(value.textValue()).toString();
                if (id.equalsIgnoreCase(value.textValue())) {
                    return id;
                }
            }
        } catch (IllegalArgumentException invalid) {
            // Invalid input is never echoed back.
        }
        throw new ChatMessageRejection("INVALID_CLIENT_ID");
    }

    private void acknowledge(Long userId, Long membershipId, String sessionId, ChatMessageResponse saved) {
        privateEvent(userId, sessionId, new ChatMessageEvent("CHAT_ACK",
                Map.of("membership_id", membershipId, "message", saved)));
    }

    private void reject(Long userId, Long roomId, Long membershipId, String sessionId, String clientId,
            ChatMessageRejection rejection) {
        privateEvent(userId, sessionId, new ChatMessageEvent("CHAT_REJECTED",
                Map.of("room_id", roomId, "membership_id", membershipId, "client_message_id", clientId,
                        "reason", rejection.reason(), "retry_after_ms", rejection.retryAfterMs())));
    }

    private void privateEvent(Long userId, String sessionId, ChatMessageEvent event) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setHeader(SimpMessageHeaderAccessor.IGNORE_ERROR, true);
        headers.setLeaveMutable(true);
        try {
            broker.getObject().convertAndSendToUser(userId.toString(), "/queue/chat-events", event,
                    headers.getMessageHeaders());
        } catch (RuntimeException unavailable) {
            // Client retains the UUID when an ACK cannot be delivered; never log its content.
        }
    }
}
