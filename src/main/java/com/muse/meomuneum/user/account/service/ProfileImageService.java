package com.muse.meomuneum.user.account.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.muse.meomuneum.user.account.exception.UserAccountException;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;

@Service
public class ProfileImageService {

    private static final Logger log = LoggerFactory.getLogger(ProfileImageService.class);
    private final UserRepository userRepository;
    private final ProfileImageStorage storage;

    public ProfileImageService(UserRepository userRepository, ProfileImageStorage storage) {
        this.userRepository = userRepository;
        this.storage = storage;
    }

    @Transactional
    public String upload(Long userId, MultipartFile image) {
        User user = userRepository.findActiveByIdForUpdate(userId).orElseThrow(this::unauthorized);
        String previous = user.getProfileImageUrl();
        String replacement = storage.store(userId, image);
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    cleanup(userId, status == STATUS_COMMITTED ? previous : replacement);
                }
            });
        } catch (RuntimeException exception) {
            cleanup(userId, replacement);
            throw exception;
        }
        user.updateProfile(null, null, false, null, false, replacement, LocalDateTime.now());
        userRepository.flush();
        return replacement;
    }

    @Transactional(readOnly = true)
    public byte[] read(Long userId, String uuid) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId).orElseThrow(this::unauthorized);
        String url = ProfileImageStorage.URL_PREFIX + uuid + ".png";
        if (!url.equals(user.getProfileImageUrl())) {
            throw new UserAccountException("USER_IMAGE_NOT_FOUND", HttpStatus.NOT_FOUND, "image not found");
        }
        return storage.read(userId, url);
    }

    private void cleanup(Long userId, String url) {
        try {
            storage.delete(userId, url);
        } catch (RuntimeException exception) {
            log.warn("event=user_profile_image_cleanup_failed");
        }
    }

    private UserAccountException unauthorized() {
        return new UserAccountException("AUTH_401", HttpStatus.UNAUTHORIZED, "invalid credentials");
    }
}
