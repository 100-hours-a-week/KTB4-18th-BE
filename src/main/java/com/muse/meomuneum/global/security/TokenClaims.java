package com.muse.meomuneum.global.security;

import java.util.List;

public record TokenClaims(Long userId, List<String> roles) {
}
