package org.example.pulse_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "pulse.analysis")
public class PulseAnalysisProperties {

    private int periodDays = 90;
    private int minPostsFull = 10;
    /** Быстрый режим: кэш постов, без Bot API, статистика сразу, LLM с коротким таймаутом */
    private boolean fastMode = true;
    private int syncMaxPosts = 100;
    private int syncMaxPages = 6;
    private int syncTimeoutMs = 20_000;
    private int minPostsForAnalysis = 3;
    private boolean skipBotApiDuringSync = true;
    private int llmTimeoutSeconds = 20;
    /** Зависшие запросы старше этого порога автоматически отменяются */
    private int staleRequestTimeoutMinutes = 4;
    /** Сколько постов отдавать в LLM для глубокого разбора */
    private int llmSamplePosts = 14;
}
