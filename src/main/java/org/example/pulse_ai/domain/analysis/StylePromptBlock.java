package org.example.pulse_ai.domain.analysis;

final class StylePromptBlock {

    private StylePromptBlock() {
    }

    /** Блок пользовательского стиля — вставляется в начало user-prompt. */
    static String format(String stylePrompt) {
        if (stylePrompt == null || stylePrompt.isBlank()) {
            return "";
        }
        return """
                ПРИОРИТЕТ — стиль автора (выполняй в первую очередь, важнее примеров канала ниже):
                %s

                """.formatted(stylePrompt.trim());
    }
}
