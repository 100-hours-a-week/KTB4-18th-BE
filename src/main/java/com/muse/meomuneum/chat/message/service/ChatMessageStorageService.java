package com.muse.meomuneum.chat.message.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.chat.message.domain.ChatRoomMessage;
import com.muse.meomuneum.chat.message.repository.ChatRoomMessageRepository;
import com.muse.meomuneum.chat.room.domain.ChatRoom;
import com.muse.meomuneum.chat.room.repository.ChatRoomRepository;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;

@Service
public class ChatMessageStorageService {

    private final ChatRoomMessageRepository repository;
    private final UserRepository userRepository;
    private final ChatRoomRepository roomRepository;
    private final Clock clock;

    public ChatMessageStorageService(ChatRoomMessageRepository repository, UserRepository userRepository,
            ChatRoomRepository roomRepository, Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.clock = clock;
    }

    // Caller must authorize membership and run moderation before storing a normal message.
    @Transactional
    public ChatMessageSaveResult store(Long userId, Long roomId, String clientMessageId, String content) {
        User user = userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("chat user not found"));
        ChatRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("chat room not found"));
        LocalDateTime now = now();
        ChatRoomMessage message = ChatRoomMessage.create(user, room, clientMessageId, content, now);
        Optional<ChatRoomMessage> existing = repository.findByUser_IdAndClientMessageId(userId, clientMessageId);
        if (existing.isPresent()) {
            ChatRoomMessage previous = existing.get();
            if (!previous.getChatRoom().getId().equals(roomId) || !previous.getContent().equals(content)) {
                throw new IllegalArgumentException("client message id already used for another message");
            }
            if (previous.isExpiredAt(now)) {
                throw new IllegalStateException("chat message retry has expired");
            }
            return new ChatMessageSaveResult(previous, false);
        }
        return new ChatMessageSaveResult(repository.saveAndFlush(message), true);
    }

    // Retention filter only; participation-window authorization belongs to the future query service.
    @Transactional(readOnly = true)
    public Optional<ChatRoomMessage> findUnexpiredById(Long messageId) {
        LocalDateTime now = now();
        return repository.findUnexpiredById(messageId, now.minusHours(24), now);
    }

    @Transactional
    public int deleteExpiredMessages() {
        return repository.deleteExpired(now().minusHours(24));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
