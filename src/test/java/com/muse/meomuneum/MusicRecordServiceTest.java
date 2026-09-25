package com.muse.meomuneum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicSummary;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.CreateRequest;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicSelection;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordDetailResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.Region;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.RegionPart;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;

class MusicRecordServiceTest {
    private MusicRecordRepository repository;
    private LocationResolutionTokenProvider tokens;
    private ItunesMusicSearchClient itunes;
    private MusicRecordService service;
    private ObjectMapper mapper;
    private MusicRecordDetailResponse detail;

    @BeforeEach
    void setUp() {
        repository = mock(MusicRecordRepository.class);
        tokens = mock(LocationResolutionTokenProvider.class);
        itunes = mock(ItunesMusicSearchClient.class);
        service = new MusicRecordService(repository, itunes,
                tokens, new MusicSearchCursorCodec("test-secret"));
        mapper = new ObjectMapper();
        detail = new MusicRecordDetailResponse(7L,
                new MusicSummary(11L, "밤편지", "아이유", null),
                5L, new Region(new RegionPart(5L, "11", "서울특별시"),
                        new RegionPart(4L, "11440", "마포구")), "홍대", "산책 중",
                Instant.parse("2026-09-22T06:30:00Z"), null);
    }

    @Test
    void detailIsScopedToOwner() {
        when(repository.findRecord(1L, 7L)).thenReturn(Optional.of(detail));
        assertThat(service.detail(1L, 7L)).isEqualTo(detail);
        assertThatThrownBy(() -> service.detail(2L, 7L))
                .isInstanceOf(MusicRecordException.class);
    }

    @Test
    void unchangedPatchDoesNotWrite() {
        when(repository.findRecord(1L, 7L)).thenReturn(Optional.of(detail));
        assertThatThrownBy(() -> service.update(1L, 7L,
                mapper.readTree("{\"emotion_memo\":\"산책 중\"}")))
                .isInstanceOf(MusicRecordException.class);
        verify(repository, never()).updateRecord(any(Long.class), any(Long.class), any(), any(), any());
    }

    @Test
    void changedMemoKeepsMusicAndCreatedAt() {
        when(repository.findRecord(1L, 7L)).thenReturn(Optional.of(detail));
        when(repository.updateRecord(eq(1L), eq(7L), eq("홍대"),
                eq("새로운 기분"), any(Instant.class))).thenReturn(1);
        var result = service.update(1L, 7L,
                mapper.readTree("{\"emotion_memo\":\"새로운 기분\"}"));
        assertThat(result.record_id()).isEqualTo(7L);
        assertThat(result.updated_at()).isNotNull();
    }

    @Test
    void changingMusicIsRejectedByOriginalContract() {
        when(repository.findRecord(1L, 7L)).thenReturn(Optional.of(detail));
        assertThatThrownBy(() -> service.update(1L, 7L, mapper.readTree(
                "{\"music\":{\"provider\":\"ITUNES\",\"external_music_id\":\"456\"}}")))
                .isInstanceOf(MusicRecordException.class);
        verify(repository, never()).updateRecord(any(Long.class), any(Long.class), any(), any(), any());
    }

    @Test
    void searchUsesDatabaseBeforeExternalProviderAndStableCursor() {
        var stored = new MusicItem(8L, "ITUNES", "100", "밤편지", "아이유", null, null, null, false);
        when(repository.highestMusicId()).thenReturn(8L);
        when(repository.searchMusic("밤", Long.MAX_VALUE, 8L, 2)).thenReturn(List.of(stored));

        var result = service.search("밤", "ITUNES", null, 1);

        assertThat(result.items()).containsExactly(stored);
        assertThat(result.has_next()).isFalse();
        assertThat(result.next_cursor()).isNull();
        verify(itunes, never()).search("밤");
    }

    @Test
    void databaseCursorNeverSwitchesToItunesAfterSongsDisappear() {
        var first = new MusicItem(8L, "ITUNES", "100", "밤편지", "아이유", null, null, null, false);
        var second = new MusicItem(7L, "ITUNES", "101", "밤의 노래", "가수", null, null, null, false);
        when(repository.highestMusicId()).thenReturn(8L);
        when(repository.searchMusic("밤", Long.MAX_VALUE, 8L, 2)).thenReturn(List.of(first, second));
        when(repository.searchMusic("밤", 8L, 8L, 2)).thenReturn(List.of());

        var initial = service.search("밤", "ITUNES", null, 1);
        var next = service.search("밤", "ITUNES", initial.next_cursor(), 1);

        assertThat(initial.items()).containsExactly(first);
        assertThat(next.items()).isEmpty();
        assertThat(next.has_next()).isFalse();
        verify(itunes, never()).search("밤");
    }

    @Test
    void databaseFailureNeverFallsBackToItunes() {
        when(repository.highestMusicId()).thenReturn(8L);
        when(repository.searchMusic("밤", Long.MAX_VALUE, 8L, 21))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.search("밤", "ITUNES", null, 20))
                .isInstanceOf(IllegalStateException.class);
        verify(itunes, never()).search("밤");
    }

    @Test
    void storedSongWhoseMetadataDoesNotMatchKeepsItunesTextAndStoredPlaybackMetadata() {
        var stored = new MusicItem(8L, "ITUNES", "100", "원래 제목", "저장된 가수",
                "stored-cover", "stored-preview", "AbCdEfGh123", true);
        var external = new MusicItem(null, "ITUNES", "100", "검색어 노래", "외부 가수",
                "external-cover", "external-preview", null, false);
        when(repository.highestMusicId()).thenReturn(8L);
        when(repository.searchMusic("검색어", Long.MAX_VALUE, 8L, 21)).thenReturn(List.of());
        when(itunes.search("검색어")).thenReturn(List.of(external));
        when(repository.findStoredMusicByIds(List.of("100"), 8L, "검색어"))
                .thenReturn(java.util.Map.of("100", new MusicRecordRepository.StoredMusic(stored, false)));

        var result = service.search("검색어", "ITUNES", null, 20);

        assertThat(result.items()).containsExactly(new MusicItem(8L, "ITUNES", "100", "검색어 노래",
                "외부 가수", "external-cover", "stored-preview", "AbCdEfGh123", true));
        assertThat(result.has_next()).isFalse();
    }

    @Test
    void externalCandidateDoesNotFallBackToStaleStoredCover() {
        var stored = new MusicItem(8L, "ITUNES", "100", "옛 제목", "옛 가수",
                "stale-cover", null, null, false);
        var external = new MusicItem(null, "ITUNES", "100", "새 검색어 노래", "새 가수",
                null, "external-preview", null, false);
        when(repository.highestMusicId()).thenReturn(8L);
        when(repository.searchMusic("검색어", Long.MAX_VALUE, 8L, 21)).thenReturn(List.of());
        when(itunes.search("검색어")).thenReturn(List.of(external));
        when(repository.findStoredMusicByIds(List.of("100"), 8L, "검색어"))
                .thenReturn(java.util.Map.of("100", new MusicRecordRepository.StoredMusic(stored, false)));

        var result = service.search("검색어", "ITUNES", null, 20);

        assertThat(result.items()).containsExactly(new MusicItem(8L, "ITUNES", "100",
                "새 검색어 노래", "새 가수", null, "external-preview", null, false));
    }

    @Test
    void createIgnoresClientMetadataAndUsesProviderLookup() {
        var location = new MusicRecordRepository.Location(3L, "dot-3", 4L, "11440", "마포구",
                5L, "11", "서울특별시");
        var selected = new MusicItem(null, "ITUNES", "200", "서버 제목", "서버 가수",
                null, null, null, false);
        when(tokens.validate("location-token", 1L)).thenReturn(
                new LocationResolutionClaims(1L, 5L, "11", 4L, "11440", 3L,
                        Instant.now().plusSeconds(300)));
        when(repository.findLocation(3L, 4L, 5L)).thenReturn(Optional.of(location));
        when(repository.findMusic("ITUNES", "200")).thenReturn(Optional.empty());
        when(itunes.lookup("200")).thenReturn(Optional.of(selected));
        when(repository.upsertMusic(selected)).thenReturn(9L);
        when(repository.saveRecord(eq(1L), eq(9L), eq(location), eq("홍대"), eq("좋아요"),
                any(Instant.class))).thenReturn(7L);
        Instant storedAt = Instant.parse("2026-09-22T06:30:00Z");
        when(repository.findCreatedAt(7L)).thenReturn(storedAt);

        var result = service.create(1L, new CreateRequest(new MusicSelection("ITUNES", "200"),
                "location-token", "홍대", "좋아요"));

        assertThat(result.record_id()).isEqualTo(7L);
        assertThat(result.created_at()).isEqualTo(storedAt);
        verify(repository).upsertMusic(selected);
    }

    @Test
    void createWithExistingMusicDoesNotCallItunesLookup() {
        var location = new MusicRecordRepository.Location(3L, "dot-3", 4L, "11440", "마포구",
                5L, "11", "서울특별시");
        var storedMusic = new MusicItem(9L, "ITUNES", "200", "DB 제목", "DB 가수",
                null, null, null, false);
        when(tokens.validate("location-token", 1L)).thenReturn(
                new LocationResolutionClaims(1L, 5L, "11", 4L, "11440", 3L,
                        Instant.now().plusSeconds(300)));
        when(repository.findLocation(3L, 4L, 5L)).thenReturn(Optional.of(location));
        when(repository.findMusic("ITUNES", "200")).thenReturn(Optional.of(storedMusic));
        when(repository.saveRecord(eq(1L), eq(9L), eq(location), any(), any(),
                any(Instant.class))).thenReturn(7L);
        when(repository.findCreatedAt(7L)).thenReturn(Instant.parse("2026-09-22T06:30:00Z"));

        var created = service.create(1L, new CreateRequest(new MusicSelection("ITUNES", "200"),
                "location-token", null, null));

        assertThat(created.record_id()).isEqualTo(7L);
        verify(itunes, never()).lookup("200");
        verify(repository, never()).upsertMusic(any(MusicItem.class));
    }

    @Test
    void regionOnlyLocationTokenCannotCreateMusicRecord() {
        when(tokens.validate("old-region-token", 1L)).thenReturn(
                new LocationResolutionClaims(1L, 5L, "11", 4L, "11440",
                        Instant.now().plusSeconds(300)));

        assertThatThrownBy(() -> service.create(1L, new CreateRequest(
                new MusicSelection("ITUNES", "200"), "old-region-token", null, null)))
                .isInstanceOf(MusicRecordException.class)
                .hasMessage("invalid request");
        verify(repository, never()).findLocation(any(Long.class), any(Long.class), any(Long.class));
    }
}
