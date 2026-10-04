package com.muse.meomuneum.user.signup.controller;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.user.signup.dto.UserAvailabilityResponse;
import com.muse.meomuneum.user.signup.service.AvailabilityRateLimiter;
import com.muse.meomuneum.user.signup.service.UserAvailabilityService;

@RestController
@RequestMapping("/api/v1/users/availability")
public class UserAvailabilityController {
    private final UserAvailabilityService userAvailabilityService;
    private final AvailabilityRateLimiter availabilityRateLimiter;

    public UserAvailabilityController(UserAvailabilityService userAvailabilityService,
            AvailabilityRateLimiter availabilityRateLimiter) {
        this.userAvailabilityService = userAvailabilityService;
        this.availabilityRateLimiter = availabilityRateLimiter;
    }

    @GetMapping("/{field}")
    public ResponseEntity<ApiResponse<UserAvailabilityResponse>> checkAvailability(
            @PathVariable String field,
            @RequestParam(required = false) String value,
            HttpServletRequest request) {
        if (!availabilityRateLimiter.isAllowed(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .cacheControl(CacheControl.noStore())
                    .body(ApiResponse.of("too many requests", null));
        }

        boolean available = userAvailabilityService.isAvailable(field, value);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of("availability checked", new UserAvailabilityResponse(available)));
    }
}
