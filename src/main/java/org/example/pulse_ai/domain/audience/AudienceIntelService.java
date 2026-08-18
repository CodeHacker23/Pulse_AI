package org.example.pulse_ai.domain.audience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ChannelPostEntity;
import org.example.pulse_ai.persistence.repository.ChannelPostRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.stats.scraper.TelegramPublicChannelScraper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Слои ЦА:
 * 0 факты из постов → 1 гипотеза (LLM строго по фактам) → 2 грунт запросов → 3 сохранение.
 * Без фактов LLM не вызываем и ничего не выдумываем.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudienceIntelService {

    private static final String SYSTEM = """
            Ты размечаешь НИШУ Telegram-канала для поиска такой же ЦА в других каналах и чатах.
            Не копируй слова из карточки товара (ноты, ваниль, сердце, основа, пирамида аромата).
            Нужна тема рынка: что продают / о чём канал одной фразой.
            Пример: посты про «верхние ноты / ваниль» + в описании «диффузоры» → theme=диффузоры,
            search_queries=["диффузор","парфюм","ароматы","парфюмерия"].
            buyer_role — кто покупает (ценитель ароматов, хозяйка дома…), не отрасль «бизнес».
            parse_venues — типы мест в Telegram: каналы, чаты, группы этой темы.
            parse_method: GROUP_MEMBERS | CHANNEL_COMMENTS | LOOKALIKE_CHANNELS
            Запрещены запросы: бизнес, новости, крипта — если этого нет в текстах.
            Ответ — только JSON.""";

    private final ChannelPostRepository postRepository;
    private final ChannelRepository channelRepository;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final PulseAnalysisProperties analysisProperties;
    private final TelegramPublicChannelScraper publicScraper;

    @Transactional
    public AudienceBrief buildAndSave(ChannelEntity channel) {
        AudienceBrief brief = build(channel, true);
        persist(channel, brief);
        return brief;
    }

    public AudienceBrief resolve(ChannelEntity channel) {
        AudienceBrief stored = readStored(channel);
        if (stored != null && stored.usable() && !AudienceLexicon.queriesLookLikeRecipe(stored.searchQueries())) {
            return stored;
        }
        return buildAndSave(channel);
    }

    public AudienceBrief build(ChannelEntity channel, boolean allowLlm) {
        List<ChannelPostEntity> posts = postRepository.findByChannelIdOrderByPublishedAtAsc(channel.getId());
        String about = "";
        try {
            about = publicScraper.fetchAbout(channel.getUsername());
        } catch (Exception ex) {
            log.debug("about skip: {}", ex.getMessage());
        }
        AudienceEvidence evidence = AudienceEvidenceExtractor.extract(channel, posts, about);
        int subs = channel.getSubscriberCount() != null ? channel.getSubscriberCount() : 0;
        if (evidence.thin()) {
            log.info("Audience intel thin: channelId={} postsUsed={} tokens={}",
                    channel.getId(), evidence.postsUsed(), evidence.tokens());
            return AudienceBriefGrounding.fromEvidence(evidence, subs);
        }
        if (!allowLlm) {
            return AudienceBriefGrounding.fromEvidence(evidence, subs);
        }
        try {
            int timeout = Math.min(18, Math.max(10, analysisProperties.getLlmTimeoutSeconds()));
            String json = llmService.completeJsonWithTimeout(SYSTEM, userPrompt(evidence), timeout);
            JsonNode node = objectMapper.readTree(json);
            AudienceBrief merged = AudienceBriefGrounding.mergeLlm(evidence, node, subs);
            log.info("Audience intel: channelId={} source={} queries={} role={}",
                    channel.getId(), merged.source(), merged.searchQueries(), merged.buyerRole());
            return merged;
        } catch (Exception ex) {
            log.warn("Audience LLM skip, posts-only: {}", ex.getMessage());
            return AudienceBriefGrounding.fromEvidence(evidence, subs);
        }
    }

    public String promptBlock(AudienceBrief brief) {
        if (brief == null) {
            return "";
        }
        return """
                ФАКТЫ ЦА (уже извлечены из постов, не переписывай отраслью вроде «бизнес»):
                - роль: %s
                - зачем читают: %s
                - токены из постов: %s
                - где искать/парсить: %s
                - метод: %s
                - уверенность: %d/100, источник: %s
                В разделе аудитории назови эту роль и эти токены. Не подменяй их общим ярлыком.
                """.formatted(
                nz(brief.buyerRole()),
                nz(brief.jobToBeDone()),
                brief.evidenceTokens() == null ? "—" : String.join(", ", brief.evidenceTokens()),
                brief.parseVenues() == null || brief.parseVenues().isEmpty()
                        ? "пока не ясно"
                        : String.join("; ", brief.parseVenues()),
                nz(brief.parseMethod()),
                brief.confidence(),
                nz(brief.source())
        );
    }

    private void persist(ChannelEntity channel, AudienceBrief brief) {
        try {
            channel.setAudienceBrief(objectMapper.writeValueAsString(brief));
            channelRepository.save(channel);
        } catch (Exception ex) {
            log.warn("Не сохранил audience_brief: {}", ex.getMessage());
        }
    }

    private AudienceBrief readStored(ChannelEntity channel) {
        String raw = channel.getAudienceBrief();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, AudienceBrief.class);
        } catch (Exception ex) {
            log.debug("audience_brief parse fail: {}", ex.getMessage());
            return null;
        }
    }

    private static String userPrompt(AudienceEvidence evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("Канал: «").append(nz(evidence.title())).append("»");
        if (evidence.username() != null && !evidence.username().isBlank()) {
            sb.append(" @").append(evidence.username());
        }
        sb.append("\nО канале: ").append(nz(evidence.about()));
        sb.append("\nСлова-ориентиры: ").append(String.join(", ", evidence.tokens()));
        if (evidence.tgstatCategory() != null && !AudienceLexicon.tooBroadLabel(evidence.tgstatCategory())) {
            sb.append("\nКатегория каталога (слабый сигнал): ").append(evidence.tgstatCategory());
        } else if (evidence.tgstatCategory() != null) {
            sb.append("\nКатегория каталога «").append(evidence.tgstatCategory())
                    .append("» слишком широкая — игнорируй её.");
        }
        sb.append("\nЦитаты:\n");
        int i = 1;
        for (String s : evidence.samples()) {
            sb.append(i++).append(". ").append(s).append('\n');
        }
        sb.append("""
                JSON:
                {"theme":"диффузоры","buyer_role":"...","job":"...","search_queries":["диффузор","парфюм"],"parse_venues":["чаты про парфюм","группы любителей ароматов"],"parse_method":"GROUP_MEMBERS"}
                """);
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
