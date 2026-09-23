package com.muse.meomuneum.chat.member.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;

import com.muse.meomuneum.chat.room.domain.ChatRoom;
import com.muse.meomuneum.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected ChatRoomMember() {
    }

    private ChatRoomMember(User user, ChatRoom chatRoom) {
        this.user = Objects.requireNonNull(user);
        this.chatRoom = Objects.requireNonNull(chatRoom);
    }

    public static ChatRoomMember join(User user, ChatRoom chatRoom) {
        return new ChatRoomMember(user, chatRoom);
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
