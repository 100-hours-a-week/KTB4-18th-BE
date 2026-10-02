package com.muse.meomuneum.music.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.muse.meomuneum.music.exception.MusicMetadataException;

public final class MusicMetadataPolicy {
    public static final int MAX_ARTIST_NAME_LENGTH = 1000;
    private static final int MAX_TITLE_LENGTH = 255;
    private static final Logger log = LoggerFactory.getLogger(MusicMetadataPolicy.class);

    private MusicMetadataPolicy() {
    }

    public static void validate(String title, String artistName) {
        validateField("title", title, MAX_TITLE_LENGTH);
        validateField("artist_name", artistName, MAX_ARTIST_NAME_LENGTH);
    }

    private static void validateField(String field, String value, int maxLength) {
        // MySQL VARCHAR limits count characters, including supplementary Unicode characters once.
        int length = value == null ? 0 : value.codePointCount(0, value.length());
        if (value == null || value.isBlank() || length > maxLength) {
            String reason = field + (value == null || value.isBlank() ? "_missing" : "_too_long");
            log.warn("event=music_metadata_invalid reason={} length={} maxLength={}", reason, length, maxLength);
            throw new MusicMetadataException(reason);
        }
    }
}
