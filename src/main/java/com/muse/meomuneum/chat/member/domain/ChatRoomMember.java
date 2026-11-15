package com.muse.meomuneum.chat.member.domain;

import java.time.LocalDateTime;
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

import com.muse.meomuneum.chat.room.domain.ChatRoom;
import com.muse.meomuneum.user.domain.User;

@Entity
@Table(name = "chat_room_members")
public class ChatRoomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom chatRoom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "auth_session_id", columnDefinition = "char(36)", length = 36, updatable = false)
    private String authSessionId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected ChatRoomMember() {
    }

    private ChatRoomMember(User user, ChatRoom chatRoom, LocalDateTime createdAt, String authSessionId) {
        this.user = Objects.requireNonNull(user);
        this.chatRoom = Objects.requireNonNull(chatRoom);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.authSessionId = Objects.requireNonNull(authSessionId);
    }

    public static ChatRoomMember join(User user, ChatRoom chatRoom, LocalDateTime joinedAt, String authSessionId) {
        return new ChatRoomMember(user, chatRoom, joinedAt, authSessionId);
    }

    public String getAuthSessionId() {
        return authSessionId;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public ChatRoom getChatRoom() {
        return chatRoom;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public boolean isActive() {
        return deletedAt == null;
    }

    public void leave(LocalDateTime leftAt) {
        if (deletedAt != null) {
            return;
        }
        deletedAt = Objects.requireNonNull(leftAt);
    }
}
