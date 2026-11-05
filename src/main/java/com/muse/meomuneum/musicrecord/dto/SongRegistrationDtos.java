package com.muse.meomuneum.musicrecord.dto;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

public final class SongRegistrationDtos {
    private SongRegistrationDtos() {
    }

    public static boolean validTrackId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    public record Request(String externalTrackId) {
        public Request {
            if (!validTrackId(externalTrackId)) {
                throw new MusicRecordException("song_registration_invalid_track");
            }
        }
    }

    public record Response(MusicItem music, boolean created) {
    }
}
