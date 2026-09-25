package com.muse.meomuneum.musicrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository.Location;

class LocationTokenServiceTest {
    private final LocationTokenService tokens = new LocationTokenService("test-only-signing-secret");
    private final Location location = new Location(3L, "dot-3", 4L, "11440", "마포구",
            5L, "11", "서울특별시");

    @Test
    void signedTokenCarriesTheSameExpiryAsTheResponse() {
        Instant expiresAt = Instant.now().plusSeconds(300);
        String token = tokens.create(1L, location, expiresAt);
        var claims = tokens.parse(token, 1L);

        assertThat(claims.dotId()).isEqualTo(3L);
        assertThat(claims.sigunguId()).isEqualTo(4L);
        assertThat(claims.sidoId()).isEqualTo(5L);
        assertThat(claims.expiresAt()).isEqualTo(Instant.ofEpochSecond(expiresAt.getEpochSecond()));
    }

    @Test
    void expiryBoundaryIsRejected() {
        String token = tokens.create(1L, location, Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> tokens.parse(token, 1L))
                .isInstanceOf(MusicRecordException.class);
    }
}
