package com.muse.meomuneum.user.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.muse.meomuneum.user.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findAllByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT user FROM User user WHERE user.id = :userId AND user.deletedAt IS NULL")
    Optional<User> findActiveByIdForUpdate(@Param("userId") Long userId);
}
