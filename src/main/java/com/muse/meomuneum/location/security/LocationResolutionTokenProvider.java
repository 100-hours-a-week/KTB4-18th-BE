package com.muse.meomuneum.location.security;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.chat.region.domain.Region;
import com.muse.meomuneum.location.config.LocationTokenProperties;
import com.muse.meomuneum.location.exception.LocationErrorCode;
import com.muse.meomuneum.location.exception.LocationException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@Component
public class LocationResolutionTokenProvider {

    private static final String TOKEN_PREFIX = "loc_";
    private static final String TOKEN_TYPE = "LOCATION_RESOLUTION";
    private static final String ISSUER = "meomuneum-location";
    private static final String AUDIENCE = "meomuneum-api";
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final byte[] secret;
    private final Clock clock;

    public LocationResolutionTokenProvider(LocationTokenProperties properties, Clock clock) {
        this.secret = validateSecret(properties.secret());
        this.clock = clock;
    }

    public IssuedLocationToken issue(Long userId, Region sido, Region sigungu) {
        return issue(userId, sido, sigungu, null);
    }

    public IssuedLocationToken issue(Long userId, Region sido, Region sigungu, Long mapDotId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(TOKEN_LIFETIME);
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .audience(AUDIENCE)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim("type", TOKEN_TYPE)
                .claim("sido_region_id", sido.getId())
                .claim("sido_code", sido.getCode())
                .claim("sigungu_region_id", sigungu.getId())
                .claim("sigungu_code", sigungu.getCode());
        if (mapDotId != null) {
            builder.claim("map_dot_id", mapDotId);
        }
        JWTClaimsSet claims = builder.build();

        try {
            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            signedJwt.sign(new MACSigner(secret));
            return new IssuedLocationToken(TOKEN_PREFIX + signedJwt.serialize(), TOKEN_LIFETIME.toSeconds());
        } catch (JOSEException exception) {
            throw invalidToken();
        }
    }

    public LocationResolutionClaims validate(String token, Long expectedUserId) {
        try {
            SignedJWT signedJwt = parseToken(token);
            JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
            if (!signedJwt.verify(new MACVerifier(secret)) || !hasExpectedStandardClaims(signedJwt, claims)) {
                throw invalidToken();
            }

            Long userId = Long.valueOf(claims.getSubject());
            if (!userId.equals(expectedUserId)) {
                throw invalidToken();
            }

            return new LocationResolutionClaims(
                    userId,
                    numberClaim(claims, "sido_region_id"),
                    stringClaim(claims, "sido_code"),
                    numberClaim(claims, "sigungu_region_id"),
                    stringClaim(claims, "sigungu_code"),
                    optionalNumberClaim(claims, "map_dot_id"),
                    claims.getExpirationTime().toInstant());
        } catch (JOSEException | ParseException | IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private SignedJWT parseToken(String token) throws ParseException {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            throw invalidToken();
        }
        return SignedJWT.parse(token.substring(TOKEN_PREFIX.length()));
    }

    private boolean hasExpectedStandardClaims(SignedJWT signedJwt, JWTClaimsSet claims) throws ParseException {
        Date issueTime = claims.getIssueTime();
        Date expirationTime = claims.getExpirationTime();
        return JWSAlgorithm.HS256.equals(signedJwt.getHeader().getAlgorithm())
                && ISSUER.equals(claims.getIssuer())
                && List.of(AUDIENCE).equals(claims.getAudience())
                && TOKEN_TYPE.equals(claims.getStringClaim("type"))
                && issueTime != null
                && !issueTime.toInstant().isAfter(clock.instant())
                && expirationTime != null
                && expirationTime.toInstant().isAfter(clock.instant());
    }

    private Long numberClaim(JWTClaimsSet claims, String name) throws ParseException {
        Object value = claims.getClaim(name);
        if (!(value instanceof Number number)) {
            throw invalidToken();
        }
        return number.longValue();
    }

    private Long optionalNumberClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || number.longValue() <= 0) {
            throw invalidToken();
        }
        return number.longValue();
    }

    private String stringClaim(JWTClaimsSet claims, String name) throws ParseException {
        String value = claims.getStringClaim(name);
        if (value == null || value.isBlank()) {
            throw invalidToken();
        }
        return value;
    }

    private byte[] validateSecret(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException("LOCATION_TOKEN_SECRET must be at least 32 bytes");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private LocationException invalidToken() {
        return new LocationException(LocationErrorCode.INVALID_LOCATION_TOKEN);
    }
}
