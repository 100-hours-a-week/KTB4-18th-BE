package com.muse.meomuneum.location.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.muse.meomuneum.chat.region.domain.Region;
import com.muse.meomuneum.chat.region.domain.RegionLevel;
import com.muse.meomuneum.location.config.LocationTokenProperties;
import com.muse.meomuneum.location.exception.LocationErrorCode;
import com.muse.meomuneum.location.exception.LocationException;

class LocationResolutionTokenProviderTest {

    private static final String SECRET = "location-token-test-secret-with-at-least-32-bytes";
    private static final Instant ISSUED_AT = Instant.parse("2026-09-23T08:00:00Z");

    private Region sido;
    private Region sigungu;

    @BeforeEach
    void setUp() {
        sido = Region.create("41", "경기도", RegionLevel.SIDO, null);
        ReflectionTestUtils.setField(sido, "id", 9L);
        sigungu = Region.create("41135", "성남시 분당구", RegionLevel.SIGUNGU, sido);
        ReflectionTestUtils.setField(sigungu, "id", 25L);
    }

    @Test
    void issuesFiveMinuteTokenWithUserAndRegionClaims() {
        LocationResolutionTokenProvider provider = providerAt(ISSUED_AT, SECRET);

        IssuedLocationToken issuedToken = provider.issue(7L, sido, sigungu);
        LocationResolutionClaims claims = provider.validate(issuedToken.value(), 7L);

        assertThat(issuedToken.value()).startsWith("loc_");
        assertThat(issuedToken.expiresIn()).isEqualTo(300L);
        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.sidoRegionId()).isEqualTo(9L);
        assertThat(claims.sidoCode()).isEqualTo("41");
        assertThat(claims.sigunguRegionId()).isEqualTo(25L);
        assertThat(claims.sigunguCode()).isEqualTo("41135");
        assertThat(claims.expiresAt()).isEqualTo(ISSUED_AT.plusSeconds(300));
    }

    @Test
    void rejectsExpiredToken() {
        String token = providerAt(ISSUED_AT, SECRET).issue(7L, sido, sigungu).value();

        assertInvalidToken(() -> providerAt(ISSUED_AT.plusSeconds(301), SECRET).validate(token, 7L));
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        String token = providerAt(ISSUED_AT, SECRET).issue(7L, sido, sigungu).value();

        assertInvalidToken(() -> providerAt(
                ISSUED_AT,
                "different-location-token-secret-with-at-least-32-bytes"
        ).validate(token, 7L));
    }

    @Test
    void rejectsTokenOwnedByDifferentUser() {
        LocationResolutionTokenProvider provider = providerAt(ISSUED_AT, SECRET);
        String token = provider.issue(7L, sido, sigungu).value();

        assertInvalidToken(() -> provider.validate(token, 8L));
    }

    private LocationResolutionTokenProvider providerAt(Instant instant, String secret) {
        return new LocationResolutionTokenProvider(
                new LocationTokenProperties(secret),
                Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private void assertInvalidToken(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(LocationException.class)
                .extracting(exception -> ((LocationException) exception).getErrorCode())
                .isEqualTo(LocationErrorCode.INVALID_LOCATION_TOKEN);
    }
}
