package com.muse.meomuneum.feature.auth.login.token;

import com.muse.meomuneum.feature.auth.login.config.AuthTokenProperties;
import com.muse.meomuneum.feature.auth.login.user.UserAccount;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

@Component
public class JwtTokenIssuer implements TokenIssuer {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final AuthTokenProperties properties;
    private final Clock clock;

    public JwtTokenIssuer(AuthTokenProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IssuedTokens issue(UserAccount user) {
        Instant issuedAt = clock.instant();
        String accessToken = createToken(
                user.id(), "access", issuedAt, properties.accessExpirationSeconds());
        String refreshToken = createToken(
                user.id(), "refresh", issuedAt, properties.refreshExpirationSeconds());

        return new IssuedTokens(
                accessToken,
                properties.accessExpirationSeconds(),
                refreshToken,
                properties.refreshExpirationSeconds());
    }

    private String createToken(Long userId, String tokenType, Instant issuedAt, long expiresIn) {
        long issuedAtSeconds = issuedAt.getEpochSecond();
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode("{\"sub\":\"" + userId + "\",\"typ\":\"" + tokenType
                + "\",\"iat\":" + issuedAtSeconds + ",\"exp\":" + (issuedAtSeconds + expiresIn) + "}");
        String signingInput = header + "." + payload;
        return signingInput + "." + sign(signingInput);
    }

    private String encode(String value) {
        return URL_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            return URL_ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign authentication token", exception);
        }
    }
}
