package com.muse.meomuneum.location.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.location.dto.LocationResolveRequest;
import com.muse.meomuneum.location.dto.LocationResolveResponse;
import com.muse.meomuneum.location.service.LocationResolutionService;

@RestController
@RequestMapping("/api/v1/locations")
public class LocationController {

    private final LocationResolutionService locationResolutionService;

    public LocationController(LocationResolutionService locationResolutionService) {
        this.locationResolutionService = locationResolutionService;
    }

    @PostMapping("/resolve")
    public ApiResponse<LocationResolveResponse> resolve(
            @RequestBody LocationResolveRequest request,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ApiResponse.of("location resolved", locationResolutionService.resolve(userId, request));
    }
}
