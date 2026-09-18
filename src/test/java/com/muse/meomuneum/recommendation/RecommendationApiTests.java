package com.muse.meomuneum.recommendation;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.provider.RecommendationProvider;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;
import com.muse.meomuneum.recommendation.service.RecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "recommendation.allow-guests=true")
@ActiveProfiles("test")
@Transactional // 각 테스트의 변경은 종료 시 자동 롤백됩니다.
class RecommendationApiTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String body(String prompt) {
        return """
                {"input_type":"TEXT","trigger_type":"CHATBOT",
                 "conversation_key":"550e8400-e29b-41d4-a716-446655440000","prompt":"%s"}
                """.formatted(prompt);
    }

    @Test void savesFiveSongsWithoutSavingPromptAndGuestCanRetrieve() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/recommendations").session(session).contentType("application/json").content(body("비 오는 밤")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[0].music.preview_url").isNotEmpty());
        long id = jdbc.queryForObject("SELECT MAX(id) FROM recommendation_sessions", Long.class);
        mvc.perform(get("/api/v1/recommendations/" + id).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].rank_no").value(1))
                .andExpect(jsonPath("$.data.items[4].rank_no").value(5));
        assertNull(jdbc.queryForObject("SELECT prompt FROM recommendation_sessions WHERE id = " + id + "", String.class));
        assertNull(jdbc.queryForObject("SELECT user_id FROM recommendation_sessions WHERE id = " + id + "", Long.class));
        assertNull(jdbc.queryForObject("SELECT region_id FROM recommendation_sessions WHERE id = " + id + "", Long.class));
    }

    @Test void rejectsBlankLongAndUnsupportedInputBeforeSaving() throws Exception {
        int before = count("recommendation_sessions");
        for (String prompt : new String[]{"   ", "a".repeat(1001)}) {
            mvc.perform(post("/api/v1/recommendations").contentType("application/json").content(body(prompt)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/recommendations").contentType("application/json")
                .content(body("노래").replace("TEXT", "IMAGE"))).andExpect(status().isBadRequest());
        assertEquals(before, count("recommendation_sessions"));
    }

    @Test void cannotReadAnotherGuestsResult() throws Exception {
        var owner = new MockHttpSession();
        mvc.perform(post("/api/v1/recommendations").session(owner).contentType("application/json").content(body("노래")))
                .andExpect(status().isAccepted());
        long id = jdbc.queryForObject("SELECT MAX(id) FROM recommendation_sessions", Long.class);
        mvc.perform(get("/api/v1/recommendations/" + id).session(new MockHttpSession())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/recommendations/" + id)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/recommendations/999999").session(owner)).andExpect(status().isNotFound());
    }

    @Test void reusesMusicButSavesEveryRecommendation() throws Exception {
        int beforeSessions = count("recommendation_sessions");
        int beforeItems = count("recommendation_items");
        int musicAfterFirst = 0;
        var session = new MockHttpSession();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/recommendations").session(session).contentType("application/json").content(body("신나는 노래")))
                    .andExpect(status().isAccepted());
            if (i == 0) musicAfterFirst = count("music");
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
        RecommendationProvider broken = prompt -> java.util.List.of(good, bad,
                new TrackData("ITUNES", "3", "test", "test", null, null),
                new TrackData("ITUNES", "4", "test", "test", null, null),
                new TrackData("ITUNES", "5", "test", "test", null, null));
        var repository = context.getBean(RecommendationRepository.class);
        var template = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        assertThrows(RuntimeException.class, () -> template.execute(status -> new RecommendationService(broken, repository)
                .create(new RecommendationRequest("TEXT", "CHATBOT", "550e8400-e29b-41d4-a716-446655440000", "test"), "guest", null)));
        assertEquals(beforeSessions, count("recommendation_sessions"));
        assertEquals(beforeMusic, count("music"));
    }

    // 테이블 이름은 이 테스트 안의 고정된 값만 전달합니다.
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
