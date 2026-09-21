package com.muse.meomuneum.feature.auth.login.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 40) String email,
        @NotBlank @Size(max = 72) String password) {
}
