package com.muse.meomuneum.user.account.controller;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.user.account.service.UserAccountService;
import com.muse.meomuneum.user.account.service.UserTermsAgreementService;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserGender;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserAccountController {

    private final CurrentUserResolver currentUserResolver;
    private final UserAccountService userAccountService;
    private final UserTermsAgreementService userTermsAgreementService;

    public UserAccountController(UserAccountService userAccountService, CurrentUserResolver currentUserResolver,
            UserTermsAgreementService userTermsAgreementService) {
        this.userAccountService = userAccountService;
        this.currentUserResolver = currentUserResolver;
        this.userTermsAgreementService = userTermsAgreementService;
    }

    @GetMapping
    public ApiResponse<UserProfileResponse> getProfile(Authentication authentication) {
        return ApiResponse.of("user retrieved",
                UserProfileResponse.from(userAccountService.getProfile(userId(authentication))));
    }

    @PatchMapping
    public ApiResponse<UpdateUserProfileResponse> updateProfile(
            @Valid @RequestBody UpdateUserProfileRequest request, Authentication authentication) {
        User user = userAccountService.updateProfile(
                userId(authentication), request.nickname(), request.birth_year(), request.isBirthYearProvided(),
                request.gender(), request.isGenderProvided(), request.profile_image_url());
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

    @GetMapping("/terms-agreements")
    public ApiResponse<TermsAgreementsResponse> getTermsAgreements(Authentication authentication) {
        return ApiResponse.of("agreements retrieved",
                new TermsAgreementsResponse(userTermsAgreementService.getAgreements(userId(authentication))));
    }

    @PostMapping("/terms-agreements")
    public ResponseEntity<ApiResponse<TermsAgreementCreationResponse>> agreeToTerms(
            @Valid @RequestBody AgreeToTermsRequest request, Authentication authentication) {
        List<Long> agreementIds = userTermsAgreementService.agree(userId(authentication), request.terms_ids());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("terms agreed", new TermsAgreementCreationResponse(agreementIds)));
    }

    @DeleteMapping("/terms-agreements/{termsId}")
    public ResponseEntity<Void> withdrawTermsAgreement(@PathVariable long termsId, Authentication authentication) {
        userTermsAgreementService.withdraw(userId(authentication), termsId);
        return ResponseEntity.noContent().build();
    }

    private Long userId(Authentication authentication) {
        Long userId = currentUserResolver.resolve(authentication);
        if (userId == null) {
            throw new IllegalStateException("Authenticated user is required");
        }
        return userId;
    }

    public static class UpdateUserProfileRequest {

        @Size(min = 2, max = 12)
        @Pattern(regexp = "[가-힣A-Za-z0-9]+")
        private String nickname;

        @Min(1900)
        private Short birthYear;
        private boolean birthYearProvided;

        private UserGender gender;
        private boolean genderProvided;

        @Size(max = 1000)
        private String profileImageUrl;

        public UpdateUserProfileRequest() {
        }

        public String nickname() {
            return nickname;
        }

        public Short birth_year() {
            return birthYear;
        }

        public boolean isBirthYearProvided() {
            return birthYearProvided;
        }

        public UserGender gender() {
            return gender;
        }

        public boolean isGenderProvided() {
            return genderProvided;
        }

        public String profile_image_url() {
            return profileImageUrl;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }

        @JsonSetter("birth_year")
        public void setBirthYear(Short birthYear) {
            this.birthYear = birthYear;
            this.birthYearProvided = true;
        }

        @JsonSetter("gender")
        public void setGender(UserGender gender) {
            this.gender = gender;
            this.genderProvided = true;
        }

        @JsonSetter("profile_image_url")
        public void setProfileImageUrl(String profileImageUrl) {
            this.profileImageUrl = profileImageUrl;
        }
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

    public record ChangePasswordRequest(@NotBlank String current_password,
            @NotBlank @Size(min = 8, max = 64) @Pattern(regexp = "(?=.*[0-9])(?=.*[!@#$%^&*_+=-])[A-Za-z0-9!@#$%^&*_+=-]+") String new_password) {
    }

    public record WithdrawRequest(@NotBlank String password) {
    }

    public record AgreeToTermsRequest(@NotEmpty List<@NotNull Long> terms_ids) {
    }

    public record TermsAgreementsResponse(List<UserTermsAgreementService.AgreementItem> items) {
    }

    public record TermsAgreementCreationResponse(List<Long> agreement_ids) {
    }
}
