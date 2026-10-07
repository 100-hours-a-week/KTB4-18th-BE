package com.muse.meomuneum.user.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.user.account.exception.UserAccountException;
import com.muse.meomuneum.user.account.exception.UserAccountExceptionHandler;
import com.muse.meomuneum.user.account.service.ProfileImageService;
import com.muse.meomuneum.user.account.service.UserAccountService;
import com.muse.meomuneum.user.account.service.UserTermsAgreementService;

class UserAccountControllerTest {

    private final ProfileImageService images = mock(ProfileImageService.class);
    private final UsernamePasswordAuthenticationToken principal = new UsernamePasswordAuthenticationToken(1L, null,
            java.util.List.of());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UserAccountController(mock(UserAccountService.class),
                new CurrentUserResolver(), mock(UserTermsAgreementService.class), images))
                .setControllerAdvice(new UserAccountExceptionHandler()).build();
    }

    @Test
    void handlesMultipartOverflowBeforeControllerSelectionIncludingContextPath() throws Exception {
        var resolver = new UserAccountExceptionHandler().profileImageUploadSizeResolver(
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(ObjectMapper.class));
        assertThat(((Ordered) resolver).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/context/api/v1/users/me/profile-image");
        request.setContextPath("/context");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var result = resolver.resolveException(request, response, null, new MaxUploadSizeExceededException(10));
        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(new ObjectMapper().readTree(response.getContentAsByteArray()).get("message").asText())
                .isEqualTo("image is too large");
        assertThat(new ObjectMapper().readTree(response.getContentAsByteArray()).get("data").isNull()).isTrue();
    }

    @Test
    void fallsThroughWithoutChangingResponseForOtherRoutesMethodsAndExceptionTypes() {
        var resolver = new UserAccountExceptionHandler().profileImageUploadSizeResolver(
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(ObjectMapper.class));
        for (String[] route : new String[][]{{"PUT", "/api/v1/speech-transcriptions"},
                {"POST", "/api/v1/users/me/profile-image"}, {"PUT", "/api/v1/users/me/profile-image/"}}) {
            MockHttpServletRequest request = new MockHttpServletRequest(route[0], route[1]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(resolver.resolveException(request, response, null, new MaxUploadSizeExceededException(10)))
                    .isNull();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).isNull();
            assertThat(response.getContentAsByteArray()).isEmpty();
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(resolver.resolveException(new MockHttpServletRequest("PUT", "/api/v1/users/me/profile-image"),
                response, null, new IllegalStateException("unrelated"))).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void uploadsImagePartAndReturnsSavedUrl() throws Exception {
        when(images.upload(eq(1L), any())).thenReturn("/api/v1/users/me/profile-image/current.png");
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/api/v1/users/me/profile-image")
                .file(new MockMultipartFile("image", new byte[]{1, 2})).principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile_image_url").value("/api/v1/users/me/profile-image/current.png"));
    }

    @Test
    void readsBinaryPngWithPrivateHeaders() throws Exception {
        when(images.read(1L, "current")).thenReturn(new byte[]{1, 2});
        mvc.perform(get("/api/v1/users/me/profile-image/current.png").principal(principal))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1, 2}))
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void missingImagePartReturnsControlled400() throws Exception {
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/api/v1/users/me/profile-image")
                .principal(principal))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("invalid request"));
    }

    @Test
    void resolverOverflowReturnsControlled413() throws Exception {
        when(images.upload(eq(1L), any())).thenThrow(new MaxUploadSizeExceededException(5 * 1024 * 1024));
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/api/v1/users/me/profile-image")
                .file(new MockMultipartFile("image", new byte[]{1})).principal(principal))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.message").value("image is too large"));
    }

    @Test
    void imageValidationAndStorageFailuresUseControlledEnvelopes() throws Exception {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.BAD_REQUEST, HttpStatus.CONTENT_TOO_LARGE,
                HttpStatus.INTERNAL_SERVER_ERROR}) {
            when(images.upload(eq(1L), any())).thenThrow(new UserAccountException("IMAGE", status, "image failed"));
            mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/api/v1/users/me/profile-image")
                    .file(new MockMultipartFile("image", new byte[]{1})).principal(principal))
                    .andExpect(status().is(status.value())).andExpect(jsonPath("$.message").value("image failed"));
        }
        when(images.read(1L, "stale")).thenThrow(new UserAccountException("IMAGE", HttpStatus.NOT_FOUND,
                "image not found"));
        mvc.perform(get("/api/v1/users/me/profile-image/stale.png").principal(principal))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("image not found"));
    }
}
