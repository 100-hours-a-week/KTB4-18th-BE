package com.muse.meomuneum.map.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.muse.meomuneum.map.repository.MapDotRepository.LatestMapDotRecord;

class MapDotRepositoryTests {
    @Test
    void ranksOnlyVisibleActiveRecordsByTimestampAndId() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        LatestMapDotRecord latest = new LatestMapDotRecord(7, "https://cdn.example.com/cover.jpg",
                Instant.parse("2026-09-03T11:00:00Z"));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<LatestMapDotRecord>>any()))
                .thenReturn(List.of(latest));

        List<LatestMapDotRecord> records = new MapDotRepository(jdbc).findLatestPublicRecords();

        assertEquals(List.of(latest), records);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<LatestMapDotRecord>>any());
        assertTrue(sql.getValue().contains("mr.deleted_at IS NULL"));
        assertTrue(sql.getValue().contains("u.deleted_at IS NULL"));
        assertTrue(sql.getValue().contains("us.map_visibility = 'PUBLIC'"));
        assertTrue(sql.getValue().contains("PARTITION BY mr.map_dot_id"));
        assertTrue(sql.getValue().contains("ORDER BY mr.created_at DESC, mr.id DESC"));
        assertTrue(sql.getValue().contains("WHERE record_rank = 1"));
    }
}
