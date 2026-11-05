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
