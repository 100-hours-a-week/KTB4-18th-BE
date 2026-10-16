package com.muse.meomuneum.recommendation.controller;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.muse.meomuneum.recommendation.dto.request.ImageRecommendationRequest;
import com.muse.meomuneum.recommendation.dto.response.RecommendationAcceptedResponse;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.recommendation.service.ImageRecommendationService;

@RestController
@RequestMapping("/api/v1/recommendations/images")
public class ImageRecommendationController {
    private final ImageRecommendationService service;
    private final CurrentUserResolver users;

    public ImageRecommendationController(ImageRecommendationService service, CurrentUserResolver users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RecommendationController.ApiResponse<RecommendationAcceptedResponse>> create(
            @Valid @ModelAttribute ImageRecommendationRequest request,
            @RequestPart("image") MultipartFile image, HttpServletRequest servletRequest,
            MultipartHttpServletRequest multipartRequest, Authentication authentication) {
        boolean unexpectedParameter = servletRequest.getParameterMap().keySet().stream()
                .anyMatch(name -> !Set.of("conversation_key", "input_type", "trigger_type").contains(name));
        var fileParts = multipartRequest.getMultiFileMap();
        boolean unexpectedFilePart = fileParts.size() != 1 || !fileParts.containsKey("image")
                || fileParts.get("image").size() != 1;
        if (unexpectedParameter || unexpectedFilePart) {
            throw new com.muse.meomuneum.recommendation.exception.RecommendationException(400,
                    "invalid image recommendation request");
        }
        var accepted = service.accept(request, image, users.resolve(authentication));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new RecommendationController.ApiResponse<>("recommendation processing", accepted));
    }
}
