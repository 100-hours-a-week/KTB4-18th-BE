package com.muse.meomuneum.user.signup.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SignupResponse(@JsonProperty("user_id") long userId) {}
