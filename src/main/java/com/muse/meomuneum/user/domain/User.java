package com.muse.meomuneum.user.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    private Long id;

    @Column(name = "email", length = 40, nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    @Column(name = "nickname", length = 12, nullable = false)
    private String nickname;

    @Column(name = "birth_year")
    private Short birthYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    private UserGender gender;

    @Column(name = "profile_image_url", length = 1000)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private UserRole role;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected User() {
    }

    public Long getId() {
        return id;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
