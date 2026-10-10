package com.muse.meomuneum.chat.ban.domain;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.muse.meomuneum.user.domain.User;

@Entity
@Table(name = "chat_bans")
public class ChatBan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "banned_by_user_id", updatable = false)
    private User bannedByUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 64, nullable = false, updatable = false)
    private ChatBanReason reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected ChatBan() {
    }

    private ChatBan(User user, ChatBanReason reason, LocalDateTime startedAt) {
        this.user = Objects.requireNonNull(user);
        this.reason = Objects.requireNonNull(reason);
        this.createdAt = Objects.requireNonNull(startedAt).truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = this.createdAt.plusDays(7);
    }

    public static ChatBan automatic(User user, ChatBanReason reason, LocalDateTime startedAt) {
        return new ChatBan(user, reason, startedAt);
    }

    public boolean isActiveAt(LocalDateTime now) {
        return deletedAt == null && !createdAt.isAfter(now) && expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public User getBannedByUser() {
        return bannedByUser;
    }

    public ChatBanReason getReason() {
        return reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }
}
