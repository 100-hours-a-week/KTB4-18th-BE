package com.muse.meomuneum.user.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.user.account.exception.UserAccountExceptionHandler;
import com.muse.meomuneum.user.account.service.ProfileImageService;
import com.muse.meomuneum.user.account.service.ProfileImageStorage;
import com.muse.meomuneum.user.account.service.UserAccountService;
import com.muse.meomuneum.user.account.service.UserTermsAgreementService;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@SpringBootTest(classes = ProfileImageHttpIntegrationTest.HttpConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes",
        "auth.cors.allowed-origins=http://localhost:5173"})
class ProfileImageHttpIntegrationTest {

    @TempDir
    static Path root;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    JwtTokenProvider tokens;

    @Test
    void realServletAcceptsDecimalBoundaryAndReturnsExact413ForServiceAndParserOverflow() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "PNG", png);
        User user = org.springframework.beans.BeanUtils.instantiateClass(User.class.getDeclaredConstructor());
        ReflectionTestUtils.setField(user, "id", 1L);
        ReflectionTestUtils.setField(user, "role", UserRole.USER);
        String token = tokens.createAccessToken(user);
        for (int size : new int[]{10_000_000, 10_000_001, 10 * 1024 * 1024 + 1}) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(("--profile-boundary\r\nContent-Disposition: form-data; name=\"image\"; " +
                    "filename=\"profile.png\"\r\nContent-Type: image/png\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(Arrays.copyOf(png.toByteArray(), size));
            body.write("\r\n--profile-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/v1/users/me/profile-image"))
                    .header("Authorization", "Bearer " + token).header("Origin", "http://localhost:5173")
                    .header("Content-Type", "multipart/form-data; boundary=profile-boundary")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("actual servlet upload of %s bytes", size)
                    .isEqualTo(size == 10_000_000 ? 200 : 413);
            if (size > 10_000_000) {
                assertThat(new ObjectMapper().readTree(response.body()).get("message").asText())
                        .isEqualTo("10MB 이하의 이미지만 등록할 수 있어요.");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
    @Import({SecurityConfig.class, UserAccountController.class, UserAccountExceptionHandler.class,
            CurrentUserResolver.class})
    static class HttpConfiguration {

        @Bean(destroyMethod = "")
        ProfileImageStorage profileImageStorage() throws Exception {
            return new ProfileImageStorage(root.toString());
        }

        @Bean
        ProfileImageService profileImageService(ProfileImageStorage storage) {
            ProfileImageService service = mock(ProfileImageService.class);
            when(service.upload(anyLong(), any()))
                    .thenAnswer(invocation -> storage.store(invocation.getArgument(0), invocation.getArgument(1)));
            return service;
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
        JwtTokenProvider jwtTokenProvider(JwtProperties properties) {
            return new JwtTokenProvider(properties);
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
    }
}
