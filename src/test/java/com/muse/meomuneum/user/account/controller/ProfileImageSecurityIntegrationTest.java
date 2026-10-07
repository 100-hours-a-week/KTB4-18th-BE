package com.muse.meomuneum.user.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.user.account.service.ProfileImageService;
import com.muse.meomuneum.user.account.service.ProfileImageStorage;
import com.muse.meomuneum.user.account.service.UserAccountService;
import com.muse.meomuneum.user.account.service.UserTermsAgreementService;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserGender;
import com.muse.meomuneum.user.repository.UserRepository;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@WebMvcTest(value = UserAccountController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes",
        "auth.cors.allowed-origins=http://localhost:5173"})
@Import({SecurityConfig.class, ProfileImageService.class, ProfileImageSecurityIntegrationTest.Configuration.class})
class ProfileImageSecurityIntegrationTest {

    @TempDir
    static Path root;

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository repository;

    @Autowired
    ProfileImageStorage storage;

    @Autowired
    ApplicationContext context;

    private User user;
    private MockMultipartFile image;

    @BeforeEach
    void setUp() throws Exception {
        org.mockito.Mockito.reset(repository);
        user = org.springframework.beans.BeanUtils.instantiateClass(User.class.getDeclaredConstructor());
        ReflectionTestUtils.setField(user, "id", 1L);
        user.updateProfile("기존닉", (short) 1999, UserGender.FEMALE, "https://example.com/legacy.png",
                LocalDateTime.now());
        when(repository.findActiveByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(user));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "PNG", bytes);
        image = new MockMultipartFile("image", "untrusted.png", "image/png", bytes.toByteArray());
    }

    @Test
    void registersSizeResolverAheadOfMvcFallbackForPreHandlerFailure() throws Exception {
        List<HandlerExceptionResolver> resolvers = new java.util.ArrayList<>(
                context.getBeansOfType(HandlerExceptionResolver.class).values());
        AnnotationAwareOrderComparator.sort(resolvers);
        assertThat(resolvers.getFirst()).isSameAs(context.getBean("profileImageUploadSizeResolver"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(resolvers.getFirst().resolveException(
                new MockHttpServletRequest("PUT", "/api/v1/users/me/profile-image"), response, null,
                new MaxUploadSizeExceededException(10 * 1024 * 1024))).isNotNull();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(new ObjectMapper().readTree(response.getContentAsByteArray()).get("message").asText())
                .isEqualTo("10MB 이하의 이미지만 등록할 수 있어요.");
        assertThat(resolvers.getFirst().resolveException(new MockHttpServletRequest("PUT", "/other-upload"),
                new MockHttpServletResponse(), null, new MaxUploadSizeExceededException(10))).isNull();
    }

    @Test
    void sharedSecurityRejectsAnonymousUploadAndRead() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/users/me/profile-image").file(image)
                .header("Origin", "http://localhost:5173")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me/profile-image/00000000-0000-0000-0000-000000000000.png"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedOwnerUploadsFetchesAndReplacesImagePreservingProfile() throws Exception {
        upload();
        String prior = user.getProfileImageUrl();
        mvc.perform(get(prior).with(owner())).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
        upload();
        String current = user.getProfileImageUrl();
        assertThat(current).isNotEqualTo(prior);
        assertThat(user.getNickname()).isEqualTo("기존닉");
        assertThat(user.getBirthYear()).isEqualTo((short) 1999);
        assertThat(user.getGender()).isEqualTo(UserGender.FEMALE);
        assertThat(Files.exists(root.resolve("1").resolve(prior.substring(prior.lastIndexOf('/') + 1)))).isFalse();
        mvc.perform(get(prior).with(owner())).andExpect(status().isNotFound());
        mvc.perform(get(current).with(owner())).andExpect(status().isOk());
    }

    @Test
    void patchAssignedForeignUuidDoesNotGrantReadOrDeleteForeignImage() throws Exception {
        String foreign = storage.store(2L, image);
        user.updateProfile(null, null, null, foreign, LocalDateTime.now());
        mvc.perform(get(foreign).with(owner())).andExpect(status().isNotFound());
        upload();
        assertThat(storage.read(2L, foreign)).isNotEmpty();
    }

    @Test
    void transactionalFlushFailureKeepsPreviousFileAndRemovesReplacement() throws Exception {
        upload();
        String previous = user.getProfileImageUrl();
        List<Path> before;
        try (var files = Files.list(root.resolve("1"))) {
            before = files.sorted().toList();
        }
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"))
                .when(repository).flush();
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/users/me/profile-image").file(image).with(owner())
                .header("Origin", "http://localhost:5173")).andExpect(status().isInternalServerError());
        assertThat(storage.read(1L, previous)).isNotEmpty();
        try (var files = Files.list(root.resolve("1"))) {
            assertThat(files.sorted().toList()).isEqualTo(before);
        }
    }

    @Test
    void authenticatedWebpUploadReturnsNormalizedPng() throws Exception {
        try (var input = getClass().getResourceAsStream("/profile-images/lossless.webp")) {
            image = new MockMultipartFile("image", "fake.png", "image/png", input.readAllBytes());
        }
        upload();
        mvc.perform(get(user.getProfileImageUrl()).with(owner())).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
        byte[] normalized = storage.read(1L, user.getProfileImageUrl());
        assertThat(normalized).startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
    }

    private void upload() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/users/me/profile-image").file(image).with(owner())
                .header("Origin", "http://localhost:5173")).andExpect(status().isOk());
    }

    private RequestPostProcessor owner() {
        return authentication(new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @TestConfiguration
    @EnableTransactionManagement
    static class Configuration {

        @Bean
        ProfileImageStorage profileImageStorage() {
            return new ProfileImageStorage(root.toString());
        }

        @Bean
        UserRepository userRepository() {
            return mock(UserRepository.class);
        }

        @Bean
        UserAccountService userAccountService() {
            return mock(UserAccountService.class);
        }

        @Bean
        UserTermsAgreementService userTermsAgreementService() {
            return mock(UserTermsAgreementService.class);
        }

        @Bean
        CurrentUserResolver currentUserResolver() {
            return new CurrentUserResolver();
        }

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return new JwtTokenProvider(new JwtProperties("project-api", "project-api",
                    "development-only-secret-with-at-least-32-bytes", 3600, 1209600));
        }

        @Bean
        SecurityErrorResponseWriter securityErrorResponseWriter(ObjectMapper mapper) {
            return new SecurityErrorResponseWriter(mapper);
        }

        @Bean
        UserAuthenticationService userAuthenticationService() {
            UserAuthenticationService service = mock(UserAuthenticationService.class);
            when(service.findActiveUser(anyLong())).thenReturn(mock(User.class));
            return service;
        }

        @Bean
        AbstractPlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }
    }
}
