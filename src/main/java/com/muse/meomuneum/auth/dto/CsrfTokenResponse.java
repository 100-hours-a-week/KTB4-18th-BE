package com.muse.meomuneum.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CsrfTokenResponse(@JsonProperty("csrf_token") String csrfToken) {
}
