package com.muse.meomuneum.chat.message.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.text.Normalizer;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatMessageMaskingTest {
    private final ChatMessageMasking masking = new ChatMessageMasking();

    @Test
    void masksOnlyDetectedCharactersIncludingMultipleAndOverlappingTerms() {
        assertEquals("가사에 **과 ***이 있어 😀", masking.mask("가사에 씨발과 개새끼이 있어 😀"));
        assertEquals("**** / *****", masking.mask("씨발병신 / 자지 빨아"));
        ChatMessageMasking overlapping = new ChatMessageMasking(List.of("가나다", "나다라"), List.of(), List.of());
        assertEquals("****", overlapping.mask("가나다라"));
        assertEquals("****", masking.mask("씨발씨발"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"씨 발", "씨@발", "씨_발", "씨-발", "씨.발", "씨*발", "씨\u00A0발",
            "개@새_끼", "자 지 빨 아", "보지.빨아"})
    void masksInsertedSeparatorsAsPartOfTheDetectedSpan(String text) {
        assertEquals("*".repeat(text.codePointCount(0, text.length())), masking.mask(text));
    }

    @Test
    void unreviewedBypassesAreNotGuessed() {
        assertEquals("씨#발 씨1발", masking.mask("씨#발 씨1발"));
    }

    @Test
    void appliesServiceAdditionsExclusionsAndProtectedPhrases() {
        ChatMessageMasking custom = new ChatMessageMasking(List.of("검증어"), List.of("씨발"), List.of("검증어휘"));
        assertEquals("씨발 *** 검증어휘", custom.mask("씨발 검증어 검증어휘"));
        assertEquals("*** 시발점 ***", masking.mask("개새끼 시발점 개새끼"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"공지사항을 확인해 주세요", "이 곡 마스터가 좋아요", "게이 동성애자 트랜스젠더",
            "고환 유방 성교 성폭행 성교육", "시발점에서 시작해요", "새끼 고양이가 귀여워요",
            "영화를 보지 않고 자지 않는다", "호모 사피엔스", "😀 좋은 음악!", "BPM 120", "2018년"})
    void preservesReviewedNormalExamples(String text) {
        assertEquals(text, masking.mask(text));
    }

    @Test
    void preservesUnrelatedUnicodeAndCountsOriginalCodePointsForMasking() {
        String unrelated = Normalizer.normalize("음악", Normalizer.Form.NFD);
        String bad = Normalizer.normalize("씨발", Normalizer.Form.NFD);
        assertEquals(unrelated + " 😀 **", masking.mask(unrelated + " 😀 씨발"));
        assertEquals("*".repeat(bad.codePointCount(0, bad.length())), masking.mask(bad));
        ChatMessageMasking custom = new ChatMessageMasking(List.of("😀욕"), List.of(), List.of());
        assertEquals("좋은 **!", custom.mask("좋은 😀욕!"));
    }
}
