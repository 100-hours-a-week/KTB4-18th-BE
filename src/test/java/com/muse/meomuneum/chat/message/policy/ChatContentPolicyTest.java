package com.muse.meomuneum.chat.message.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatContentPolicyTest {
    private final ChatContentPolicy policy = new ChatContentPolicy(
            new ChatMessagePolicyProperties.Rules(Duration.ofSeconds(1), true, true, null, null));

    @Test
    void trimsLikeJavascriptAndCountsUnicodeCodePointsWithoutTruncation() {
        assertEquals("hello", policy.validateText("\uFEFF\u00A0hello\u3000"));
        assertEquals("😀".repeat(300), policy.validateText("😀".repeat(300)));
        assertEquals("CONTENT_TOO_LONG", assertThrows(ChatMessageRejection.class,
                () -> policy.validateText("😀".repeat(301))).reason());
        assertThrows(ChatMessageRejection.class, () -> policy.validateText("\uD800"));
        assertThrows(ChatMessageRejection.class, () -> policy.validateText("\u00A0\n"));
    }

    @Test
    void reviewedLiteralMatchesIncludeQuotesAndLyricsWithoutGuessingOtherWords() {
        assertEquals(com.muse.meomuneum.chat.ban.domain.ChatBanReason.PROFANITY,
                policy.banReason("가사에 씨발이 있어").orElseThrow());
        assertEquals(com.muse.meomuneum.chat.ban.domain.ChatBanReason.OBSCENITY,
                policy.banReason("보지 빨아").orElseThrow());
        for (String allowed : java.util.List.of("시발점", "성교육", "병신", "씨 발")) {
            assertEquals(java.util.Optional.empty(), policy.banReason(allowed));
        }
        ChatContentPolicy disabled = new ChatContentPolicy(new ChatMessagePolicyProperties.Rules(
                null, true, false, null, null));
        assertEquals(java.util.Optional.empty(), disabled.banReason("씨발"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "HTTP://example", "www.example.com", "example.kr/a",
            "sub.example.io", "example.net", "example.org"})
    void rejectsReviewedUrlForms(String content) {
        assertEquals("URL_NOT_ALLOWED", assertThrows(ChatMessageRejection.class,
                () -> policy.validatePersonalContent(content)).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"010-0000-0000", "01000000000", "+82-10-0000-0000", "02-0000-0000",
            "031 000 0000", "070-0000-0000", "000101-1000000", "0001011000000", "M00000000", "M000A0000"})
    void rejectsSyntheticPersonalInformation(String content) {
        assertEquals("PERSONAL_INFORMATION", assertThrows(ChatMessageRejection.class,
                () -> policy.validatePersonalContent(content)).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"시발점", "성교육", "2026-10-08", "BPM 120", "곡 번호 12345", "씨발"})
    void allowsPersonalContentExamples(String content) {
        policy.validatePersonalContent(content);
    }
}
