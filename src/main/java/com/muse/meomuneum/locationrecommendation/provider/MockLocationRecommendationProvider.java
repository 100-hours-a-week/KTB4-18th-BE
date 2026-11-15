package com.muse.meomuneum.locationrecommendation.provider;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.locationrecommendation.config.LocationRecommendationSettings;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.TrackResponse;

import tools.jackson.databind.ObjectMapper;

@Component
@Profile("local-mock")
public class MockLocationRecommendationProvider extends AiLocationRecommendationProvider {
    public MockLocationRecommendationProvider(ObjectMapper mapper,
            @Qualifier("locationRecommendationHttpClient") HttpClient client,
            LocationRecommendationSettings settings) {
        super(mapper, client, settings);
    }

    @Override
    public Call recommend(UUID requestId, double latitude, double longitude, Duration timeout,
            BooleanSupplier beforeSend) {
        if (!beforeSend.getAsBoolean()) {
            return new Call(CompletableFuture.completedFuture(new Result(List.of(), null)), () -> {
            }, false);
        }
        List<TrackResponse> tracks = List.of(
                new TrackResponse("6808583618", "Unknown Planet", "IU",
                        "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/4a/88/fd/4a88fdb6-a316-4236-c35f-c2d7dcd29562/mzaf_4959010141563513717.plus.aac.p.m4a",
                        "https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/0f/42/d4/0f42d4ec-e05e-afa2-225a-fa27bb65127b/cover_KM0025182_1.jpg/100x100bb.jpg",
                        "https://music.apple.com/us/album/unknown-planet/6808583617?i=6808583618&uo=4"),
                new TrackResponse("1703196825", "Landing in Love", "HANRORO",
                        "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/1d/14/b0/1d14b0a6-773d-7a46-fc1e-cd9855da0029/mzaf_18331909942495617093.plus.aac.p.m4a",
                        "https://is1-ssl.mzstatic.com/image/thumb/Music116/v4/c2/ef/87/c2ef8779-f3bb-5a1c-ef6f-c27185e62446/8809936067307.jpg/100x100bb.jpg",
                        "https://music.apple.com/us/album/landing-in-love/1703196819?i=1703196825&uo=4"),
                new TrackResponse("6820428730", "NEON", "A2O & A2O LITE",
                        "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/40/43/b8/4043b833-ec96-7807-fd8c-f74a62e777f3/mzaf_2462934578689260207.plus.aac.p.m4a",
                        "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/47/9c/af/479caf36-9941-3b8e-51f5-98e761463502/888272190403_Cover.jpg/100x100bb.jpg",
                        "https://music.apple.com/us/album/neon/6820428726?i=6820428730&uo=4"),
                new TrackResponse("6822369089", "Looks Can Kill", "IVE",
                        "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/b0/cf/66/b0cf663c-2092-6695-d89d-c42f5ef5b691/mzaf_283039607585779770.plus.aac.p.m4a",
                        "https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/01/2f/77/012f77a7-f38e-6799-5e8b-16d0756df44d/885288585919.jpg/100x100bb.jpg",
                        "https://music.apple.com/us/album/looks-can-kill/6822369065?i=6822369089&uo=4"),
                new TrackResponse("6795782855", "SUN KISS", "TUIDE",
                        "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/0d/dd/b9/0dddb93f-03de-b4a8-3b8e-3ac90e6d4387/mzaf_6348659234099955109.plus.aac.p.m4a",
                        "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/f2/a0/02/f2a00270-a846-b0aa-65b3-91bc94442e10/823375310600_Cover.jpg/100x100bb.jpg",
                        "https://music.apple.com/us/album/sun-kiss/6795782851?i=6795782855&uo=4"));
        return new Call(CompletableFuture.completedFuture(new Result(tracks, null)), () -> {
        }, true);
    }
}
