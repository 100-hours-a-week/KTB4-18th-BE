package com.muse.meomuneum.musicrecord.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.Request;
import com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.Response;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.resolver.CurrentUserResolver;
import com.muse.meomuneum.musicrecord.service.SongRegistrationService;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/songs")
public class SongController {
    private final SongRegistrationService service;
    private final CurrentUserResolver users;

    public SongController(SongRegistrationService service, CurrentUserResolver users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Response>> register(@RequestBody JsonNode body, Authentication authentication) {
        users.resolve(authentication);
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("externalTrackId")
                || !body.get("externalTrackId").isTextual()) {
            throw new MusicRecordException("song_registration_invalid_body");
        }
        Response response = service.register(new Request(body.get("externalTrackId").asText()));
        return ResponseEntity.status(response.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.of("song registered", response));
    }
}
