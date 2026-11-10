package com.muse.meomuneum.musicrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

class MusicSearchCursorCodecTest {
    private final Instant now = Instant.parse("2026-10-22T00:00:00Z");
    private final MusicSearchCursorCodec codec = new MusicSearchCursorCodec("secret", Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void signedCursorBindsQueryCountrySizeSourceAndPosition() {
        String token = codec.encode("밤편지", "KR", 10, codec.database(7, 30));
        assertThat(codec.decode(token, "밤편지", "KR", 10).lastDbId()).isEqualTo(7);
        assertThat(codec.decode(token, "밤편지", "KR", 10).source()).isEqualTo("DB");
        assertThatThrownBy(() -> codec.decode(token, "다른곡", "KR", 10)).isInstanceOf(MusicRecordException.class);
        assertThatThrownBy(() -> codec.decode(token, "밤편지", "US", 10)).isInstanceOf(MusicRecordException.class);
        assertThatThrownBy(() -> codec.decode(token, "밤편지", "KR", 20)).isInstanceOf(MusicRecordException.class);
        assertThatThrownBy(() -> codec.decode(token + "x", "밤편지", "KR", 10))
                .isInstanceOf(MusicRecordException.class);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).startsWith("3|");
        assertThatThrownBy(() -> codec.decode(token.replace(token.split("\\.")[0],
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.replaceFirst("3", "2")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                "밤편지", "KR", 10))
                .isInstanceOf(MusicRecordException.class);
    }

    @Test
    void dateOrderedCursorSignsTheReleaseBoundaryAndDistinguishesMissingDatesFromLegacy() {
        String dated = codec.encode("q", "KR", 2, codec.database(7, 30, -17L));
        var datePosition = codec.decode(dated, "q", "KR", 2);
        assertThat(datePosition.lastReleaseEpochSecond()).isEqualTo(-17L);
        assertThat(datePosition.legacyDatabaseOrder()).isFalse();

        String undated = codec.encode("q", "KR", 2, datePosition.advanceDatabase(8, null));
        var nullPosition = codec.decode(undated, "q", "KR", 2);
        assertThat(nullPosition.lastReleaseEpochSecond()).isNull();
        assertThat(nullPosition.legacyDatabaseOrder()).isFalse();

        String legacy = "M3w4ZTM1YzJjZDNiZjY2NDFiZGIwZTIwNTBiNzY5MzJjYmIyZTYwMzRhMGRkYWNjMWQ5YmVhODJhNmJhNTdm"
                + "N2NmfElUVU5FU3xLUnwyfERCfDd8MzB8fDB8MTc5MjYyNzIwMHwxNzkyNjI5MDAw"
                + ".gWX--tCKBWcujraY9-DB2tw9c88Nh1mBBU2sto7wOhU";
        var oldPosition = codec.decode(legacy, "q", "KR", 2);
        assertThat(oldPosition.lastDbId()).isEqualTo(7);
        assertThat(oldPosition.legacyDatabaseOrder()).isTrue();
        assertThat(codec.encode("q", "KR", 2, oldPosition)).isEqualTo(legacy);
    }

    @Test
    void relevanceCursorSignsTheTierAndMaintainsLegacyReleaseOrder() {
        String token = codec.encode("한로로", "KR", 2, codec.database(7, 30, 12L, 0));
        var position = codec.decode(token, "한로로", "KR", 2);
        assertThat(position.lastMatchPriority()).isZero();
        assertThat(position.lastReleaseEpochSecond()).isEqualTo(12L);
        String next = codec.encode("한로로", "KR", 2, position.advanceDatabase(8, null, 1));
        var boundary = codec.decode(next, "한로로", "KR", 2);
        assertThat(boundary.lastMatchPriority()).isEqualTo(1);
        assertThat(boundary.lastReleaseEpochSecond()).isNull();
        assertThat(boundary.highWatermark()).isEqualTo(30);
        assertThat(boundary.expiresAt()).isEqualTo(position.expiresAt());

        String outOfRange = codec.encode("한로로", "KR", 2, codec.database(7, 30, 12L, 3));
        assertThatThrownBy(() -> codec.decode(outOfRange, "한로로", "KR", 2))
                .isInstanceOf(MusicRecordException.class);

        String dated = codec.encode("한로로", "KR", 2, codec.database(7, 30, 12L));
        var datedPosition = codec.decode(dated, "한로로", "KR", 2);
        assertThat(datedPosition.lastMatchPriority()).isNull();
        assertThat(datedPosition.advanceDatabase(8, 11L, 1).lastMatchPriority()).isNull();
        assertThat(codec.encode("한로로", "KR", 2, datedPosition)).isEqualTo(dated);
    }

    @Test
    void databaseCursorExpiresWithoutSlidingOnLaterPages() {
        String first = codec.encode("q", "KR", 20, codec.database(30, 50));
        var later = new MusicSearchCursorCodec("secret", Clock.fixed(now.plusSeconds(1799), ZoneOffset.UTC));
        String next = later.encode("q", "KR", 20, later.decode(first, "q", "KR", 20).advance(10, 0));
        assertThat(later.decode(next, "q", "KR", 20).expiresAt()).isEqualTo(now.plusSeconds(1800));
        var expired = new MusicSearchCursorCodec("secret", Clock.fixed(now.plusSeconds(1800), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.decode(next, "q", "KR", 20)).hasMessage("search query required");
    }

    @Test
    void externalCursorRetainsSnapshotAndExpiryForGoneHandling() {
        String id = java.util.UUID.randomUUID().toString();
        var cursor = new MusicSearchCursorCodec.Cursor("ITUNES", 0, 0, id, 20, now, now.plusSeconds(300));
        String token = codec.encode("q", "KR", 20, cursor);
        var expired = new MusicSearchCursorCodec("secret", Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC));
        assertThat(expired.decode(token, "q", "KR", 20)).isEqualTo(cursor);
        String invalid = codec.encode("q", "KR", 20, cursor.advance(0, 21));
        assertThatThrownBy(() -> codec.decode(invalid, "q", "KR", 20)).isInstanceOf(MusicRecordException.class);
    }
}
