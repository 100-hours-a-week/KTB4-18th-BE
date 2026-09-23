package com.muse.meomuneum.user.signup.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email @Size(max = 40) String email,
        @NotBlank @Size(min = 8, max = 64)
        @Pattern(regexp = "(?=.*[0-9])(?=.*[!@#$%^&*_+=-])[A-Za-z0-9!@#$%^&*_+=-]+") String password,
        @NotBlank @Size(min = 2, max = 12) @Pattern(regexp = "[가-힣A-Za-z0-9]+") String nickname,
        @JsonProperty("birth_year") @Min(1900) Short birthYear,
        @Pattern(regexp = "MALE|FEMALE") String gender,
        @JsonProperty("terms_ids") @NotNull @Size(min = 1) List<@NotNull Long> termsIds) {}
