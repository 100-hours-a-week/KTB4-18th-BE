package com.muse.meomuneum.chat.message.policy;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class ChatContentPolicy {
    private static final String SPACE = "[\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A"
            + "\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";
    private static final Pattern EDGES = Pattern.compile("^" + SPACE + "+|" + SPACE + "+$");
    private static final Pattern URL = Pattern.compile(
            "(?i)(?:https?://|www\\.|(?<![a-z0-9_-])[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\\.(?:com|net|org|kr|io)(?![a-z0-9_-]))");
    private static final String SEPARATOR = "[ \\-]?";
    private static final Pattern PERSONAL_INFORMATION = Pattern.compile(
            "(?i)(?<![a-z0-9])(?:[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])"
                    + SEPARATOR + "[1-4][0-9]{6}|[msrdo](?:[0-9]{8}|[0-9]{3}[a-z][0-9]{4})"
                    + "|(?:0|\\+82" + SEPARATOR + ")(?:1[016789]|2|[3-6][1-5]|70)"
                    + SEPARATOR + "[0-9]{3,4}" + SEPARATOR + "[0-9]{4})(?![a-z0-9])");

    private final ChatMessagePolicyProperties.Rules rules;

    public ChatContentPolicy(ChatMessagePolicyProperties.Rules rules) {
        this.rules = rules;
    }

    public String validateText(String content) {
        if (content == null) {
            throw new ChatMessageRejection("EMPTY_CONTENT");
        }
        String text = EDGES.matcher(content).replaceAll("");
        if (text.isEmpty()) {
            throw new ChatMessageRejection("EMPTY_CONTENT");
        }
        if (text.codePointCount(0, text.length()) > 300) {
            throw new ChatMessageRejection("CONTENT_TOO_LONG");
        }
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    throw new ChatMessageRejection("INVALID_CONTENT");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new ChatMessageRejection("INVALID_CONTENT");
            }
        }
        return text;
    }

    public void validatePersonalContent(String text) {
        if (!rules.detectionEnabled()) {
            return;
        }
        if (URL.matcher(text).find()) {
            throw new ChatMessageRejection("URL_NOT_ALLOWED");
        }
        if (PERSONAL_INFORMATION.matcher(text).find()) {
            throw new ChatMessageRejection("PERSONAL_INFORMATION");
        }
    }
}
