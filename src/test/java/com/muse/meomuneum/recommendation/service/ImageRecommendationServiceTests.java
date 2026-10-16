package com.muse.meomuneum.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.muse.meomuneum.recommendation.dto.request.ImageRecommendationRequest;
import com.muse.meomuneum.recommendation.dto.response.RecommendationResponse;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.media.RecommendationImageValidator;
import com.muse.meomuneum.recommendation.provider.ImageStreamingRecommendationProvider;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;

@ExtendWith(MockitoExtension.class)
class ImageRecommendationServiceTests {
    private static final String CONVERSATION_KEY = "11111111-1111-1111-1111-111111111111";

    @Mock
    private RecommendationRepository repository;

    @Mock
    private RecommendationEventStream eventStream;

    @Mock
    private ObjectProvider<ImageStreamingRecommendationProvider> providerHolder;

    @Mock
    private ImageStreamingRecommendationProvider imageProvider;

    @Test
    void createsImageSessionAfterValidatingJpegAndReturnsAcceptedResponse() throws Exception {
        when(repository.createImageSession(any(), eq(42L), any(Instant.class))).thenReturn(123L);
        when(providerHolder.getIfAvailable()).thenReturn(imageProvider);
        doAnswer(invocation -> {
            var listener = invocation.getArgument(1,
                    com.muse.meomuneum.recommendation.provider.ImageRecommendationStreamListener.class);
            listener.onVerdict(true);
            listener.onTracks(java.util.List.of());
            return java.util.List.of();
        }).when(imageProvider).recommend(any(), any());
        when(repository.completeImageSession(eq(123L), eq(CONVERSATION_KEY), any())).thenReturn(
                new RecommendationResponse(123L, "COMPLETED", CONVERSATION_KEY, java.util.List.of(), Instant.now()));
        var request = new ImageRecommendationRequest(CONVERSATION_KEY, "IMAGE", "CHATBOT");
        var image = jpegFile();
        var service = service();

        var response = service.accept(request, image, 42L);

        assertThat(response.recommendation_id()).isEqualTo(123L);
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.conversation_key()).isEqualTo(CONVERSATION_KEY);
        verify(repository).createImageSession(eq(request), eq(42L), any(Instant.class));
        var order = inOrder(repository, eventStream);
        order.verify(repository).completeImageSession(eq(123L), eq(CONVERSATION_KEY), any());
        order.verify(eventStream).tracks(eq(123L), any());
        order.verify(eventStream).done(eq(123L), any());
    }

    @Test
    void persistsSuccessfulEmptyRejectionBeforePublishingFixedCopyAndTerminalEvents() throws Exception {
        when(repository.createImageSession(any(), eq(42L), any(Instant.class))).thenReturn(124L);
        when(repository.completeImageSession(eq(124L), eq(CONVERSATION_KEY), eq(java.util.List.of()))).thenReturn(
                new RecommendationResponse(124L, "COMPLETED", CONVERSATION_KEY, java.util.List.of(), Instant.now()));
        when(providerHolder.getIfAvailable()).thenReturn(imageProvider);
        doAnswer(invocation -> {
            var listener = invocation.getArgument(1,
                    com.muse.meomuneum.recommendation.provider.ImageRecommendationStreamListener.class);
            listener.onVerdict(false);
            listener.onRejected("scene_unreadable");
            listener.onTracks(java.util.List.of());
            return java.util.List.of();
        }).when(imageProvider).recommend(any(), any());
        var service = service();

        service.accept(new ImageRecommendationRequest(CONVERSATION_KEY, "IMAGE", "CHATBOT"), jpegFile(), 42L);

        var order = inOrder(repository, eventStream);
        order.verify(repository).completeImageSession(eq(124L), eq(CONVERSATION_KEY), eq(java.util.List.of()));
        order.verify(eventStream).rejected(eq(124L), eq("scene_unreadable"),
                eq("사진에서 장소를 알아보기 어려워요. 장소가 잘 보이게 다시 촬영해 주세요."));
        order.verify(eventStream).tracks(eq(124L), any());
        order.verify(eventStream).done(eq(124L), any());
        verify(eventStream, never()).text(eq(124L), any());
    }

    @Test
    void rejectsSaturatedAdmissionBeforeCopyingOrCallingImageProvider() throws Exception {
        var admission = new ImageRecommendationAdmission();
        assertThat(admission.reserve(CONVERSATION_KEY)).isTrue();
        when(eventStream.reserveImageCapacity()).thenReturn(true);
        when(providerHolder.getIfAvailable()).thenReturn(imageProvider);
        MultipartFile image = mock(MultipartFile.class);
        when(image.isEmpty()).thenReturn(false);
        when(image.getSize()).thenReturn(8L);
        var service = new ImageRecommendationService(repository, new RecommendationImageValidator(), eventStream,
                admission, providerHolder, Runnable::run);

        assertThatThrownBy(() -> service.accept(
                new ImageRecommendationRequest(CONVERSATION_KEY, "IMAGE", "CHATBOT"), image, 42L))
                .isInstanceOf(RecommendationException.class)
                .extracting(exception -> ((RecommendationException) exception).getStatus()).isEqualTo(429);
        verify(image, never()).getBytes();
        verify(imageProvider, never()).recommend(any(), any());
        verify(repository, never()).createImageSession(any(), any(), any());
    }

    @Test
    void rejectsMissingAuthenticationBeforePersistingSession() {
        var request = new ImageRecommendationRequest(CONVERSATION_KEY, "IMAGE", "CHATBOT");
        var service = service();

        assertThatThrownBy(() -> service.accept(request, jpegFile(), null))
                .isInstanceOf(RecommendationException.class)
                .extracting(exception -> ((RecommendationException) exception).getStatus()).isEqualTo(401);
        verify(repository, never()).createImageSession(any(), any(), any());
    }

    @Test
    void rejectsInvalidJpegBeforePersistingSession() {
        var request = new ImageRecommendationRequest(CONVERSATION_KEY, "IMAGE", "CHATBOT");
        var invalidImage = new MockMultipartFile("image", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(providerHolder.getIfAvailable()).thenReturn(imageProvider);
        var service = service();

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
        when(providerHolder.getIfAvailable()).thenReturn(imageProvider);
        var service = service();

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

    private ImageRecommendationService service() {
        lenient().when(eventStream.reserveImageCapacity()).thenReturn(true);
        lenient().when(providerHolder.getIfAvailable()).thenReturn(imageProvider);
        return new ImageRecommendationService(repository, new RecommendationImageValidator(), eventStream,
                new ImageRecommendationAdmission(), providerHolder, Runnable::run);
    }
}
