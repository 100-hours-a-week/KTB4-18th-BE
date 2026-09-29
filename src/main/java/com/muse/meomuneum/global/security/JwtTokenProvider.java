package com.muse.meomuneum.global.security;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.exception.ErrorCode;
import com.muse.meomuneum.user.domain.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_TYPE = "refresh";
    private static final String TOKEN_TYPE_CLAIM = "typ";

    private final JwtDecoder jwtDecoder;
    private final JwtDecoder logoutLocatorDecoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        SecretKey secretKey = createSecretKey(jwtProperties.secret());
        this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()));
        this.jwtDecoder = decoder;
        NimbusJwtDecoder locatorDecoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256)
                .build();
        locatorDecoder.setJwtValidator(new JwtIssuerValidator(jwtProperties.issuer()));
        this.logoutLocatorDecoder = locatorDecoder;
    }

    public String createAccessToken(User user) {
        return createToken(user, ACCESS_TOKEN_TYPE, jwtProperties.accessTokenExpirationSeconds(), null);
    }

    public String createAccessToken(User user, String sessionId) {
        return createToken(user, ACCESS_TOKEN_TYPE, jwtProperties.accessTokenExpirationSeconds(), sessionId);
    }

    public String createRefreshToken(User user) {
        return createToken(user, REFRESH_TOKEN_TYPE, jwtProperties.refreshTokenExpirationSeconds(), null);
    }

    public String createRefreshToken(User user, String sessionId) {
        return createToken(user, REFRESH_TOKEN_TYPE, jwtProperties.refreshTokenExpirationSeconds(), sessionId);
    }

    public TokenClaims parseAccessToken(String token) {
        return parseToken(token, ACCESS_TOKEN_TYPE, SecurityErrorCode.ACCESS_UNAUTHORIZED);
    }

    public RefreshTokenClaims parseRefreshToken(String token) {
        return toRefreshClaims(parseToken(token, REFRESH_TOKEN_TYPE, AuthErrorCode.REFRESH_INVALID_TOKEN, jwtDecoder));
    }

    public TokenClaims parseAccessTokenForLogout(String token) {
        return parseToken(token, ACCESS_TOKEN_TYPE, SecurityErrorCode.ACCESS_UNAUTHORIZED, logoutLocatorDecoder);
    }

    public RefreshTokenClaims parseRefreshTokenForLogout(String token) {
        return toRefreshClaims(parseToken(token, REFRESH_TOKEN_TYPE, AuthErrorCode.REFRESH_INVALID_TOKEN,
                logoutLocatorDecoder));
    }

    private RefreshTokenClaims toRefreshClaims(TokenClaims claims) {
        if (claims.expiresAt() == null || claims.sessionId() == null || claims.tokenId() == null) {
            throw new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN);
        }
        return new RefreshTokenClaims(claims.userId(), claims.expiresAt(), claims.sessionId(), claims.tokenId());
    }

    private String createToken(User user, String type, long expirationSeconds, String sessionId) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder().issuer(jwtProperties.issuer())
                .subject(user.getId().toString())
                .audience(List.of(jwtProperties.audience())).issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(expirationSeconds)).id(UUID.randomUUID().toString())
                .claim(TOKEN_TYPE_CLAIM, type)
                .claim("roles", List.of(user.getRole().name()));
        if (sessionId != null){
            claimsBuilder.claim("sid", sessionId);
        }
        JwtClaimsSet claims = claimsBuilder.build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private TokenClaims parseToken(String token, String expectedType, ErrorCode errorCode) {
        return parseToken(token, expectedType, errorCode, jwtDecoder);
    }

    private TokenClaims parseToken(String token, String expectedType, ErrorCode errorCode, JwtDecoder decoder) {
        try {
            Jwt jwt = decoder.decode(token);
            boolean hasExpectedAudience = jwt.getAudience().contains(jwtProperties.audience());
            boolean hasExpectedType = expectedType.equals(jwt.getClaimAsString(TOKEN_TYPE_CLAIM));
            if (!hasExpectedAudience || !hasExpectedType) {
                throw new AuthenticationFailedException(errorCode);
            }

            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null || roles.isEmpty()) {
                throw new AuthenticationFailedException(errorCode);
            }

            return new TokenClaims(Long.valueOf(jwt.getSubject()), roles, jwt.getExpiresAt(),
                    jwt.getClaimAsString("sid"), jwt.getId());
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
