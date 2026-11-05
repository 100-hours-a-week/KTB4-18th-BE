package com.muse.meomuneum.musicrecord.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.location.exception.LocationException;
import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.CreateRequest;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.CreateResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordDetailResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordListResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicSearchResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.UpdateResponse;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository.Location;

import tools.jackson.databind.JsonNode;

@Service
public class MusicRecordService {
    private static final int PAGE_SIZE = 20;
    private final MusicSearchCandidateCache candidates;
    private final MusicRecordRepository repository;
    private final ItunesMusicSearchClient itunes;
    private final LocationResolutionTokenProvider tokens;
    private final MusicSearchCursorCodec searchCursors;
    private final Clock clock = Clock.systemUTC();

    public MusicRecordService(MusicRecordRepository repository, ItunesMusicSearchClient itunes,
            LocationResolutionTokenProvider tokens,
            MusicSearchCursorCodec searchCursors, MusicSearchCandidateCache candidates) {
        this.candidates = candidates;
        this.repository = repository;
        this.itunes = itunes;
        this.tokens = tokens;
        this.searchCursors = searchCursors;
    }

    public MusicSearchResponse search(String query, String provider, String cursor, Integer size) {
        String normalized = query == null
                ? ""
                : java.text.Normalizer.normalize(query,
                        java.text.Normalizer.Form.NFC).replaceAll("[\\p{javaWhitespace}\\p{Z}]+", " ").trim();
        if (normalized.isEmpty() || !"ITUNES".equals(provider)
                || (size != null && (size < 1 || size > PAGE_SIZE))) {
            throw invalidSearchCursor("music_search_invalid_query");
        }
        int pageSize = size == null ? PAGE_SIZE : size;
        String country = itunes.country();
        MusicSearchCursorCodec.Cursor position = cursor == null || cursor.isBlank()
                ? null
                : searchCursors.decode(cursor, normalized, country, pageSize);
        if (position != null && "ITUNES".equals(position.source())) {
            var snapshot = candidates.findSnapshot(position.snapshotId()).orElseThrow(this::expiredSearch);
            if (!snapshot.key().equals(searchKey(country, normalized))
                    || position.offset() >= snapshot.items().size()
                    || position.expiresAt().getEpochSecond() != snapshot.expiresAt().getEpochSecond()) {
                throw invalidSearchCursor("music_cursor_invalid_snapshot_position");
            }
            return externalPage(snapshot, normalized, country, position.offset(), pageSize);
        }
        long highWatermark = position == null ? repository.highestMusicId() : position.highWatermark();
        long beforeId = position == null ? Long.MAX_VALUE : position.lastDbId();
        List<MusicItem> db = repository.searchMusic(normalized, beforeId, highWatermark,
                position == null ? PAGE_SIZE + 1 : pageSize + 1);
        if (position != null || db.size() > PAGE_SIZE) {
            boolean hasNext = db.size() > pageSize;
            List<MusicItem> items = List.copyOf(db.subList(0, Math.min(pageSize, db.size())));
            String next = null;
            if (hasNext) {
                long lastId = items.getLast().music_id();
                var nextPosition = position == null
                        ? searchCursors.database(lastId, highWatermark)
                        : position.advance(lastId, 0);
                next = searchCursors.encode(normalized, country, pageSize, nextPosition);
            }
            return new MusicSearchResponse(items, next, hasNext, "DB");
        }
        String key = searchKey(country, normalized);
        var snapshot = candidates.findQuery(key)
                .orElseGet(() -> candidates.put(key, country, itunes.search(normalized)));
        return externalPage(snapshot, normalized, country, 0, pageSize);
    }

    private String searchKey(String country, String query) {
        return "3:" + country + ":" + searchCursors.hash(query);
    }

    private MusicSearchResponse externalPage(MusicSearchCandidateCache.Snapshot snapshot, String query,
            String country, int offset, int size) {
        int end = Math.min(offset + size, snapshot.items().size());
        boolean hasNext = end < snapshot.items().size();
        var position = new MusicSearchCursorCodec.Cursor("ITUNES", 0, 0, snapshot.id(), end,
                snapshot.issuedAt(), snapshot.expiresAt());
        return new MusicSearchResponse(List.copyOf(snapshot.items().subList(offset, end)), hasNext
                ? searchCursors.encode(query, country, size, position)
                : null, hasNext, "ITUNES");
    }

    private MusicRecordException expiredSearch() {
        return new MusicRecordException("music_search_expired", HttpStatus.GONE, "music search expired");
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
        LocationResolutionClaims claims;
        try {
            claims = tokens.validate(request.location_resolution_token(), userId);
        } catch (LocationException exception) {
            throw new MusicRecordException("location_token_invalid");
        }
        if (claims.mapDotId() == null) {
            throw new MusicRecordException("location_token_missing_map_dot");
        }
        Location location = repository.findLocation(claims.mapDotId(), claims.sigunguRegionId(),
                claims.sidoRegionId()).orElseThrow(() -> new MusicRecordException("location_token_target_missing"));
        MusicItem music = resolveMusicForRecord(request.music().external_music_id());
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

    private MusicItem resolveMusicForRecord(String trackId) {
        return repository.findMusic("ITUNES", trackId).orElseGet(() -> lookupMusic(trackId));
    }

    private MusicItem resolveMusicForUpdate(String trackId) {
        return repository.findMusic("ITUNES", trackId).orElseGet(() -> itunes.lookup(trackId)
                .orElseThrow(() -> new MusicRecordException("itunes_track_not_found", HttpStatus.NOT_FOUND,
                        "music not found")));
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
    public void deleteAll(long userId, List<Long> recordIds) {
        if (recordIds == null || recordIds.isEmpty() || recordIds.size() > 100
                || recordIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new MusicRecordException("music_record_delete_invalid_ids");
        }
        // Keep lock acquisition order consistent across overlapping requests.
        recordIds.stream().distinct().sorted().forEach(id -> delete(userId, id));
    }

    @Transactional
    public void delete(long userId, long recordId) {
        if (repository.deleteRecord(userId, recordId, clock.instant()) == 1) {
            return;
        }
        if (repository.existsActiveRecord(recordId)) {
            throw new MusicRecordException("music_record_not_owned", HttpStatus.FORBIDDEN, "forbidden");
        }
        throw new MusicRecordException("music_record_not_found", HttpStatus.NOT_FOUND,
                "music record not found");
    }

    @Transactional
    public UpdateResponse update(long userId, long recordId, JsonNode body) {
        // Resolve ownership before inspecting supplied fields to avoid leaking record state.
        MusicRecordDetailResponse current = detail(userId, recordId);
        if (body == null || !body.isObject()) {
            throw new MusicRecordException("music_record_update_invalid_body");
        }
        for (java.util.Map.Entry<String, JsonNode> field : body.properties()) {
            if (!"custom_place_name".equals(field.getKey()) && !"emotion_memo".equals(field.getKey())
                    && !"music".equals(field.getKey())) {
                throw new MusicRecordException("music_record_update_field_not_allowed");
            }
        }
        String place = body.has("custom_place_name")
                ? optionalText(body.get("custom_place_name"), 100, "custom_place_name")
                : current.custom_place_name();
        String memo = body.has("emotion_memo")
                ? optionalText(body.get("emotion_memo"), 500, "emotion_memo")
                : current.emotion_memo();

        JsonNode musicNode = body.get("music");
        String provider = null;
        String externalId = null;
        if (musicNode != null) {
            if (!musicNode.isObject()) {
                throw new MusicRecordException("music_record_update_music_invalid");
            }
            for (java.util.Map.Entry<String, JsonNode> field : musicNode.properties()) {
                if (!"provider".equals(field.getKey()) && !"external_music_id".equals(field.getKey())) {
                    throw new MusicRecordException("music_record_update_music_invalid");
                }
            }
            JsonNode providerNode = musicNode.get("provider");
            JsonNode externalIdNode = musicNode.get("external_music_id");
            if (providerNode == null || !providerNode.isTextual()
                    || externalIdNode == null || !externalIdNode.isTextual()) {
                throw new MusicRecordException("music_record_update_music_invalid");
            }
            provider = providerNode.asText();
            externalId = externalIdNode.asText();
            if (!"ITUNES".equals(provider) || !externalId.matches("[0-9]+")) {
                throw new MusicRecordException("music_record_update_music_invalid");
            }
        }

        boolean metadataChanged = !Objects.equals(place, current.custom_place_name())
                || !Objects.equals(memo, current.emotion_memo());
        if (musicNode == null && !metadataChanged) {
            throw new MusicRecordException("music_record_update_no_changes");
        }

        if (musicNode != null) {
            MusicRecordRepository.MusicIdentity existing = repository
                    .findRecordMusicIdentity(userId, recordId)
                    .orElseThrow(() -> new MusicRecordException("music_record_not_found",
                            HttpStatus.NOT_FOUND, "music record not found"));
            boolean sameMusic = provider.equals(existing.provider()) && externalId.equals(existing.externalId());
            if (sameMusic && !metadataChanged) {
                return new UpdateResponse(recordId, current.updated_at());
            }
            if (!sameMusic) {
                MusicItem selected = resolveMusicForUpdate(externalId);
                long musicId = selected.music_id() == null ? repository.upsertMusic(selected) : selected.music_id();
                Instant updatedAt = clock.instant();
                if (repository.updateRecordMusic(userId, recordId, musicId, updatedAt) != 1) {
                    throw new MusicRecordException("music_record_update_target_missing");
                }
                if (metadataChanged && repository.updateRecord(userId, recordId, place, memo, updatedAt) != 1) {
                    throw new MusicRecordException("music_record_update_target_missing");
                }
                return new UpdateResponse(recordId, updatedAt);
            }
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
