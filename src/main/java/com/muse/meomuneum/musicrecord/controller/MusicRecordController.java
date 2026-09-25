package com.muse.meomuneum.musicrecord.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.CreateRequest;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.CreateResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordDetailResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordListResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicSearchResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.UpdateResponse;
import com.muse.meomuneum.musicrecord.resolver.CurrentUserResolver;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;

@RestController
@RequestMapping("/api/v1")
public class MusicRecordController {
    private final MusicRecordService service;
    private final CurrentUserResolver users;

    public MusicRecordController(MusicRecordService service, CurrentUserResolver users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping("/music/search")
    public ApiResponse<MusicSearchResponse> search(@RequestParam(required = false) String query,
            @RequestParam(defaultValue = "ITUNES") String provider,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return ApiResponse.of("music search completed", service.search(query, provider, cursor, size));
    }

    @PostMapping("/music-records")
    public ResponseEntity<ApiResponse<CreateResponse>> create(@Valid @RequestBody CreateRequest body,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of("music record created",
                service.create(users.resolve(authentication), body)));
    }

    @GetMapping("/users/me/music-records")
    public ApiResponse<MusicRecordListResponse> list(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size, Authentication authentication) {
        return ApiResponse.of("my music records retrieved",
                service.list(users.resolve(authentication), cursor, size));
    }
    @GetMapping("/music-records/{recordId}")
    public ApiResponse<MusicRecordDetailResponse> detail(@PathVariable long recordId, Authentication authentication) {
        return ApiResponse.of("music record retrieved", service.detail(users.resolve(authentication), recordId));
    }
    @PatchMapping("/music-records/{recordId}")
    public ApiResponse<UpdateResponse> update(@PathVariable long recordId, @RequestBody JsonNode body,
                                              Authentication authentication) {
        return ApiResponse.of("music record updated", service.update(users.resolve(authentication), recordId, body));
    }
}
