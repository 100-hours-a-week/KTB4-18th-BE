package com.muse.meomuneum.user.account.controller;

import java.time.LocalDateTime;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.user.account.service.UserAccountService;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserGender;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserAccountController {

    private final CurrentUserResolver currentUserResolver;
    private final UserAccountService userAccountService;

    public UserAccountController(UserAccountService userAccountService, CurrentUserResolver currentUserResolver) {
        this.userAccountService = userAccountService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping
    public ApiResponse<UserProfileResponse> getProfile(Authentication authentication) {
        return ApiResponse.of("user retrieved",
                UserProfileResponse.from(userAccountService.getProfile(userId(authentication))));
    }

    @PatchMapping
    public ApiResponse<UpdateUserProfileResponse> updateProfile(@Valid @RequestBody UpdateUserProfileRequest request,
            Authentication authentication) {
        User user = userAccountService.updateProfile(userId(authentication), request.nickname(), request.birth_year(),
                request.gender(), request.profile_image_url());
        return ApiResponse.of("user updated", new UpdateUserProfileResponse(user.getId(), user.getUpdatedAt()));
    }

    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        userAccountService.changePassword(userId(authentication), request.current_password(), request.new_password());
        if (servletRequest.getSession(false) != null) {
            servletRequest.getSession(false).invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> withdraw(@Valid @RequestBody WithdrawRequest request, Authentication authentication,
            HttpServletRequest servletRequest) {
        userAccountService.withdraw(userId(authentication), request.password());
        if (servletRequest.getSession(false) != null) {
            servletRequest.getSession(false).invalidate();
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private Long userId(Authentication authentication) {
        Long userId = currentUserResolver.resolve(authentication);
        if (userId == null) {
            throw new IllegalStateException("Authenticated user is required");
        }
        return userId;
    }

    public record UpdateUserProfileRequest(@Size(min = 2, max = 12) String nickname, Short birth_year,
            UserGender gender, @Size(max = 1000) String profile_image_url) {
    }

    public record UserProfileResponse(Long user_id, String email, String nickname, Short birth_year,
            UserGender gender, String profile_image_url, LocalDateTime created_at) {
        private static UserProfileResponse from(User user) {
            return new UserProfileResponse(user.getId(), user.getEmail(), user.getNickname(), user.getBirthYear(),
                    user.getGender(), user.getProfileImageUrl(), user.getCreatedAt());
        }
    }

    public record UpdateUserProfileResponse(Long user_id, LocalDateTime updated_at) {
    }

    public record ChangePasswordRequest(@NotBlank String current_password, @NotBlank String new_password) {
    }

    public record WithdrawRequest(@NotBlank String password) {
    }
}
