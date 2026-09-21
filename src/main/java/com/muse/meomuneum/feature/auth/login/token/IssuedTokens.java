package com.muse.meomuneum.feature.auth.login.token;

public record IssuedTokens(
        String accessToken,
        long accessTokenExpiresIn,
        String refreshToken,
        long refreshTokenExpiresIn) {
}
