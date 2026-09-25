package com.muse.meomuneum.musicrecord.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.CreateRequest;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.CreateResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.LocationRequest;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.LocationResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MapDot;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordDetailResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordListResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicSearchResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.UpdateResponse;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.provider.KakaoReverseGeocodingClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository.Location;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository.StoredMusic;

@Service
public class MusicRecordService {
    private static final int PAGE_SIZE = 20;
    private final MusicRecordRepository repository;
    private final ItunesMusicSearchClient itunes;
    private final LocationTokenService tokens;
    private final KakaoReverseGeocodingClient kakao;
    private final MusicSearchCursorCodec searchCursors;
    private final Clock clock = Clock.systemUTC();

    public MusicRecordService(MusicRecordRepository repository, ItunesMusicSearchClient itunes,
            LocationTokenService tokens, KakaoReverseGeocodingClient kakao,
            MusicSearchCursorCodec searchCursors) {
        this.repository = repository;
        this.itunes = itunes;
        this.tokens = tokens;
        this.kakao = kakao;
        this.searchCursors = searchCursors;
    }

    public LocationResponse resolveLocation(long userId, LocationRequest request) {
        if (request.accuracy_meters() > 100) {
            throw new MusicRecordException("gps_accuracy_over_100m", HttpStatus.BAD_REQUEST,
                    "invalid coordinates or location accuracy insufficient");
        }
        Location location = repository.findNearestLocation(request.latitude(), request.longitude())
                .orElseThrow(() -> new MusicRecordException("location_not_mapped", HttpStatus.NOT_FOUND,
                        "map dot not found"));
        try {
            kakao.reverseGeocode(request.latitude(), request.longitude());
        } catch (MusicRecordException exception) {
            throw new MusicRecordException(exception.reason(), HttpStatus.BAD_GATEWAY,
                    "reverse geocoding failed");
        }
        Instant expires = clock.instant().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        String token = tokens.create(userId, location, expires);
        return new LocationResponse(new MapDot(location.dotId(), location.dotCode()), location.region(),
                token, 300);
    }

    public MusicSearchResponse search(String query, String provider, String cursor, Integer size) {
        if (query == null || query.isBlank() || !"ITUNES".equals(provider)
                || (size != null && (size < 1 || size > PAGE_SIZE))) {
            throw new MusicRecordException("music_search_invalid_query", HttpStatus.BAD_REQUEST,
                    "search query required");
        }
        String normalized = query.trim();
        int pageSize = size == null ? PAGE_SIZE : size;
        MusicSearchCursorCodec.Cursor position = cursor == null || cursor.isBlank() ? null
                : searchCursors.decode(cursor, normalized);
        long highWatermark = position == null ? repository.highestMusicId() : position.highWatermark();
        if (position != null && "ITUNES".equals(position.phase())) {
            List<MusicItem> external = externalCandidates(normalized, highWatermark);
            String hash = externalHash(external);
            if (!hash.equals(position.externalHash()) || position.externalIndex() > external.size()) {
                throw invalidSearchCursor("music_external_results_changed");
            }
            return externalPage(external, normalized, highWatermark, position.lastDbId(),
                    position.externalIndex(), pageSize, hash);
        }
        long beforeId = position == null ? Long.MAX_VALUE : position.lastDbId();
        if (beforeId <= 0) {
            throw invalidSearchCursor("music_cursor_invalid_db_position");
        }
        List<MusicItem> db = repository.searchMusic(normalized, beforeId, highWatermark, pageSize + 1);
        if (db.size() > pageSize) {
            List<MusicItem> items = List.copyOf(db.subList(0, pageSize));
            long lastId = items.getLast().music_id();
            return new MusicSearchResponse(items, searchCursors.encode(normalized, "DB", lastId,
                    highWatermark, 0, ""), true);
        }
        List<MusicItem> external = externalCandidates(normalized, highWatermark);
        String hash = externalHash(external);
        List<MusicItem> items = new ArrayList<>(db);
        int count = Math.min(pageSize - items.size(), external.size());
        items.addAll(external.subList(0, count));
        boolean hasNext = count < external.size();
        long lastDbId = db.isEmpty() ? beforeId : db.getLast().music_id();
        String next = hasNext ? searchCursors.encode(normalized, "ITUNES", lastDbId,
                highWatermark, count, hash) : null;
        return new MusicSearchResponse(List.copyOf(items), next, hasNext);
    }

    private List<MusicItem> externalCandidates(String query, long highWatermark) {
        List<MusicItem> results = itunes.search(query);
        List<String> ids = results.stream().map(MusicItem::external_music_id).distinct().toList();
        Map<String, StoredMusic> stored = repository.findStoredMusicByIds(ids, highWatermark, query);
        Set<String> seen = new HashSet<>();
        return results.stream().filter(item -> seen.add(item.external_music_id()))
                .filter(item -> !stored.containsKey(item.external_music_id())
                        || !stored.get(item.external_music_id()).matchesSearch())
                .map(item -> stored.containsKey(item.external_music_id())
                        ? mergeSearchMetadata(item, stored.get(item.external_music_id()).music()) : item)
                .toList();
    }

    private MusicItem mergeSearchMetadata(MusicItem external, MusicItem stored) {
        return new MusicItem(stored.music_id(), stored.provider(), stored.external_music_id(),
                external.title(), external.artist_name(),
                external.album_cover_url(),
                stored.preview_url() == null ? external.preview_url() : stored.preview_url(),
                stored.youtube_video_id(), stored.is_queueable());
    }

    private MusicSearchResponse externalPage(List<MusicItem> external, String query, long highWatermark,
            long lastDbId, int index, int pageSize, String hash) {
        int end = Math.min(index + pageSize, external.size());
        List<MusicItem> items = List.copyOf(external.subList(index, end));
        boolean hasNext = end < external.size();
        return new MusicSearchResponse(items, hasNext ? searchCursors.encode(query, "ITUNES",
                lastDbId, highWatermark, end, hash) : null, hasNext);
    }

    private String externalHash(List<MusicItem> external) {
        return searchCursors.hash(external.stream().map(MusicItem::external_music_id)
                .collect(Collectors.joining("|")));
    }

    private MusicRecordException invalidSearchCursor(String reason) {
        return new MusicRecordException(reason, HttpStatus.BAD_REQUEST, "search query required");
    }

    @Transactional
    public CreateResponse create(long userId, CreateRequest request) {
        if (request == null || request.music() == null || !"ITUNES".equals(request.music().provider())
                || request.music().external_music_id() == null
                || !request.music().external_music_id().matches("[0-9]+")) {
            throw new MusicRecordException("music_record_invalid_music");
        }
        var claims = tokens.parse(request.location_resolution_token(), userId);
        Location location = repository.findLocation(claims.dotId(), claims.sigunguId(), claims.sidoId())
                .orElseThrow(() -> new MusicRecordException("location_token_target_missing",
                        HttpStatus.NOT_FOUND, "map dot or region not found"));
        MusicItem music = repository.findMusic(request.music().provider(),
                request.music().external_music_id()).orElseGet(() -> lookupMusic(request.music().external_music_id()));
        long musicId = music.music_id() == null ? repository.upsertMusic(music) : music.music_id();
        Instant now = clock.instant();
        long id = repository.saveRecord(userId, musicId, location,
                blankToNull(request.custom_place_name()), blankToNull(request.emotion_memo()), now);
        return new CreateResponse(id, location.dotId(), location.region(),
                blankToNull(request.custom_place_name()), repository.findCreatedAt(id));
    }

    private MusicItem lookupMusic(String trackId) {
        try {
            return itunes.lookup(trackId).orElseThrow(() -> new MusicRecordException("itunes_track_not_found"));
        } catch (MusicRecordException exception) {
            if (exception.status() == HttpStatus.BAD_GATEWAY) {
                throw new MusicRecordException("itunes_lookup_unavailable", HttpStatus.INTERNAL_SERVER_ERROR,
                        "internal server error");
            }
            throw exception;
        }
    }

    public MusicRecordListResponse list(long userId, String cursor, Integer size) {
        int pageSize = size == null ? PAGE_SIZE : Math.min(Math.max(size, 1), PAGE_SIZE);
        Cursor value = decodeRecordCursor(cursor);
        List<MusicRecordResponse> rows = repository.findRecords(userId, value.createdAt(),
                value.id(), pageSize + 1);
        boolean hasNext = rows.size() > pageSize;
        List<MusicRecordResponse> items = hasNext ? rows.subList(0, pageSize) : rows;
        String next = hasNext ? encodeRecordCursor(items.getLast()) : null;
        return new MusicRecordListResponse(items, next, hasNext);
    }

    public MusicRecordDetailResponse detail(long userId, long recordId) {
        return repository.findRecord(userId, recordId).orElseThrow(() -> {
            if (repository.existsActiveRecord(recordId)) {
                return new MusicRecordException("music_record_not_owned", HttpStatus.FORBIDDEN, "forbidden");
            }
            return new MusicRecordException("music_record_not_found", HttpStatus.NOT_FOUND,
                    "music record not found");
        });
    }

    @Transactional
    public UpdateResponse update(long userId, long recordId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new MusicRecordException("music_record_update_invalid_body");
        }
        for (java.util.Map.Entry<String, JsonNode> field : body.properties()) {
            if (!"custom_place_name".equals(field.getKey()) && !"emotion_memo".equals(field.getKey())) {
                throw new MusicRecordException("music_record_update_field_not_allowed");
            }
        }
        MusicRecordDetailResponse current = detail(userId, recordId);
        String place = body.has("custom_place_name")
                ? optionalText(body.get("custom_place_name"), 100, "custom_place_name")
                : current.custom_place_name();
        String memo = body.has("emotion_memo")
                ? optionalText(body.get("emotion_memo"), 500, "emotion_memo")
                : current.emotion_memo();
        if (Objects.equals(place, current.custom_place_name())
                && Objects.equals(memo, current.emotion_memo())) {
            throw new MusicRecordException("music_record_update_no_changes");
        }
        Instant updatedAt = clock.instant();
        if (repository.updateRecord(userId, recordId, place, memo, updatedAt) != 1) {
            throw new MusicRecordException("music_record_update_target_missing");
        }
        return new UpdateResponse(recordId, updatedAt);
    }
    private String optionalText(JsonNode value, int maxLength, String name) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().length() > maxLength) {
            throw new MusicRecordException("music_record_update_" + name + "_invalid");
        }
        return blankToNull(value.asText());
    }

    private String encodeRecordCursor(MusicRecordResponse item) {
        return encode(item.created_at() + ":" + item.record_id());
    }

    private Cursor decodeRecordCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, Long.MAX_VALUE);
        }
        try {
            String[] parts = decode(cursor).split(":");
            if (parts.length != 4) {
                throw new IllegalArgumentException("invalid cursor");
            }
            Instant createdAt = Instant.parse(parts[0] + ":" + parts[1] + ":" + parts[2]);
            long id = Long.parseLong(parts[3]);
            if (id <= 0) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new Cursor(createdAt, id);
        } catch (Exception exception) {
            throw new MusicRecordException("record_cursor_invalid", HttpStatus.BAD_REQUEST, "invalid cursor");
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Cursor(Instant createdAt, long id) {
    }
}
