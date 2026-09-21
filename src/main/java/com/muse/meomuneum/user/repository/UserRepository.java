package com.muse.meomuneum.user.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.muse.meomuneum.user.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findAllByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByIdAndDeletedAtIsNull(Long id);
}
