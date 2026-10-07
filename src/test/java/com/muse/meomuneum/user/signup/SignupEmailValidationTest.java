package com.muse.meomuneum.user.signup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.muse.meomuneum.user.signup.dto.SignupRequest;

class SignupEmailValidationTest {
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    void preservesNullEmailForNotBlankValidation() {
        SignupRequest request = new SignupRequest(null, "Password1!", "Nickname", (short) 1994,
                "MALE", List.of(1L));

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString()).contains("email");
    }

    @Test
    void normalizesEmailBeforeSignupBeanValidation() {
        SignupRequest request = new SignupRequest(" QA@Example.com ", "Password1!", "Nickname", (short) 1994,
                "MALE", List.of(1L));

        assertThat(request.email()).isEqualTo("qa@example.com");
        assertThat(validator.validate(request)).isEmpty();
    }
}
