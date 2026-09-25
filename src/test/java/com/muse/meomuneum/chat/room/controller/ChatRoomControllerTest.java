package com.muse.meomuneum.chat.room.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.chat.room.dto.ChatRoomMembershipResponse;
import com.muse.meomuneum.chat.room.dto.ChatRoomResponse;
import com.muse.meomuneum.chat.room.exception.ChatRoomExceptionHandler;
import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;
import com.muse.meomuneum.chat.room.service.ChatRoomJoinResult;

class ChatRoomControllerTest {

    private ChatRoomEntryService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ChatRoomEntryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatRoomController(service))
                .setControllerAdvice(new ChatRoomExceptionHandler()).build();
    }

    @Test
    void returnsThePreProvisionedRoomForARegion() throws Exception {
        when(service.findByRegion(25L)).thenReturn(new ChatRoomResponse(700L, 25L, "성남시 분당구", 25, "ACTIVE"));

        mockMvc.perform(get("/api/v1/regions/25/chat-room")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("chat room retrieved"))
                .andExpect(jsonPath("$.data.room_id").value(700L)).andExpect(jsonPath("$.data.region_id").value(25L))
                .andExpect(jsonPath("$.data.region_name").value("성남시 분당구"));
    }

    @Test
    void returnsCreatedForTheFirstJoin() throws Exception {
        ChatRoomMembershipResponse membership = membership();
        when(service.join(7L, 700L, "loc_token")).thenReturn(new ChatRoomJoinResult(membership, true));

        mockMvc.perform(post("/api/v1/chat-rooms/700/members")
                .principal(new UsernamePasswordAuthenticationToken(7L, null)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"location_resolution_token\":\"loc_token\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("chat room joined"))
                .andExpect(jsonPath("$.data.membership_id").value(900L))
                .andExpect(jsonPath("$.data.room_id").value(700L)).andExpect(jsonPath("$.data.region_id").value(25L));
    }

    @Test
    void returnsOkAndTheExistingMembershipForAnIdempotentRetry() throws Exception {
        ChatRoomMembershipResponse membership = membership();
        when(service.join(7L, 700L, "loc_token")).thenReturn(new ChatRoomJoinResult(membership, false));

        mockMvc.perform(post("/api/v1/chat-rooms/700/members")
                .principal(new UsernamePasswordAuthenticationToken(7L, null)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"location_resolution_token\":\"loc_token\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("already joined"))
                .andExpect(jsonPath("$.data.membership_id").value(900L));
    }

    private ChatRoomMembershipResponse membership() {
        return new ChatRoomMembershipResponse(900L, 700L, 25L, OffsetDateTime.parse("2026-09-25T00:00:00Z"));
    }
}
