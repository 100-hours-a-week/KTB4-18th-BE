package com.muse.meomuneum.recommendation.provider;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.muse.meomuneum.recommendation.dto.TrackData;
import org.springframework.stereotype.Component;

/** 2026-09-17 iTunes Search API에서 확보한 샘플. 요청 중에는 외부 API를 호출하지 않습니다. */
@Component
public class SampleRecommendationProvider implements RecommendationProvider {
    private final List<TrackData> tracks = List.of(
            new TrackData("ITUNES", "1560113134", "Lilac", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/35/9f/83/359f83b3-1423-3153-1641-98e948b7fc65/cover_-_EDAM_5_LILAC.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/be/62/d2/be62d240-fe27-0514-9e20-b29645cc3dcb/mzaf_8930037822844249227.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1511885178", "eight (feat. SUGA)", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/6b/65/4d/6b654d71-ed85-c6c4-8fe2-ef3d8e9f2ee0/cover_-.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/b7/70/da/b770dac6-f957-d045-af12-ebff80578aa4/mzaf_13656583148299369335.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1726888402", "Love wins all", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music126/v4/e3/56/1e/e3561eb5-de12-790d-569b-53fa22e6b491/cover_KM0019422_1.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/94/79/f5/9479f56a-ac54-6449-3603-0645090bb3c1/mzaf_7391528050042328078.plus.aac.p.m4a"),
            new TrackData("ITUNES", "409076748", "Good Day", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/6d/2e/c9/6d2ec954-d8d8-b26a-1a8f-def6026815c5/mzi.orccormf.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/12/de/86/12de86be-3968-e309-f4da-3e624ec4ff49/mzaf_11516704482831655132.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1488300930", "Blueming", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/b8/b8/18/b8b81899-a984-be85-5656-ec1f2fc10227/5_Love_poem.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/15/08/47/150847d9-2c85-b6b5-a2eb-b61ceb4517e0/mzaf_168895178236497376.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1229073310", "Palette (feat. G-DRAGON)", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music123/v4/94/ec/24/94ec2442-5add-d1ca-5eba-37f1298abfbc/cover_KM0005225_1.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/1f/02/ec/1f02ec43-61e0-b241-3832-16ce29c3d280/mzaf_8074590719931790363.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1438614348", "Bbibbi", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music114/v4/8a/2a/fb/8a2afbb2-728b-f160-8210-ba43146e9d83/cover-_DS.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/84/7c/f8/847cf8ab-803c-1296-8950-f263f7c9cc49/mzaf_14552590457128055922.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1550907178", "Celebrity", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/02/32/09/02320995-35fb-e63f-fb52-76595b70ed45/1.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/13/82/d4/1382d452-60e8-c147-0c26-2e9ce7255dea/mzaf_4297788010739193805.plus.aac.p.m4a"),
            new TrackData("ITUNES", "484346204", "너랑 나 (YOU&I)", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music/61/42/2c/mzi.mezumbpd.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/e9/a7/cd/e9a7cd80-eb86-c724-6360-2881b5544cc6/mzaf_14496311500699851022.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1224530983", "Can't Love You Anymore (with OHHYUK)", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/e4/f1/6c/e4f16cd8-8f21-8788-3f84-79628a3e88c2/cover-_DS.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/6b/78/a9/6b78a9ca-b63d-9568-edb2-f8039e58764a/mzaf_2702515648537167454.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1488300934", "Love Poem", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/b8/b8/18/b8b81899-a984-be85-5656-ec1f2fc10227/5_Love_poem.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/c0/af/59/c0af5939-6434-69be-2628-99c1455256c3/mzaf_3831246281503898434.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1219218446", "Through the Night", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music114/v4/dc/12/fe/dc12fe03-172b-a843-0d96-12819fa05b6c/cover-.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/5a/43/d5/5a43d5c0-8b9a-2a91-dd9c-405bfd51b82f/mzaf_2888874735741039485.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1613272550", "GANADARA (feat. IU)", "Jay Park", "https://is1-ssl.mzstatic.com/image/thumb/Music116/v4/73/fb/31/73fb31ac-1ca6-643d-3bef-44832b988fbf/cover_KM0014893_1.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/e3/c4/de/e3c4dea7-f39f-1158-ef57-30560e9d9d24/mzaf_8483793774492569197.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1229073403", "Jam Jam", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music123/v4/94/ec/24/94ec2442-5add-d1ca-5eba-37f1298abfbc/cover_KM0005225_1.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/80/99/58/80995849-b3f2-8f25-c1c8-593182bbd8a7/mzaf_5825362826916637760.plus.aac.p.m4a"),
            new TrackData("ITUNES", "1051977842", "스물셋 Twenty-Three", "IU", "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/f8/75/d2/f875d2e2-1317-7f5a-2025-7ca9513dbb3d/COVER-.jpg/100x100bb.jpg", "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/de/bc/c3/debcc392-e0cb-e487-273d-13725c93e93d/mzaf_8155948164669914598.plus.aac.p.m4a")
    );

    @Override
    public List<TrackData> recommend(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT);
        // 실제 AI 대신 단순 키워드로 샘플 곡의 순서를 바꿉니다.
        boolean calm = text.contains("비") || text.contains("밤") || text.contains("차분") || text.contains("휴식");
        return tracks.stream()
                .sorted(Comparator.comparingInt(track -> score(track, text, calm)))
                .limit(5).toList();
    }

    private int score(TrackData track, String text, boolean calm) {
        if (text.contains(track.title().toLowerCase(Locale.ROOT))) return 0;
        if (calm && List.of("Through the Night", "Love Poem", "Love wins all", "Palette (feat. G-DRAGON)",
                "Can't Love You Anymore (with OHHYUK)").contains(track.title())) return 1;
        return 2;
    }
}
