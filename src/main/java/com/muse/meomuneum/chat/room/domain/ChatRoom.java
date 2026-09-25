package com.muse.meomuneum.chat.room.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.muse.meomuneum.chat.region.domain.Region;
import com.muse.meomuneum.chat.region.domain.RegionLevel;

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

@Entity
@Table(name = "chat_rooms")
public class ChatRoom {

    public static final int DEFAULT_CAPACITY = 25;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false, unique = true)
    private Region region;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ChatRoomStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected ChatRoom() {
    }

    private ChatRoom(Region region, int capacity) {
        this.region = Objects.requireNonNull(region);
        if (region.getLevel() != RegionLevel.SIGUNGU) {
            throw new IllegalArgumentException("chat room requires a SIGUNGU region");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("chat room capacity must be positive");
        }
        this.capacity = capacity;
        this.status = ChatRoomStatus.ACTIVE;
    }

    public static ChatRoom create(Region region) {
        return new ChatRoom(region, DEFAULT_CAPACITY);
    }

    public static ChatRoom create(Region region, int capacity) {
        return new ChatRoom(region, capacity);
    }

    public Long getId() {
        return id;
    }

    public Region getRegion() {
        return region;
    }

    public int getCapacity() {
        return capacity;
    }

    public ChatRoomStatus getStatus() {
        return status;
    }

    public void deactivate() {
        status = ChatRoomStatus.INACTIVE;
    }
}
