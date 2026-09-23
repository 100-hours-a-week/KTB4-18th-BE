package com.muse.meomuneum.chat.region.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
@Table(name = "regions")
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 30, nullable = false, unique = true)
    private String code;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", length = 20, nullable = false)
    private RegionLevel level;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Region parent;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected Region() {
    }

    private Region(String code, String name, RegionLevel level, Region parent) {
        this.code = Objects.requireNonNull(code);
        this.name = Objects.requireNonNull(name);
        this.level = Objects.requireNonNull(level);
        this.parent = parent;
        this.active = true;
    }

    public static Region create(String code, String name, RegionLevel level, Region parent) {
        if (level == RegionLevel.SIGUNGU && parent == null) {
            throw new IllegalArgumentException("SIGUNGU region requires a parent SIDO region");
        }
        if (level == RegionLevel.SIDO && parent != null) {
            throw new IllegalArgumentException("SIDO region cannot have a parent region");
        }
        return new Region(code, name, level, parent);
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public RegionLevel getLevel() {
        return level;
    }

    public Region getParent() {
        return parent;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        active = false;
    }
}
