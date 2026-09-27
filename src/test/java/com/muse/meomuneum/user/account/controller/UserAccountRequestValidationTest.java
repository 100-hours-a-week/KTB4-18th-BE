package com.muse.meomuneum.user.account.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
        assertThat(validator.validate(new UserAccountController.UpdateUserProfileRequest(null, null, null, null)))
                .isEmpty();
        assertThat(validator.validate(new UserAccountController.UpdateUserProfileRequest("한글Name12", (short) 1900,
                null, null))).isEmpty();
        assertThat(validator.validate(new UserAccountController.UpdateUserProfileRequest("닉", null, null, null)))
                .isNotEmpty();
        assertThat(validator.validate(new UserAccountController.UpdateUserProfileRequest("invalid!", null, null, null)))
                .isNotEmpty();
        assertThat(validator.validate(new UserAccountController.UpdateUserProfileRequest(null, (short) 1899,
                null, null))).isNotEmpty();
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
