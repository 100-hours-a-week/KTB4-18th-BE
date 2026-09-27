package com.muse.meomuneum.global.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.exception.ErrorCode;
import com.muse.meomuneum.user.domain.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

@Component
public class JwtTokenProvider {

    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_TYPE = "refresh";
    private static final String TOKEN_TYPE_CLAIM = "typ";

    private final JwtDecoder jwtDecoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        SecretKey secretKey = createSecretKey(jwtProperties.secret());
        this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()));
        this.jwtDecoder = decoder;
    }

    public String createAccessToken(User user) {
        return createToken(user, ACCESS_TOKEN_TYPE, jwtProperties.accessTokenExpirationSeconds());
    }

    public String createRefreshToken(User user) {
        return createToken(user, REFRESH_TOKEN_TYPE, jwtProperties.refreshTokenExpirationSeconds());
    }

    public TokenClaims parseAccessToken(String token) {
        return parseToken(token, ACCESS_TOKEN_TYPE, SecurityErrorCode.ACCESS_UNAUTHORIZED);
    }

    public RefreshTokenClaims parseRefreshToken(String token) {
        TokenClaims claims = parseToken(token, REFRESH_TOKEN_TYPE, AuthErrorCode.REFRESH_INVALID_TOKEN);
        if (claims.expiresAt() == null) {
            throw new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN);
        }

        return new RefreshTokenClaims(claims.userId(), claims.expiresAt());
    }

    private String createToken(User user, String type, long expirationSeconds) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(jwtProperties.issuer()).subject(user.getId().toString())
                .audience(List.of(jwtProperties.audience())).issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(expirationSeconds)).id(UUID.randomUUID().toString())
                .claim(TOKEN_TYPE_CLAIM, type)
                .claim("roles", List.of(user.getRole().name())).build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private TokenClaims parseToken(String token, String expectedType, ErrorCode errorCode) {
        try {
            Jwt jwt = jwtDecoder.decode(token);
            boolean hasExpectedAudience = jwt.getAudience().contains(jwtProperties.audience());
            boolean hasExpectedType = expectedType.equals(jwt.getClaimAsString(TOKEN_TYPE_CLAIM));
            if (!hasExpectedAudience || !hasExpectedType) {
                throw new AuthenticationFailedException(errorCode);
            }

            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null || roles.isEmpty()) {
                throw new AuthenticationFailedException(errorCode);
            }

            return new TokenClaims(Long.valueOf(jwt.getSubject()), roles, jwt.getExpiresAt());
        } catch (BadJwtException | IllegalArgumentException exception) {
            throw new AuthenticationFailedException(errorCode);
        }
    }

    private SecretKey createSecretKey(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("AUTH_JWT_SECRET must be at least 32 bytes");
        }

        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
