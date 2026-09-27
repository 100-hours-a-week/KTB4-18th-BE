package com.muse.meomuneum.musicrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

class MusicSearchCursorCodecTest {
    private final MusicSearchCursorCodec codec = new MusicSearchCursorCodec("test-only-signing-secret");

    @Test
    void cursorPreservesSearchSnapshotAndPosition() {
        String token = codec.encode("밤편지", "ITUNES", 7L, 30L, 12, codec.hash("tracks"));

        var cursor = codec.decode(token, "밤편지");

        assertThat(cursor.phase()).isEqualTo("ITUNES");
        assertThat(cursor.lastDbId()).isEqualTo(7L);
        assertThat(cursor.highWatermark()).isEqualTo(30L);
        assertThat(cursor.externalIndex()).isEqualTo(12);
    }

    @Test
    void rejectsTamperingOrDifferentQueryUsingDocumentedBadRequestMessage() {
        String token = codec.encode("밤편지", "DB", 7L, 30L, 0, "");

        assertThatThrownBy(() -> codec.decode(token, "다른 곡"))
                .isInstanceOf(MusicRecordException.class)
                .hasMessage("search query required");
        assertThatThrownBy(() -> codec.decode(token + "x", "밤편지"))
                .isInstanceOf(MusicRecordException.class)
                .hasMessage("search query required");
    }

    @Test
    void cursorExpiresAfterThirtyMinutesWithoutPageExtension() {
        Instant issuedAt = Instant.parse("2026-09-25T00:00:00Z");
        String secret = "test-only-signing-secret";
        var issuer = new MusicSearchCursorCodec(secret, Clock.fixed(issuedAt, ZoneOffset.UTC));
        String first = issuer.encode("밤편지", "DB", 7L, 30L, 0, "");
        var beforeExpiry = new MusicSearchCursorCodec(secret,
                Clock.fixed(issuedAt.plusSeconds(1799), ZoneOffset.UTC));
        var firstPage = beforeExpiry.decode(first, "밤편지");
        String next = beforeExpiry.encode("밤편지", "DB", 6L, 30L, 0, "", firstPage.expiresAt());

        assertThat(beforeExpiry.decode(next, "밤편지").expiresAt())
                .isEqualTo(issuedAt.plusSeconds(1800));
        var expired = new MusicSearchCursorCodec(secret,
                Clock.fixed(issuedAt.plusSeconds(1800), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.decode(first, "밤편지"))
                .isInstanceOf(MusicRecordException.class)
                .hasMessage("search query required");
        assertThatThrownBy(() -> expired.decode(next, "밤편지"))
                .isInstanceOf(MusicRecordException.class)
                .hasMessage("search query required");
    }
}
