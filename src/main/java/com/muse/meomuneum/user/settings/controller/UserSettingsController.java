package com.muse.meomuneum.user.settings.controller;

import java.time.LocalDateTime;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.user.settings.domain.MapVisibility;
import com.muse.meomuneum.user.settings.domain.UserSettings;
import com.muse.meomuneum.user.settings.service.UserSettingsService;

@RestController
@RequestMapping("/api/v1/users/me/settings")
public class UserSettingsController {

    private final UserSettingsService service;
    private final CurrentUserResolver currentUserResolver;

    public UserSettingsController(UserSettingsService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping
    public ApiResponse<SettingsResponse> get(Authentication authentication) {
        return new ApiResponse<>("settings retrieved", SettingsResponse.from(service.get(userId(authentication))));
    }

    @PatchMapping
    public ApiResponse<UpdatedSettingsResponse> update(@RequestBody UpdateSettingsRequest request,
            Authentication authentication) {
        UserSettings settings = service.update(userId(authentication), request.map_visibility(),
                request.is_unrecorded_dot_recommendation_enabled());
        return new ApiResponse<>("settings updated", UpdatedSettingsResponse.from(settings));
    }

    private Long userId(Authentication authentication) {
        return currentUserResolver.resolve(authentication);
    }

    public record UpdateSettingsRequest(MapVisibility map_visibility,
            Boolean is_unrecorded_dot_recommendation_enabled) {
    }

    public record SettingsResponse(MapVisibility map_visibility, boolean is_unrecorded_dot_recommendation_enabled) {
        private static SettingsResponse from(UserSettings settings) {
            return new SettingsResponse(settings.getMapVisibility(), settings.isUnrecordedDotRecommendationEnabled());
        }
    }

    public record UpdatedSettingsResponse(MapVisibility map_visibility,
            boolean is_unrecorded_dot_recommendation_enabled, LocalDateTime updated_at) {
        private static UpdatedSettingsResponse from(UserSettings settings) {
            return new UpdatedSettingsResponse(settings.getMapVisibility(),
                    settings.isUnrecordedDotRecommendationEnabled(), settings.getUpdatedAt());
        }
    }
}
