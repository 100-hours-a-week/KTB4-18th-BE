package com.muse.meomuneum.chat.ban.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.chat.ban.domain.ChatBan;
import com.muse.meomuneum.chat.ban.domain.ChatBanReason;
import com.muse.meomuneum.chat.ban.repository.ChatBanRepository;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;

@Service
public class ChatBanStorageService {

    private final ChatBanRepository repository;
    private final UserRepository userRepository;
    private final Clock clock;

    public ChatBanStorageService(ChatBanRepository repository, UserRepository userRepository, Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public ChatBan storeAutomaticBan(Long userId, ChatBanReason reason) {
        User user = userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("chat user not found"));
        LocalDateTime now = now();
        return repository.findActiveByUserId(userId, now, PageRequest.of(0, 1)).stream().findFirst()
                .orElseGet(() -> repository.saveAndFlush(ChatBan.automatic(user, reason, now)));
    }

    @Transactional(readOnly = true)
    public boolean hasActiveBan(Long userId) {
        LocalDateTime now = now();
        return repository.existsByUser_IdAndDeletedAtIsNullAndCreatedAtLessThanEqualAndExpiresAtGreaterThan(userId,
                now, now);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
