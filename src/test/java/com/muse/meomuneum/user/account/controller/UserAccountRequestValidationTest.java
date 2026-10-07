package com.muse.meomuneum.user.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.user.account.service.UserAccountService;
import com.muse.meomuneum.user.account.service.UserTermsAgreementService;
import com.muse.meomuneum.user.domain.User;

class UserAccountRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void usesSignupPasswordLengthAndCharacterPolicyForPasswordChange() {
        assertThat(validator.validate(new UserAccountController.ChangePasswordRequest("current", "Testpass1!")))
                .isEmpty();
        assertThat(validator.validate(new UserAccountController.ChangePasswordRequest("current", "short1!")))
                .isNotEmpty();
        assertThat(validator.validate(new UserAccountController.ChangePasswordRequest("current", "Password!")))
                .isNotEmpty();
        assertThat(validator.validate(new UserAccountController.ChangePasswordRequest("current", "Password1")))
                .isNotEmpty();
        assertThat(validator.validate(new UserAccountController.ChangePasswordRequest("current", "Pass word1!")))
                .isNotEmpty();
    }

    @Test
    void usesSignupNicknameAndBirthYearLowerBoundWhileKeepingPartialFieldsOptional() {
        assertThat(validator.validate(new UserAccountController.UpdateUserProfileRequest())).isEmpty();

        UserAccountController.UpdateUserProfileRequest validRequest = profileRequest("한글Name12", (short) 1900);
        assertThat(validator.validate(validRequest)).isEmpty();

        UserAccountController.UpdateUserProfileRequest shortNickname = profileRequest("닉", null);
        assertThat(validator.validate(shortNickname)).isNotEmpty();

        UserAccountController.UpdateUserProfileRequest invalidNickname = profileRequest("invalid!", null);
        assertThat(validator.validate(invalidNickname)).isNotEmpty();

        UserAccountController.UpdateUserProfileRequest invalidBirthYear = profileRequest(null, (short) 1899);
        assertThat(validator.validate(invalidBirthYear)).isNotEmpty();
    }

    private static UserAccountController.UpdateUserProfileRequest profileRequest(String nickname, Short birthYear) {
        UserAccountController.UpdateUserProfileRequest request = new UserAccountController.UpdateUserProfileRequest();
        request.setNickname(nickname);
        request.setBirthYear(birthYear);
        return request;
    }

    @Test
    void distinguishesExplicitNullGenderFromOmittedGenderInPatchBody() throws Exception {
        UserAccountService userAccountService = mock(UserAccountService.class);
        CurrentUserResolver currentUserResolver = mock(CurrentUserResolver.class);
        User user = mock(User.class);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(7L, null, List.of());
        when(currentUserResolver.resolve(authentication)).thenReturn(7L);
        when(userAccountService.updateProfile(7L, null, (short) 1990, true, null, true, "image.png"))
                .thenReturn(user);
        when(userAccountService.updateProfile(7L, null, (short) 1990, true, null, false, "image.png"))
                .thenReturn(user);
        when(user.getId()).thenReturn(7L);
        when(user.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, 10, 4, 0, 0));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserAccountController(userAccountService,
                currentUserResolver, mock(UserTermsAgreementService.class))).build();

        mvc.perform(patch("/api/v1/users/me")
                .principal(authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gender\":null,\"birth_year\":1990,\"profile_image_url\":\"image.png\"}"))
                .andExpect(status().isOk());
        verify(userAccountService).updateProfile(7L, null, (short) 1990, true, null, true, "image.png");

        mvc.perform(patch("/api/v1/users/me")
                .principal(authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"birth_year\":1990,\"profile_image_url\":\"image.png\"}"))
                .andExpect(status().isOk());
        verify(userAccountService).updateProfile(7L, null, (short) 1990, true, null, false, "image.png");
    }

    @Test
    void requiresNonEmptyTermsIdsWithNoNullElements() {
        assertThat(validator.validate(new UserAccountController.AgreeToTermsRequest(null))).isNotEmpty();
        assertThat(validator.validate(new UserAccountController.AgreeToTermsRequest(List.of(1L)))).isEmpty();
        assertThat(validator.validate(new UserAccountController.AgreeToTermsRequest(List.of()))).isNotEmpty();
        assertThat(validator.validate(new UserAccountController.AgreeToTermsRequest(java.util.Arrays.asList(1L, null))))
                .isNotEmpty();
    }
}
