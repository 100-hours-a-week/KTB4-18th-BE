package com.muse.meomuneum.musicrecord.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class MusicRecordDtos {
    private MusicRecordDtos() {
    }

    public record LocationRequest(@NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            @NotNull @DecimalMin("0.0") Double accuracy_meters) {
    }

    public record MapDot(long map_dot_id, String code) {
    }

    public record RegionPart(long region_id, String code, String name) {
    }

    public record Region(RegionPart sido, RegionPart sigungu) {
    }

    public record LocationResponse(MapDot map_dot, Region region, String location_resolution_token,
            long expires_in) {
    }

    public record MusicSearchResponse(List<MusicItem> items, String next_cursor, boolean has_next) {
    }

    public record MusicItem(Long music_id, String provider, String external_music_id, String title,
            String artist_name, String album_cover_url, String preview_url, String youtube_video_id,
            boolean is_queueable) {
    }

    public record MusicSummary(long music_id, String title, String artist_name, String album_cover_url) {
    }

    public record MusicSelection(@NotBlank @Size(max = 30) String provider,
            @NotBlank @Size(max = 64) String external_music_id) {
    }

    public record CreateRequest(@NotNull @Valid MusicSelection music,
            @NotBlank @Size(max = 1000) String location_resolution_token,
            @Size(max = 100) String custom_place_name,
            @Size(max = 500) String emotion_memo) {
    }

    public record CreateResponse(long record_id, long map_dot_id, Region region,
            String custom_place_name, Instant created_at) {
    }

    public record MusicRecordResponse(long record_id, MusicSummary music, long map_dot_id, Region region,
            String custom_place_name, String emotion_memo, Instant created_at) {
    }

    public record MusicRecordListResponse(List<MusicRecordResponse> items, String next_cursor, boolean has_next) {
    }

    public record MusicRecordDetailResponse(long record_id, MusicSummary music, long map_dot_id, Region region,
            String custom_place_name, String emotion_memo, Instant created_at, Instant updated_at) {
    }

    public record UpdateResponse(long record_id, Instant updated_at) {
    }
}
