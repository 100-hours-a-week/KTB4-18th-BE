package com.muse.meomuneum.chat.message.domain;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.muse.meomuneum.chat.room.domain.ChatRoom;
import com.muse.meomuneum.user.domain.User;

@Entity
@Table(name = "chat_room_messages", uniqueConstraints = @UniqueConstraint(name = "UK_CHAT_ROOM_MESSAGES_USER_CLIENT", columnNames = {
        "user_id", "client_message_id"}))
public class ChatRoomMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, updatable = false)
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "client_message_id", length = 100, nullable = false, updatable = false)
    private String clientMessageId;

    @Column(name = "content", length = 300, nullable = false, updatable = false)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ChatRoomMessage() {
    }

    private ChatRoomMessage(User user, ChatRoom chatRoom, String clientMessageId, String content,
            LocalDateTime sentAt) {
        validateText(clientMessageId, 100);
        validateText(content, 300);
        this.user = Objects.requireNonNull(user);
        this.chatRoom = Objects.requireNonNull(chatRoom);
        this.clientMessageId = clientMessageId;
        this.content = content;
        this.createdAt = Objects.requireNonNull(sentAt).truncatedTo(ChronoUnit.MICROS);
    }

    public static ChatRoomMessage create(User user, ChatRoom chatRoom, String clientMessageId, String content,
            LocalDateTime sentAt) {
        return new ChatRoomMessage(user, chatRoom, clientMessageId, content, sentAt);
    }

    private static void validateText(String text, int maximumLength) {
        if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > maximumLength) {
            throw new IllegalArgumentException("invalid chat message text");
        }
    }

    public boolean isExpiredAt(LocalDateTime now) {
        return !createdAt.plusHours(24).isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public ChatRoom getChatRoom() {
        return chatRoom;
    }

    public User getUser() {
        return user;
    }

    public String getClientMessageId() {
        return clientMessageId;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
