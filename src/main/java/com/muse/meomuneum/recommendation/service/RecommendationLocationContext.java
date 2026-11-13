package com.muse.meomuneum.recommendation.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.location.exception.LocationException;
import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.recommendation.exception.RecommendationException;

/** A region is provenance at admission, never inferred later from the user's current position. */
@Component
public class RecommendationLocationContext {
    private final LocationResolutionTokenProvider tokens;
    private final JdbcTemplate jdbc;

    public RecommendationLocationContext(LocationResolutionTokenProvider tokens, JdbcTemplate jdbc) {
        this.tokens = tokens;
        this.jdbc = jdbc;
    }

    public LocationResolutionClaims resolve(String token, Long userId, String sid) {
        if (userId == null) {
            throw invalid();
        }
        LocationResolutionClaims claims;
        try {
            claims = tokens.validateForChat(token, userId, sid);
        } catch (LocationException exception) {
            throw invalid();
        }
        // A map dot can span multiple regions; its representative region is not the user's region.
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM regions g JOIN regions p ON p.id=g.parent_id
                WHERE g.id=? AND g.code=? AND p.id=? AND p.code=? AND g.level='SIGUNGU' AND p.level='SIDO'
                AND g.is_active=TRUE AND p.is_active=TRUE
                AND (? IS NULL OR EXISTS(SELECT 1 FROM map_dots d WHERE d.id=?
                    AND d.is_active=TRUE)))
                """, Boolean.class, claims.sigunguRegionId(), claims.sigunguCode(), claims.sidoRegionId(),
                claims.sidoCode(), claims.mapDotId(), claims.mapDotId()));
        if (!active) {
            throw invalid();
        }
        return claims;
    }

    private RecommendationException invalid() {
        return new RecommendationException(400, "invalid location resolution token");
    }
}
