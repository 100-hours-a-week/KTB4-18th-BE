package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.muse.meomuneum.chat.region.domain.RegionLevel;
import com.muse.meomuneum.chat.region.repository.RegionRepository;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "auth.jwt.secret=development-only-secret-with-at-least-32-bytes")
@ActiveProfiles({"test", "music-record-local"})
@EnabledIfEnvironmentVariable(named = "MUSIC_RECORD_LOCAL_TESTS", matches = "true")
@Transactional
class MusicRecordApiDatabaseIntegrationTest {
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenProvider jwt;
    @Autowired
    private LocationResolutionTokenProvider locations;
    @Autowired
    private RegionRepository regions;
    @Autowired
    private MusicRecordRepository musicRepository;
    @Autowired
    private MusicSearchCursorCodec searchCursors;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void createListDetailAndPatchUseOriginalContractAndOwnerRules() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long userId = positiveId();
        long sidoId = positiveId();
        long sigunguId = positiveId();
        long dotSigunguId = positiveId();
        long dotId = positiveId();
        long musicId = positiveId();
        String externalId = String.valueOf(musicId);
        jdbc.update("INSERT INTO users (id,email,password_hash,nickname,role) VALUES (?,?,?,?,?)",
                userId, suffix + "@test.local", "test-only", "테스트", "USER");
        jdbc.update("INSERT INTO regions (id,code,name,level,is_active) VALUES (?,?,?,?,TRUE)",
                sidoId, "s" + suffix, "서울특별시", "SIDO");
        jdbc.update("INSERT INTO regions (id,parent_id,code,name,level,is_active) VALUES (?,?,?,?,?,TRUE)",
                sigunguId, sidoId, "g" + suffix, "성동구", "SIGUNGU");
        jdbc.update("INSERT INTO regions (id,parent_id,code,name,level,is_active) VALUES (?,?,?,?,?,TRUE)",
                dotSigunguId, sidoId, "x" + suffix, "마포구", "SIGUNGU");
        jdbc.update("INSERT INTO map_dots (id,code,region_id,latitude,longitude,is_active) "
                + "VALUES (?,?,?,?,?,TRUE)", dotId, "d" + suffix, dotSigunguId, 37.5, 127.0);
        jdbc.update("INSERT INTO music (id,provider,external_music_id,title,artist_name) VALUES (?,?,?,?,?)",
                musicId, "ITUNES", externalId, "테스트 노래", "테스트 가수");
        var mismatched = musicRepository.findStoredMusicByIds(List.of(externalId), musicId, "다른 검색어");
        assertThat(mismatched.get(externalId).matchesSearch()).isFalse();
        assertThat(mismatched.get(externalId).music().title()).isEqualTo("테스트 노래");
        var matched = musicRepository.findStoredMusicByIds(List.of(externalId), musicId, "테스트");
        assertThat(matched.get(externalId).matchesSearch()).isTrue();
        String bearer = bearer(userId);
        var sido = regions.findByCodeAndLevelAndActiveTrue("s" + suffix, RegionLevel.SIDO).orElseThrow();
        var sigungu = regions.findByCodeAndLevelAndActiveTrue("g" + suffix, RegionLevel.SIGUNGU)
                .orElseThrow();
        String locationToken = locations.issue(userId, sido, sigungu, dotId).value();
        MvcResult csrfResponse = mvc.perform(get("/api/v1/auth/token/csrf"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) csrfResponse.getRequest().getSession(false);
        String csrf = mapper.readTree(csrfResponse.getResponse().getContentAsString())
                .path("data").path("csrf_token").asText();

        MvcResult created = mvc.perform(post("/api/v1/music-records")
                .session(session).header("X-CSRF-TOKEN", csrf)
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"music\":{\"provider\":\"ITUNES\",\"external_music_id\":\""
                        + externalId + "\"},\"location_resolution_token\":\""
                        + locationToken + "\",\"emotion_memo\":\"처음\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("music record created"))
                .andExpect(jsonPath("$.data.map_dot_id").value(dotId))
                .andExpect(jsonPath("$.data.region.sigungu.name").value("성동구"))
                .andReturn();
        long recordId = mapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("record_id").asLong();
        assertThat(recordId).isPositive();
        Instant responseCreatedAt = Instant.parse(mapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("created_at").asText());
        LocalDateTime storedCreatedAt = jdbc.queryForObject("SELECT created_at FROM music_records WHERE id=?",
                (row, index) -> row.getObject("created_at", LocalDateTime.class), recordId);
        assertThat(responseCreatedAt).isEqualTo(storedCreatedAt.toInstant(ZoneOffset.UTC));

        mvc.perform(get("/api/v1/users/me/music-records")
                .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("my music records retrieved"))
                .andExpect(jsonPath("$.data.items[0].record_id").value(recordId))
                .andExpect(jsonPath("$.data.items[0].music.title").value("테스트 노래"));
        mvc.perform(get("/api/v1/music-records/{recordId}", recordId)
                .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emotion_memo").value("처음"));
        MvcResult nextCsrf = mvc.perform(get("/api/v1/auth/token/csrf").session(session))
                .andExpect(status().isOk()).andReturn();
        csrf = mapper.readTree(nextCsrf.getResponse().getContentAsString())
                .path("data").path("csrf_token").asText();
        MvcResult patched = mvc.perform(patch("/api/v1/music-records/{recordId}", recordId)
                .session(session).header("X-CSRF-TOKEN", csrf)
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"emotion_memo\":\"수정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.record_id").value(recordId))
                .andReturn();
        Instant responseUpdatedAt = Instant.parse(mapper.readTree(patched.getResponse().getContentAsString())
                .path("data").path("updated_at").asText());
        LocalDateTime storedUpdatedAt = jdbc.queryForObject("SELECT updated_at FROM music_records WHERE id=?",
                (row, index) -> row.getObject("updated_at", LocalDateTime.class), recordId);
        assertThat(responseUpdatedAt).isEqualTo(storedUpdatedAt.toInstant(ZoneOffset.UTC));
        mvc.perform(get("/api/v1/music-records/{recordId}", recordId)
                .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emotion_memo").value("수정"));
        mvc.perform(get("/api/v1/music-records/{recordId}", recordId)
                .header("Authorization", "Bearer " + bearer(userId + 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("forbidden"));
        mvc.perform(get("/api/v1/music-records/{recordId}", Long.MAX_VALUE)
                .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("music record not found"));
    }

    @Test
    void searchTreatsPercentUnderscoreAndEscapeCharacterAsLiteralText() {
        long firstId = positiveId();
        long secondId = firstId + 1;
        long thirdId = firstId + 2;
        jdbc.update("INSERT INTO music (id,provider,external_music_id,title,artist_name) VALUES (?,?,?,?,?)",
                firstId, "ITUNES", String.valueOf(firstId), "노래 50%_!", "가수");
        jdbc.update("INSERT INTO music (id,provider,external_music_id,title,artist_name) VALUES (?,?,?,?,?)",
                secondId, "ITUNES", String.valueOf(secondId), "노래 보통", "가수");
        jdbc.update("INSERT INTO music (id,provider,external_music_id,title,artist_name) VALUES (?,?,?,?,?)",
                thirdId, "ITUNES", String.valueOf(thirdId), "다른 곡", "이름% 가수");
        List<String> ids = List.of(String.valueOf(firstId), String.valueOf(secondId), String.valueOf(thirdId));

        assertThat(musicRepository.searchMusic("%", Long.MAX_VALUE, thirdId, 10))
                .extracting(item -> item.external_music_id())
                .contains(String.valueOf(firstId), String.valueOf(thirdId))
                .doesNotContain(String.valueOf(secondId));
        assertThat(musicRepository.searchMusic("_", Long.MAX_VALUE, thirdId, 10))
                .extracting(item -> item.external_music_id())
                .contains(String.valueOf(firstId))
                .doesNotContain(String.valueOf(secondId), String.valueOf(thirdId));
        assertThat(musicRepository.searchMusic("!", Long.MAX_VALUE, thirdId, 10))
                .extracting(item -> item.external_music_id())
                .contains(String.valueOf(firstId))
                .doesNotContain(String.valueOf(secondId), String.valueOf(thirdId));

        var percentMatches = musicRepository.findStoredMusicByIds(ids, thirdId, "%");
        assertThat(percentMatches.get(String.valueOf(firstId)).matchesSearch()).isTrue();
        assertThat(percentMatches.get(String.valueOf(secondId)).matchesSearch()).isFalse();
        assertThat(percentMatches.get(String.valueOf(thirdId)).matchesSearch()).isTrue();
        var underscoreMatches = musicRepository.findStoredMusicByIds(ids, thirdId, "_");
        assertThat(underscoreMatches.get(String.valueOf(firstId)).matchesSearch()).isTrue();
        assertThat(underscoreMatches.get(String.valueOf(secondId)).matchesSearch()).isFalse();
        assertThat(underscoreMatches.get(String.valueOf(thirdId)).matchesSearch()).isFalse();
        var escapeMatches = musicRepository.findStoredMusicByIds(ids, thirdId, "!");
        assertThat(escapeMatches.get(String.valueOf(firstId)).matchesSearch()).isTrue();
        assertThat(escapeMatches.get(String.valueOf(secondId)).matchesSearch()).isFalse();
        assertThat(escapeMatches.get(String.valueOf(thirdId)).matchesSearch()).isFalse();
    }

    @Test
    void cursorKeepsDatabaseHighWatermarkWhenMatchingSongIsInsertedBetweenPages() {
        Long previousMax = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM music", Long.class);
        long base = previousMax + 1000;
        String query = "경계" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 21; i++) {
            long id = base + i;
            jdbc.update("INSERT INTO music (id,provider,external_music_id,title,artist_name) "
                    + "VALUES (?,?,?,?,?)", id, "ITUNES", String.valueOf(id), query + " 노래", "가수");
        }
        ItunesMusicSearchClient itunes = mock(ItunesMusicSearchClient.class);
        when(itunes.search(query)).thenReturn(List.of());
        MusicRecordService service = new MusicRecordService(musicRepository, itunes, locations, searchCursors);

        var first = service.search(query, "ITUNES", null, 20);
        assertThat(first.items()).hasSize(20);
        assertThat(first.next_cursor()).isNotBlank();
        verify(itunes, times(0)).search(query);

        long newlyInsertedId = base + 21;
        jdbc.update("INSERT INTO music (id,provider,external_music_id,title,artist_name) "
                + "VALUES (?,?,?,?,?)", newlyInsertedId, "ITUNES", String.valueOf(newlyInsertedId),
                query + " 새 노래", "가수");
        var second = service.search(query, "ITUNES", first.next_cursor(), 20);

        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().music_id()).isEqualTo(base);
        assertThat(second.has_next()).isFalse();
        verify(itunes, times(0)).search(query);
    }

    private String bearer(long userId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getRole()).thenReturn(UserRole.USER);
        return jwt.createAccessToken(user);
    }

    private long positiveId() {
        return 1_000_000_000_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L);
    }
}
