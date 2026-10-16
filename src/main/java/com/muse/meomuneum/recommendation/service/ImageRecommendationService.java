package com.muse.meomuneum.recommendation.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.muse.meomuneum.music.domain.MusicMetadataPolicy;
import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.ImageRecommendationRequest;
import com.muse.meomuneum.recommendation.dto.response.RecommendationAcceptedResponse;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.media.RecommendationImageValidator;
import com.muse.meomuneum.recommendation.provider.ImageRecommendationCommand;
import com.muse.meomuneum.recommendation.provider.ImageRecommendationStreamListener;
import com.muse.meomuneum.recommendation.provider.ImageStreamingRecommendationProvider;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;

@Service
public class ImageRecommendationService {
    private static final long DEADLINE_MILLIS = 60_000;
    private static final long CLEANUP_GRACE_MILLIS = 5_000;
    private final RecommendationRepository repository;
    private final RecommendationImageValidator validator;
    private final RecommendationEventStream eventStream;
    private final ImageRecommendationAdmission admission;
    private final ObjectProvider<ImageStreamingRecommendationProvider> provider;
    private final Executor imageExecutor;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "recommendation-image-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    public ImageRecommendationService(RecommendationRepository repository, RecommendationImageValidator validator,
            RecommendationEventStream eventStream, ImageRecommendationAdmission admission,
            ObjectProvider<ImageStreamingRecommendationProvider> provider,
            @Qualifier("imageRecommendationTaskExecutor") Executor imageExecutor) {
        this.repository = repository;
        this.validator = validator;
        this.eventStream = eventStream;
        this.admission = admission;
        this.provider = provider;
        this.imageExecutor = imageExecutor;
    }

    @PostConstruct
    void recoverOrphanedImageSessions() {
        repository.failOrphanedImageSessions(Instant.now());
    }

    public RecommendationAcceptedResponse accept(ImageRecommendationRequest request, MultipartFile image, Long userId) {
        if (userId == null) {
            throw new RecommendationException(401, "로그인이 필요합니다.");
        }
        ImageStreamingRecommendationProvider imageProvider = provider.getIfAvailable();
        if (imageProvider == null) {
            throw new RecommendationException(503, "이미지 추천을 사용할 수 없습니다.");
        }

        String conversationKey;
        try {
            conversationKey = UUID.fromString(request.conversation_key()).toString();
        } catch (IllegalArgumentException exception) {
            throw new RecommendationException(400, "invalid image recommendation request");
        }
        if (image == null || image.isEmpty()) {
            throw new RecommendationException(400, "invalid image recommendation request");
        }
        if (image.getSize() > RecommendationImageValidator.MAX_BYTES) {
            throw new RecommendationException(413, "image too large");
        }

        if (!eventStream.reserveImageCapacity()) {
            throw new RecommendationException(429, "이미지 추천 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        boolean admissionReserved = admission.reserve(conversationKey);
        if (!admissionReserved) {
            eventStream.releaseImageCapacity();
            throw new RecommendationException(429, "같은 대화에서 이미지 추천을 처리 중입니다.");
        }

        byte[] imageBytes = null;
        boolean registered = false;
        try {
            try {
                imageBytes = image.getBytes();
                validator.validate(imageBytes);
            } catch (IOException exception) {
                throw new RecommendationException(400, "invalid image recommendation request");
            }
            var normalizedRequest = new ImageRecommendationRequest(conversationKey, request.input_type(),
                    request.trigger_type());
            long id = repository.createImageSession(normalizedRequest, userId, Instant.now());
            eventStream.registerImage(id);
            registered = true;
            byte[] ownedBytes = imageBytes;
            imageBytes = null;
            dispatch(id, conversationKey, ownedBytes, imageProvider);
            return new RecommendationAcceptedResponse(id, "PROCESSING", conversationKey);
        } catch (RuntimeException exception) {
            if (!registered) {
                admission.release(conversationKey);
                eventStream.releaseImageCapacity();
            }
            throw exception;
        } finally {
            if (imageBytes != null) {
                Arrays.fill(imageBytes, (byte) 0);
            }
        }
    }

    private void dispatch(long id, String conversationKey, byte[] image,
            ImageStreamingRecommendationProvider imageProvider) {
        var terminal = new AtomicBoolean();
        var completed = new AtomicBoolean();
        var timedOut = new AtomicBoolean();
        var worker = new AtomicReference<Thread>();
        ScheduledFuture<?> timeout = watchdog.schedule(() -> {
            if (completed.get()) {
                return;
            }
            timedOut.set(true);
            Thread active = worker.get();
            if (active != null) {
                active.interrupt();
            }
            failOnce(id, "TIMEOUT", "이미지 추천 시간이 초과됐어요. 다시 촬영해 주세요.", terminal);
        }, DEADLINE_MILLIS + CLEANUP_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        try {
            imageExecutor.execute(() -> {
                worker.set(Thread.currentThread());
                List<TrackData> tracks = List.of();
                List<String> trailingText = new ArrayList<>();
                boolean[] sawTracks = {false};
                String[] rejectedReason = {null};
                try {
                    if (timedOut.get()) {
                        return;
                    }
                    tracks = imageProvider.recommend(
                            new ImageRecommendationCommand(UUID.fromString(conversationKey), UUID.randomUUID(), image),
                            new ImageRecommendationStreamListener() {
                                @Override
                                public void onVerdict(boolean usable) {
                                    eventStream.verdict(id, usable);
                                }
                                @Override
                                public void onRejected(String reason) {
                                    rejectedReason[0] = reason;
                                }
                                @Override
                                public void onText(String delta) {
                                    if (sawTracks[0]) {
                                        trailingText.add(delta);
                                    } else {
                                        eventStream.text(id, delta);
                                    }
                                }
                                @Override
                                public void onTracks(List<TrackData> values) {
                                    sawTracks[0] = true;
                                }
                            });
                    if (timedOut.get() || terminal.get()) {
                        return;
                    }
                    if (rejectedReason[0] != null) {
                        var result = repository.completeImageSession(id, conversationKey, List.of());
                        String message = rejectionCopy(rejectedReason[0]);
                        eventStream.rejected(id, rejectedReason[0], message);
                        eventStream.tracks(id, Map.of("tracks", List.of()));
                        if (terminal.compareAndSet(false, true)) {
                            eventStream.done(id, result);
                        }
                        return;
                    }
                    validateTracks(tracks);
                    Map<String, Object> tracksPayload = streamPayload(tracks);
                    eventStream.ensureImageCompletionFits(id, tracksPayload);
                    var result = repository.completeImageSession(id, conversationKey, tracks);
                    eventStream.tracks(id, tracksPayload);
                    trailingText.forEach(delta -> eventStream.text(id, delta));
                    if (terminal.compareAndSet(false, true)) {
                        eventStream.done(id, result);
                    }
                } catch (RuntimeException exception) {
                    boolean timedOutFailure = exception instanceof RecommendationException recommendationException
                            && recommendationException.getStatus() == 504;
                    failOnce(id, timedOutFailure ? "TIMEOUT" : "FAILED",
                            timedOutFailure
                                    ? "이미지 추천 시간이 초과됐어요. 다시 촬영해 주세요."
                                    : "이미지 추천 결과를 처리하지 못했어요. 다시 시도해 주세요.",
                            terminal);
                } finally {
                    completed.set(true);
                    timeout.cancel(false);
                    worker.set(null);
                    Arrays.fill(image, (byte) 0);
                    admission.release(conversationKey);
                }
            });
        } catch (RejectedExecutionException exception) {
            completed.set(true);
            timeout.cancel(false);
            Arrays.fill(image, (byte) 0);
            admission.release(conversationKey);
            failOnce(id, "FAILED", "이미지 추천 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", terminal);
        }
    }

    private void failOnce(long id, String status, String detail, AtomicBoolean terminal) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        try {
            repository.fail(id, status, Instant.now());
        } finally {
            try {
                eventStream.error(id, detail);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void validateTracks(List<TrackData> tracks) {
        if (tracks == null || tracks.size() > 5
                || tracks.stream().map(track -> track.provider() + ":" + track.externalId()).distinct()
                        .count() != tracks.size()
                || tracks.stream()
                        .map(track -> (track.artistName() + ":" + track.title()).trim().toLowerCase(Locale.ROOT))
                        .distinct().count() != tracks.size()) {
            throw new RecommendationException(502, "invalid image tracks");
        }
        tracks.forEach(track -> {
            MusicMetadataPolicy.validate(track.title(), track.artistName());
            if (track.externalId() == null || track.externalId().length() > 128
                    || tooLong(track.coverUrl(), 1024) || tooLong(track.previewUrl(), 1024)
                    || tooLong(track.storeUrl(), 1024)) {
                throw new RecommendationException(502, "invalid image track metadata");
            }
        });
    }

    private boolean tooLong(String value, int maximum) {
        return value != null && value.length() > maximum;
    }

    private Map<String, Object> streamPayload(List<TrackData> tracks) {
        List<Map<String, Object>> items = tracks.stream().map(track -> {
            var item = new java.util.LinkedHashMap<String, Object>();
            item.put("track_id", track.externalId());
            item.put("title", track.title());
            item.put("artist", track.artistName());
            item.put("artwork_url", track.coverUrl());
            item.put("preview_url", track.previewUrl());
            item.put("store_url", track.storeUrl());
            item.put("reason", null);
            return java.util.Collections.unmodifiableMap(item);
        }).toList();
        return Map.of("tracks", items);
    }

    private String rejectionCopy(String reason) {
        return switch (reason) {
            case "scene_unreadable" -> "사진에서 장소를 알아보기 어려워요. 장소가 잘 보이게 다시 촬영해 주세요.";
            case "not_a_place" -> "장소 사진으로 확인되지 않았어요. 장소가 보이는 사진으로 다시 촬영해 주세요.";
            case "provider_blocked" -> "이 사진은 처리할 수 없어요. 다른 사진으로 다시 촬영해 주세요.";
            default -> throw new RecommendationException(502, "invalid image rejection");
        };
    }

    @PreDestroy
    void stopWatchdog() {
        watchdog.shutdownNow();
    }
}
