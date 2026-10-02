package com.muse.meomuneum.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.global.exception.GlobalExceptionHandler;
import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.musicrecord.controller.MusicRecordController;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.resolver.CurrentUserResolver;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;
import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;

import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class MusicMetadataMySqlIntegrationTest {
    private static final String MIGRATION = "V20261002133615__expand_music_artist_name_length.sql";
    private static final String ARTIST = "Jordi Savall, Monica Huggett, Chiara Bianchini, Ton Koopman, "
            + "Hopkinson Smith, Stephen Preston, Michel Henry, Claude Wassmer & Ku Ebbinge";
    private static final String TITLE = "Les Nations, Premier ordre \"La Françoise\": V. Sarabande";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("music_metadata_test").withUsername("test").withPassword("test");
    @TempDir
    private static Path migrations;
    private static JdbcTemplate jdbc;
    private MusicRecordRepository records;
    private ItunesMusicSearchClient itunes;
    private MockMvc mvc;

    @BeforeAll
    static void migrateFromOriginalSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        copyMigration("V1__recommendation_sample.sql");
        flyway().migrate();
        jdbc.update("INSERT INTO music (provider,external_music_id,title,artist_name) VALUES ('ITUNES','1','곡','가수')");
        assertThat(ARTIST.length()).isGreaterThan(100);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO music (provider,external_music_id,title,artist_name) VALUES ('ITUNES','2',?,?)",
                TITLE, ARTIST)).hasMessageContaining("Data too long for column 'artist_name'");
        copyMigration(MIGRATION);
        flyway().migrate();
        assertThat(jdbc.queryForObject("SELECT artist_name FROM music WHERE external_music_id='1'", String.class))
                .isEqualTo("가수");
        for (String file : List.of("V20260921200528__create_users_table.sql",
                "V20260923142421__create_chat_room_foundation.sql",
                "V20260925174748__create_map_dots_and_music_records_tables.sql")) {
            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + file));
            }
        }
        jdbc.update(
                "INSERT INTO users (id,email,password_hash,nickname,role) "
                        + "VALUES (1,'test@test.local','test','테스트','USER')");
        jdbc.update("INSERT INTO regions (id,code,name,level) VALUES (1,'11','서울특별시','SIDO')");
        jdbc.update("INSERT INTO regions (id,parent_id,code,name,level) VALUES (2,1,'11440','마포구','SIGUNGU')");
        jdbc.update("INSERT INTO map_dots (id,code,region_id,latitude,longitude) VALUES (1,'dot',2,37.5,127.0)");
    }

    private static Flyway flyway() {
        return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + migrations).load();
    }

    private static void copyMigration(String file) throws Exception {
        try (var input = new ClassPathResource("db/migration/" + file).getInputStream()) {
            Files.copy(input, migrations.resolve(file));
        }
    }

    @BeforeEach
    void setUp() {
        records = new MusicRecordRepository(jdbc);
        itunes = mock(ItunesMusicSearchClient.class);
        var tokens = mock(LocationResolutionTokenProvider.class);
        when(tokens.validate("location-token", 1L)).thenReturn(new LocationResolutionClaims(
                1L, 1L, "11", 2L, "11440", 1L, Instant.now().plusSeconds(300)));
        var service = new MusicRecordService(records, itunes, tokens, new MusicSearchCursorCodec("test-secret"));
        var users = mock(CurrentUserResolver.class);
        when(users.resolve(any())).thenReturn(1L);
        mvc = MockMvcBuilders.standaloneSetup(new MusicRecordController(service, users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void savesAndReadsReportedTrackWithoutTruncationThroughRecordApi() throws Exception {
        when(itunes.lookup("123")).thenReturn(Optional.of(item("123", ARTIST)));
        var response = mvc.perform(post("/api/v1/music-records").contentType(MediaType.APPLICATION_JSON)
                .content(request("123"))).andExpect(status().isCreated()).andReturn();
        long recordId = new ObjectMapper().readTree(response.getResponse().getContentAsString())
                .path("data").path("record_id").asLong();
        mvc.perform(get("/api/v1/music-records/" + recordId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.music.artist_name").value(ARTIST));
        assertThat(records.findMusic("ITUNES", "123").orElseThrow().artist_name()).isEqualTo(ARTIST);
    }

    @Test
    void recommendationInsertAndUpdateKeepArtistAtNewUnicodeBoundary() {
        var recommendations = new RecommendationRepository(jdbc);
        long musicId = recommendations.saveMusic(new TrackData("ITUNES", "456", TITLE, ARTIST, null, null));
        String boundary = "🎵".repeat(1000);
        long updatedId = recommendations.saveMusic(new TrackData("ITUNES", "456", TITLE, boundary, null, null));
        assertThat(updatedId).isEqualTo(musicId);
        var request = new RecommendationRequest("TEXT", "CHATBOT", UUID.randomUUID().toString(), "산책");
        var response = recommendations.saveCompleted(request, "guest", null,
                List.of(new TrackData("ITUNES", "456", TITLE, boundary, null, null)));
        assertThat(response.items().getFirst().music().artist_name()).isEqualTo(boundary);
        assertThat(records.findMusic("ITUNES", "456").orElseThrow().artist_name()).isEqualTo(boundary);
        records.upsertMusic(item("457", boundary));
        assertThat(records.findMusic("ITUNES", "457").orElseThrow().artist_name()).isEqualTo(boundary);
    }

    @Test
    void invalidProviderMetadataReturnsControlled502WithoutSavingMusicOrRecord() throws Exception {
        when(itunes.lookup("789")).thenReturn(Optional.of(item("789", "가".repeat(1001))));
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM music_records", Integer.class);
        mvc.perform(post("/api/v1/music-records").contentType(MediaType.APPLICATION_JSON)
                .content(request("789"))).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("music metadata invalid"))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(records.findMusic("ITUNES", "789")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_records", Integer.class)).isEqualTo(count);
    }

    private MusicItem item(String id, String artist) {
        return new MusicItem(null, "ITUNES", id, TITLE, artist, null, null, null, false);
    }

    private String request(String id) {
        return "{\"music\":{\"provider\":\"ITUNES\",\"external_music_id\":\"" + id
                + "\"},\"location_resolution_token\":\"location-token\"}";
    }
}
