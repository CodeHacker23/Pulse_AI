package org.example.pulse_ai.domain.audience;

import java.util.List;

/** Слой 0: только то, что реально есть у канала. Без LLM. */
public record AudienceEvidence(
        List<String> tokens,
        List<String> samples,
        String title,
        String username,
        String about,
        String tgstatCategory,
        int postsUsed
) {
    public boolean thin() {
        return tokens == null || tokens.size() < 2;
    }

    public String blob() {
        return String.join(" ",
                title == null ? "" : title,
                username == null ? "" : username,
                about == null ? "" : about,
                samples == null ? "" : String.join(" ", samples),
                tokens == null ? "" : String.join(" ", tokens));
    }

    public String queryHint() {
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }
        return String.join(", ", tokens.stream().limit(5).toList());
    }
}
