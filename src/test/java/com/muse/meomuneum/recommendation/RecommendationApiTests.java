package com.muse.meomuneum.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.provider.RecommendationProvider;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;
import com.muse.meomuneum.recommendation.service.RecommendationService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@SpringBootTest(properties = "recommendation.allow-guests=true")
@ActiveProfiles("test")
@Transactional // 각 테스트의 변경은 종료 시 자동 롤백됩니다.
class RecommendationApiTests {
    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    RecommendationProvider provider;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(provider.recommend(anyList()))
                .thenReturn(java.util.List.of(new TrackData("ITUNES", "1", "song 1", "artist", null, null),
                        new TrackData("ITUNES", "2", "song 2", "artist", null, null),
                        new TrackData("ITUNES", "3", "song 3", "artist", null, null),
                        new TrackData("ITUNES", "4", "song 4", "artist", null, null),
                        new TrackData("ITUNES", "5", "song 5", "artist", null, null)));
    }

    private String body(String prompt) {
        return """
                {"input_type":"TEXT","trigger_type":"CHATBOT",
                 "conversation_key":"550e8400-e29b-41d4-a716-446655440000","prompt":"%s"}
                """.formatted(prompt);
    }

    @Test
    void savesFiveSongsAndPromptAndGuestCanRetrieve() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/recommendations").session(session).contentType("application/json")
                .content(body("비 오는 밤"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        long id = findRecommendationId(session);
        mvc.perform(get("/api/v1/recommendations/" + id).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rank_no").value(1))
                .andExpect(jsonPath("$.data.items[4].rank_no").value(5));
        assertEquals("비 오는 밤",
                jdbc.queryForObject("SELECT prompt FROM recommendation_sessions WHERE id = ?", String.class, id));
        assertNull(jdbc.queryForObject("SELECT user_id FROM recommendation_sessions WHERE id = ?", Long.class, id));
        assertNull(jdbc.queryForObject("SELECT region_id FROM recommendation_sessions WHERE id = ?", Long.class, id));
    }

    @Test
    void returnsPartialResultsAndPassesPreviousPromptsToProvider() throws Exception {
        when(provider.recommend(anyList()))
                .thenReturn(java.util.List.of(new TrackData("ITUNES", "partial-1", "song", "artist", null, null)));
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/recommendations").session(session).contentType("application/json")
                .content(body("비 오는 밤"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(post("/api/v1/recommendations").session(session).contentType("application/json")
                .content(body("드라이브").replace("TEXT", "VOICE"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        assertEquals("VOICE", jdbc.queryForObject("SELECT input_type FROM recommendation_sessions WHERE id = ?",
                String.class, findRecommendationId(session)));
        var prompts = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(provider, times(2)).recommend(prompts.capture());
        assertEquals(java.util.List.of("비 오는 밤", "드라이브"), prompts.getAllValues().get(1));
    }

    @Test
    void limitsPreviousPromptsBeforeBuildingProviderContext() throws Exception {
        var session = new MockHttpSession();
        String conversationKey = "550e8400-e29b-41d4-a716-446655440000";
        for (int i = 0; i < 12; i++) {
            jdbc.update("""
                    INSERT INTO recommendation_sessions
                    (user_id, guest_session_id, trigger_type, input_type, conversation_key, prompt, status)
                    VALUES (NULL, ?, 'CHATBOT', 'TEXT', ?, ?, 'COMPLETED')
                    """, session.getId(), conversationKey, "history-" + i);
        }
        clearInvocations(provider);

        mvc.perform(post("/api/v1/recommendations").session(session).contentType("application/json")
                .content(body("current"))).andExpect(status().isCreated());

        var prompts = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(provider).recommend(prompts.capture());
        assertEquals(java.util.List.of("history-2", "history-3", "history-4", "history-5", "history-6", "history-7",
                "history-8", "history-9", "history-10", "history-11", "current"), prompts.getValue());
        assertTrue(String.join(" ", prompts.getValue()).length() <= 250);
    }

    @Test
    void skipsPreviousPromptsWhenCurrentPromptFillsContext() throws Exception {
        var session = new MockHttpSession();
        jdbc.update("""
                INSERT INTO recommendation_sessions
                (user_id, guest_session_id, trigger_type, input_type, conversation_key, prompt, status)
                VALUES (NULL, ?, 'CHATBOT', 'TEXT', ?, 'previous', 'COMPLETED')
                """, session.getId(), "550e8400-e29b-41d4-a716-446655440000");
        clearInvocations(provider);

        mvc.perform(post("/api/v1/recommendations").session(session).contentType("application/json")
                .content(body("a".repeat(300)))).andExpect(status().isCreated());

        var prompts = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(provider).recommend(prompts.capture());
        assertEquals(java.util.List.of("a".repeat(250)), prompts.getValue());
    }

    @Test
    void emptySearchReturns503WithoutSavingSession() throws Exception {
        when(provider.recommend(anyList())).thenReturn(java.util.List.of());
        int before = count("recommendation_sessions");
        mvc.perform(post("/api/v1/recommendations").contentType("application/json").content(body("없는 음악")))
                .andExpect(status().isServiceUnavailable());
        assertEquals(before, count("recommendation_sessions"));
    }

    @Test
    void rejectsBlankLongAndUnsupportedInputBeforeSaving() throws Exception {
        int before = count("recommendation_sessions");
        for (String prompt : new String[]{"   ", "a".repeat(1001)}) {
            mvc.perform(post("/api/v1/recommendations").contentType("application/json").content(body(prompt)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/recommendations").contentType("application/json")
                .content(body("노래").replace("TEXT", "IMAGE"))).andExpect(status().isBadRequest());
        assertEquals(before, count("recommendation_sessions"));
    }

    @Test
    void cannotReadAnotherGuestsResult() throws Exception {
        var owner = new MockHttpSession();
        mvc.perform(post("/api/v1/recommendations").session(owner).contentType("application/json").content(body("노래")))
                .andExpect(status().isCreated());
        long id = findRecommendationId(owner);
        mvc.perform(get("/api/v1/recommendations/" + id).session(new MockHttpSession()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/recommendations/" + id)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/recommendations/999999").session(owner)).andExpect(status().isNotFound());
    }

    @Test
    void reusesMusicButSavesEveryRecommendation() throws Exception {
        int beforeSessions = count("recommendation_sessions");
        int beforeItems = count("recommendation_items");
        int musicAfterFirst = 0;
        var session = new MockHttpSession();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/recommendations").session(session).contentType("application/json")
                    .content(body("신나는 노래"))).andExpect(status().isCreated());
            if (i == 0) {
                musicAfterFirst = count("music");
            }
        }
        assertEquals(musicAfterFirst, count("music"));
        assertEquals(beforeSessions + 2, count("recommendation_sessions"));
        assertEquals(beforeItems + 10, count("recommendation_items"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void transactionRollsBackWhenOneTrackCannotBeStored() {
        int beforeSessions = count("recommendation_sessions");
        int beforeMusic = count("music");
        String uniqueId = java.util.UUID.randomUUID().toString();
        var good = new TrackData("ITUNES", uniqueId, "test", "test", null, null);
        var bad = new TrackData("ITUNES", uniqueId + "-bad", null, "test", null, null);
        RecommendationProvider broken = prompts -> java.util.List.of(good, bad,
                new TrackData("ITUNES", "3", "test", "test", null, null),
                new TrackData("ITUNES", "4", "test", "test", null, null),
                new TrackData("ITUNES", "5", "test", "test", null, null));
        var repository = context.getBean(RecommendationRepository.class);
        var template = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        assertThrows(RuntimeException.class, () -> template
                .execute(status -> new RecommendationService(broken, repository, new SimpleMeterRegistry()).create(
                        new RecommendationRequest("TEXT", "CHATBOT", "550e8400-e29b-41d4-a716-446655440000", "test"),
                        "guest", null)));
        assertEquals(beforeSessions, count("recommendation_sessions"));
        assertEquals(beforeMusic, count("music"));
    }

    private long findRecommendationId(MockHttpSession session) {
        Long id = jdbc.queryForObject("""
                SELECT id FROM recommendation_sessions
                WHERE guest_session_id = ? ORDER BY id DESC LIMIT 1
                """, Long.class, session.getId());
        assertNotNull(id, "현재 게스트 세션의 추천 결과가 저장되어야 합니다.");
        return id;
    }

    private int count(String table) {
        // 테이블명은 SQL 파라미터로 바인딩할 수 없으므로 허용된 쿼리만 선택합니다.
        String sql = switch (table) {
            case "recommendation_sessions" -> "SELECT COUNT(*) FROM recommendation_sessions";
            case "recommendation_items" -> "SELECT COUNT(*) FROM recommendation_items";
            case "music" -> "SELECT COUNT(*) FROM music";
            default -> throw new IllegalArgumentException("지원하지 않는 테스트 테이블입니다.");
        };
        Integer result = jdbc.queryForObject(sql, Integer.class);
        assertNotNull(result, "COUNT 쿼리는 결과를 반환해야 합니다.");
        return result;
    }
}
