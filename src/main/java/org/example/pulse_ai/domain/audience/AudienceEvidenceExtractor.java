package org.example.pulse_ai.domain.audience;

import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ChannelPostEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Частотные слова и биграммы из своих постов (не репосты, не реклама). */
public final class AudienceEvidenceExtractor {

    private static final Pattern WORD = Pattern.compile("[\\p{L}]{4,}");
    private static final int MAX_SAMPLES = 8;
    private static final int MAX_TOKENS = 12;

    private AudienceEvidenceExtractor() {
    }

    public static AudienceEvidence extract(ChannelEntity channel, List<ChannelPostEntity> posts) {
        return extract(channel, posts, null);
    }

    public static AudienceEvidence extract(ChannelEntity channel, List<ChannelPostEntity> posts, String about) {
        Map<String, Integer> uni = new HashMap<>();
        List<String> samples = new ArrayList<>();
        int used = 0;

        List<ChannelPostEntity> ordered = posts == null ? List.of() : posts;
        int from = Math.max(0, ordered.size() - 50);
        for (int i = ordered.size() - 1; i >= from; i--) {
            ChannelPostEntity post = ordered.get(i);
            if (post.isForwarded() || looksLikeAd(textOf(post))) {
                continue;
            }
            String text = textOf(post);
            if (text.isBlank()) {
                continue;
            }
            used++;
            if (samples.size() < MAX_SAMPLES) {
                String snip = text.replace('\n', ' ').trim();
                samples.add(snip.length() > 220 ? snip.substring(0, 217) + "…" : snip);
            }
            List<String> words = words(text);
            for (String w : words) {
                if (!AudienceLexicon.recipeJargon(w)) {
                    uni.merge(w, 1, Integer::sum);
                }
            }
        }

        bump(uni, words(channel.getTitle()), 8);
        bump(uni, words(channel.getUsername()), 4);
        bump(uni, words(about), 10);

        final int postsUsed = used;
        List<String> tokens = new ArrayList<>();
        String blob = (channel.getTitle() == null ? "" : channel.getTitle()) + " "
                + (channel.getUsername() == null ? "" : channel.getUsername()) + " "
                + (about == null ? "" : about);
        for (String t : AudienceLexicon.familyQueries(blob + " " + String.join(" ", uni.keySet()))) {
            if (!tokens.contains(t)) {
                tokens.add(t);
            }
        }
        uni.entrySet().stream()
                .filter(e -> !AudienceLexicon.recipeJargon(e.getKey()))
                .filter(e -> e.getValue() >= 2 || postsUsed < 6)
                .sorted(freqThenLen())
                .map(Map.Entry::getKey)
                .forEach(t -> {
                    if (tokens.size() < MAX_TOKENS && tokens.stream().noneMatch(x -> AudienceLexicon.sameStem(x, t))) {
                        tokens.add(t);
                    }
                });

        String cat = channel.getCategory();
        if (cat != null && !AudienceLexicon.tooBroadLabel(cat) && !AudienceLexicon.tooBroadAloneQuery(cat)) {
            String n = AudienceLexicon.norm(cat);
            if (tokens.stream().noneMatch(x -> AudienceLexicon.sameStem(x, n))) {
                tokens.add(n);
            }
        }

        return new AudienceEvidence(
                List.copyOf(tokens),
                List.copyOf(samples),
                channel.getTitle(),
                channel.getUsername(),
                about,
                cat,
                used
        );
    }

    private static void bump(Map<String, Integer> uni, List<String> words, int weight) {
        for (String w : words) {
            uni.merge(w, weight, Integer::sum);
        }
    }

    private static List<String> words(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher m = WORD.matcher(AudienceLexicon.norm(text.replace('_', ' ')));
        List<String> out = new ArrayList<>();
        while (m.find()) {
            String w = m.group();
            if (!AudienceLexicon.stop(w)) {
                out.add(w);
            }
        }
        return out;
    }

    private static String textOf(ChannelPostEntity post) {
        if (post.getFullText() != null && !post.getFullText().isBlank()) {
            return post.getFullText();
        }
        return post.getTextPreview() == null ? "" : post.getTextPreview();
    }

    private static boolean looksLikeAd(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("erid")
                || t.contains("на правах рекламы")
                || t.contains("#реклама")
                || t.contains("рекламодател");
    }

    private static Comparator<Map.Entry<String, Integer>> freqThenLen() {
        return Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparingInt(e -> -e.getKey().length());
    }
}
