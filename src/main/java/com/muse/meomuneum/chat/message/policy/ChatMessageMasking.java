package com.muse.meomuneum.chat.message.policy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.vane.badwordfiltering.BadWordFiltering;

/** Immutable, startup-reviewed dictionary; never fetches external terms while handling messages. */
@Component
public class ChatMessageMasking {
    private static final String[] SEPARATORS = {" ", "\t", "\n", "\r", "\u000B", "\f", "\u00A0", "\u1680",
            "\u2000", "\u2001", "\u2002", "\u2003", "\u2004", "\u2005", "\u2006", "\u2007", "\u2008",
            "\u2009", "\u200A", "\u2028", "\u2029", "\u202F", "\u205F", "\u3000", "\uFEFF", "@", "_", "-", ".", "*"};
    private static final String GAPS = "[" + String.join("", java.util.Arrays.stream(SEPARATORS)
            .map(Pattern::quote).toList()) + "]*";
    private final List<MaskRule> rules;
    private final List<Pattern> allowed;

    public ChatMessageMasking() {
        this(readTerms("additions"), readTerms("exclusions"), readTerms("allowed-phrases"));
    }

    ChatMessageMasking(List<String> additions, List<String> exclusions, List<String> allowedPhrases) {
        BadWordFiltering dictionary = new BadWordFiltering();
        dictionary.addAll(additions);
        if (!dictionary.containsAll(exclusions)) {
            throw new IllegalArgumentException("unknown chat masking exclusion");
        }
        dictionary.removeAll(exclusions);
        rules = dictionary.stream().map(term -> Normalizer.normalize(term, Normalizer.Form.NFC)
                .replaceAll(GAPS, "")).distinct().sorted().map(term -> {
                    if (term.isEmpty()) {
                        throw new IllegalArgumentException("empty chat masking term");
                    }
                    BadWordFiltering filtering = new BadWordFiltering("*");
                    filtering.add(term);
                    String pattern = String.join(GAPS, term.codePoints()
                            .mapToObj(point -> Pattern.quote(new String(Character.toChars(point)))).toList());
                    return new MaskRule(Pattern.compile(pattern), filtering);
                }).toList();
        allowed = allowedPhrases.stream().map(phrase -> Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase))).toList();
    }

    public String mask(String text) {
        // Map normalized graphemes back to the input so unrelated text is never normalized or removed.
        StringBuilder normalized = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(text);
        int start = graphemes.first();
        for (int end = graphemes.next(); end != BreakIterator.DONE; start = end, end = graphemes.next()) {
            String segment = Normalizer.normalize(text.substring(start, end), Normalizer.Form.NFC);
            normalized.append(segment);
            for (int index = 0; index < segment.length(); index++) {
                starts.add(start);
                ends.add(end);
            }
        }
        String searchable = normalized.toString();
        boolean[] protectedPositions = new boolean[searchable.length()];
        for (Pattern phrase : allowed) {
            var matcher = phrase.matcher(searchable);
            while (matcher.find()) {
                java.util.Arrays.fill(protectedPositions, matcher.start(), matcher.end(), true);
            }
        }
        boolean[] masked = new boolean[text.length()];
        for (MaskRule rule : rules) {
            var matcher = rule.pattern().matcher(searchable);
            int searchFrom = 0;
            while (searchFrom < searchable.length() && matcher.find(searchFrom)) {
                searchFrom = matcher.start() + 1;
                boolean isProtected = true;
                for (int index = matcher.start(); index < matcher.end(); index++) {
                    isProtected &= protectedPositions[index];
                }
                String candidate = matcher.group().replaceAll(GAPS, "");
                if (!isProtected && !rule.filtering().change(candidate).equals(candidate)) {
                    java.util.Arrays.fill(masked, starts.get(matcher.start()), ends.get(matcher.end() - 1), true);
                }
            }
        }
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            if (masked[offset]) {
                result.append('*');
            } else {
                result.appendCodePoint(point);
            }
            offset += Character.charCount(point);
        }
        return result.toString();
    }

    private static List<String> readTerms(String name) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("chat/moderation/" + name + ".txt").getInputStream(), StandardCharsets.UTF_8))) {
            List<String> terms = reader.lines().map(String::strip).filter(line -> !line.startsWith("#")).toList();
            if (terms.stream().anyMatch(String::isEmpty) || Set.copyOf(terms).size() != terms.size()) {
                throw new IllegalArgumentException("invalid chat masking dictionary: " + name);
            }
            return List.copyOf(terms);
        } catch (IOException missing) {
            throw new IllegalStateException("chat masking dictionary unavailable: " + name, missing);
        }
    }

    private record MaskRule(Pattern pattern, BadWordFiltering filtering) {
    }
}
