package com.muse.meomuneum.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.muse.meomuneum.recommendation.dto.request.ImageRecommendationRequest;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.media.RecommendationImageValidator;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;

@ExtendWith(MockitoExtension.class)
class ImageRecommendationServiceTests {
    private static final String CONVERSATION_KEY = "11111111-1111-1111-1111-111111111111";

    @Mock
    private RecommendationRepository repository;

    @Test
    void createsImageSessionAfterValidatingJpegAndReturnsAcceptedResponse() throws Exception {
        when(repository.createImageSession(any(), eq(42L), any(Instant.class))).thenReturn(123L);
        var request = new ImageRecommendationRequest(CONVERSATION_KEY, "IMAGE", "CHATBOT");
        var image = jpegFile();
        var service = new ImageRecommendationService(repository, new RecommendationImageValidator());

        var response = service.accept(request, image, 42L);

        assertThat(response.recommendation_id()).isEqualTo(123L);
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.conversation_key()).isEqualTo(CONVERSATION_KEY);
        verify(repository).createImageSession(eq(request), eq(42L), any(Instant.class));
    }

    @Test
    void rejectsMissingAuthenticationBeforePersistingSession() {
        var request = new ImageRecommendationRequest(CONVERSATION_KEY, "IMAGE", "CHATBOT");
        var service = new ImageRecommendationService(repository, new RecommendationImageValidator());

        assertThatThrownBy(() -> service.accept(request, jpegFile(), null))
                .isInstanceOf(RecommendationException.class)
                .extracting(exception -> ((RecommendationException) exception).getStatus()).isEqualTo(401);
        verify(repository, never()).createImageSession(any(), any(), any());
    }

    @Test
    void rejectsInvalidJpegBeforePersistingSession() {
        var request = new ImageRecommendationRequest(CONVERSATION_KEY, "IMAGE", "CHATBOT");
        var invalidImage = new MockMultipartFile("image", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});
        var service = new ImageRecommendationService(repository, new RecommendationImageValidator());

        assertThatThrownBy(() -> service.accept(request, invalidImage, 42L))
                .isInstanceOf(RecommendationException.class)
                .extracting(exception -> ((RecommendationException) exception).getStatus()).isEqualTo(415);
        verify(repository, never()).createImageSession(any(), any(), any());
    }

    @Test
    void rejectsImageAboveFiveMibBeforeCopyingOrPersisting() {
        var request = new ImageRecommendationRequest(CONVERSATION_KEY, "IMAGE", "CHATBOT");
        var tooLarge = new MockMultipartFile("image", "photo.jpg", "image/jpeg",
                new byte[RecommendationImageValidator.MAX_BYTES + 1]);
        var service = new ImageRecommendationService(repository, new RecommendationImageValidator());

        assertThatThrownBy(() -> service.accept(request, tooLarge, 42L))
                .isInstanceOf(RecommendationException.class)
                .extracting(exception -> ((RecommendationException) exception).getStatus()).isEqualTo(413);
        verify(repository, never()).createImageSession(any(), any(), any());
    }

    private MockMultipartFile jpegFile() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "JPEG", output);
        return new MockMultipartFile("image", "photo.jpg", "image/jpeg", output.toByteArray());
    }
}
