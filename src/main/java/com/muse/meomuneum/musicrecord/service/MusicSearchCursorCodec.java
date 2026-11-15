package com.muse.meomuneum.musicrecord.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

@Component
public class MusicSearchCursorCodec {
    private static final Duration CURSOR_LIFETIME = Duration.ofMinutes(30);
    private final byte[] secret;
    private final Clock clock;

    public MusicSearchCursorCodec(String secret) {
        this(secret, Clock.systemUTC());
    }

    @Autowired
    public MusicSearchCursorCodec(@Value("${auth.jwt.secret}") String secret, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public Cursor database(long lastId, long highWatermark) {
        return database(lastId, highWatermark, null, null, true);
    }

    public Cursor database(long lastId, long highWatermark, Long releaseEpochSecond) {
        return database(lastId, highWatermark, releaseEpochSecond, null, false);
    }

    public Cursor database(long lastId, long highWatermark, Long releaseEpochSecond, Integer matchPriority) {
        return database(lastId, highWatermark, releaseEpochSecond, matchPriority, false);
    }

    private Cursor database(long lastId, long highWatermark, Long releaseEpochSecond, Integer matchPriority,
            boolean legacyOrder) {
        Instant now = clock.instant();
        return new Cursor("DB", lastId, highWatermark, "", 0, now, now.plus(CURSOR_LIFETIME), releaseEpochSecond,
                legacyOrder, matchPriority);
    }

    public String encode(String query, String country, int size, Cursor cursor) {
        boolean datedOrder = "DB".equals(cursor.source()) && !cursor.legacyDatabaseOrder();
        boolean rankedOrder = datedOrder && cursor.lastMatchPriority() != null;
        String version = rankedOrder ? "5" : datedOrder ? "4" : "3";
        String payload = version + "|" + hash(query) + "|ITUNES|" + country + "|" + size + "|"
                + cursor.source()
                + "|" + cursor.lastDbId() + "|" + cursor.highWatermark() + "|" + cursor.snapshotId()
                + "|" + cursor.offset() + "|" + cursor.issuedAt().getEpochSecond()
                + "|" + cursor.expiresAt().getEpochSecond();
        if (datedOrder) {
            payload += "|" + (cursor.lastReleaseEpochSecond() == null ? "" : cursor.lastReleaseEpochSecond());
        }
        if (rankedOrder) {
            payload += "|" + cursor.lastMatchPriority();
        }
        return base64(payload.getBytes(StandardCharsets.UTF_8)) + "." + base64(signature(payload));
    }

    public Cursor decode(String value, String query, String country, int size) {
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2) {
                throw invalid();
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(Base64.getUrlDecoder().decode(parts[1]), signature(payload))) {
                throw invalid();
            }
            String[] fields = payload.split("\\|", -1);
            boolean legacy = "3".equals(fields[0]) && fields.length == 12;
            boolean rankedOrder = "5".equals(fields[0]) && fields.length == 14 && "DB".equals(fields[5]);
            boolean datedOrder = rankedOrder
                    || ("4".equals(fields[0]) && fields.length == 13 && "DB".equals(fields[5]));
            if ((!legacy && !datedOrder) || !hash(query).equals(fields[1])
                    || !"ITUNES".equals(fields[2]) || !country.equals(fields[3])
                    || size != Integer.parseInt(fields[4])) {
                throw invalid();
            }
            Cursor cursor = new Cursor(fields[5], Long.parseLong(fields[6]), Long.parseLong(fields[7]),
                    fields[8], Integer.parseInt(fields[9]), Instant.ofEpochSecond(Long.parseLong(fields[10])),
                    Instant.ofEpochSecond(Long.parseLong(fields[11])),
                    datedOrder && !fields[12].isEmpty() ? Long.valueOf(fields[12]) : null,
                    legacy && "DB".equals(fields[5]), rankedOrder ? Integer.valueOf(fields[13]) : null);
            boolean database = "DB".equals(cursor.source());
            boolean external = "ITUNES".equals(cursor.source());
            Duration lifetime = Duration.between(cursor.issuedAt(), cursor.expiresAt());
            if ((rankedOrder && (cursor.lastMatchPriority() < 0 || cursor.lastMatchPriority() > 2))
                    || (!database && !external) || size < 1 || size > 20 || cursor.highWatermark() < 0
                    || cursor.issuedAt().isAfter(clock.instant()) || lifetime.isNegative() || lifetime.isZero()
                    || lifetime.compareTo(database ? CURSOR_LIFETIME : Duration.ofMinutes(5)) > 0
                    || (database && (cursor.lastDbId() <= 0 || cursor.lastDbId() > cursor.highWatermark()
                            || cursor.offset() != 0 || !cursor.snapshotId().isEmpty()
                            || !cursor.expiresAt().isAfter(clock.instant())))
                    || (external && (cursor.lastDbId() != 0 || cursor.highWatermark() != 0
                            || cursor.offset() < 1 || cursor.offset() >= 200 || cursor.offset() % size != 0
                            || !cursor.snapshotId().matches("[0-9a-f-]{36}")))) {
                throw invalid();
            }
            return cursor;
        } catch (MusicRecordException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    public String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("cursor hash unavailable", exception);
        }
    }

    private byte[] signature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("cursor signing unavailable", exception);
        }
    }

    private String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private MusicRecordException invalid() {
        return new MusicRecordException("music_cursor_invalid", HttpStatus.BAD_REQUEST, "search query required");
    }

    public record Cursor(String source, long lastDbId, long highWatermark, String snapshotId, int offset,
            Instant issuedAt, Instant expiresAt, Long lastReleaseEpochSecond, boolean legacyDatabaseOrder,
            Integer lastMatchPriority) {
        public Cursor(String source, long lastDbId, long highWatermark, String snapshotId, int offset,
                Instant issuedAt, Instant expiresAt) {
            this(source, lastDbId, highWatermark, snapshotId, offset, issuedAt, expiresAt, null, "DB".equals(source),
                    null);
        }

        public Cursor advance(long lastId, int nextOffset) {
            return new Cursor(source, lastId, highWatermark, snapshotId, nextOffset, issuedAt, expiresAt,
                    lastReleaseEpochSecond, legacyDatabaseOrder, lastMatchPriority);
        }

        public Cursor advanceDatabase(long lastId, Long releaseEpochSecond) {
            return advanceDatabase(lastId, releaseEpochSecond, lastMatchPriority);
        }

        public Cursor advanceDatabase(long lastId, Long releaseEpochSecond, Integer matchPriority) {
            return new Cursor(source, lastId, highWatermark, snapshotId, 0, issuedAt, expiresAt,
                    legacyDatabaseOrder ? null : releaseEpochSecond, legacyDatabaseOrder,
                    lastMatchPriority == null ? null : matchPriority);
        }
    }
}
