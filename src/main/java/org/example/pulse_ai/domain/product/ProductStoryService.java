package org.example.pulse_ai.domain.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.config.PulseProductChannelProperties;
import org.example.pulse_ai.persistence.entity.ProductChannelPostEntity;
import org.example.pulse_ai.persistence.entity.ProductStoryArcEntity;
import org.example.pulse_ai.persistence.entity.ProductStoryBeatEntity;
import org.example.pulse_ai.persistence.repository.ProductStoryArcRepository;
import org.example.pulse_ai.persistence.repository.ProductStoryBeatRepository;
import org.example.pulse_ai.text.TextHumanizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductStoryService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final ProductStoryArcRepository arcRepository;
    private final ProductStoryBeatRepository beatRepository;
    private final ProductChannelService productChannelService;
    private final ProductStyleLearnerService styleLearner;
    private final LlmService llmService;
    private final PulseAnalysisProperties analysisProperties;
    private final PulseProductChannelProperties productProperties;

    @Transactional(readOnly = true)
    public Optional<ProductStoryArcEntity> activeArc() {
        return arcRepository.findFirstByStatusOrderByCreatedAtDesc("RUNNING")
                .or(() -> arcRepository.findFirstByStatusOrderByCreatedAtDesc("DRAFT"));
    }

    @Transactional(readOnly = true)
    public List<ProductStoryBeatEntity> beats(long arcId) {
        return beatRepository.findByArcIdOrderByBeatIndexAsc(arcId);
    }

    /**
     * Создаёт сюжетную арку «Знакомство с Pulse» и пишет тексты всех эпизодов.
     */
    @Transactional
    public ProductStoryArcEntity createIntroArc(long ownerTelegramId) {
        styleLearner.ensureFreshStyle();

        ProductStoryArcEntity arc = new ProductStoryArcEntity();
        arc.setTitle("Знакомство с Pulse");
        arc.setPremise("""
                Сюжетная арка канала-витрины: от боли админа канала → обещание ясности →
                ритуал работы с ботом → ассистент рядом → умный рост → приглашение попробовать.
                Тон: сериал, не презентация. Без раскрытия внутренностей продукта.""");
        arc.setStatus("DRAFT");
        arc.setCreatedBy(ownerTelegramId);
        arc = arcRepository.save(arc);

        List<ProductStoryPrompts.BeatOutline> outlines = ProductStoryPrompts.introArcBeats();
        List<String> previous = new ArrayList<>();
        short idx = 1;
        for (ProductStoryPrompts.BeatOutline outline : outlines) {
            ProductStoryBeatEntity beat = new ProductStoryBeatEntity();
            beat.setArcId(arc.getId());
            beat.setBeatIndex(idx);
            beat.setBeatKey(outline.key());
            beat.setTitle(outline.title());
            beat.setOutline(outline.outline());
            beat.setStatus("PLANNED");

            String draft = generateBeatText(outline, arc.getPremise(), previous, idx, outlines.size());
            beat.setDraftText(draft);
            beat.setStatus("READY");
            beatRepository.save(beat);

            previous.add("Эп." + idx + " «" + outline.title() + "»: " + trim(draft, 180));
            idx++;
        }
        return arc;
    }

    private String generateBeatText(
            ProductStoryPrompts.BeatOutline outline,
            String premise,
            List<String> previous,
            int index,
            int total
    ) {
        String prev = String.join("\n", previous);
        String prompt = ProductStoryPrompts.beatUserPrompt(outline, premise, prev, index, total)
                + "\n\n" + styleLearner.buildContextForPrompt();
        try {
            String text = llmService.completeTextWithTimeout(
                    ProductStoryPrompts.BEAT_SYSTEM,
                    prompt,
                    analysisProperties.getLlmTimeoutSeconds(),
                    1200
            );
            if (text != null && !text.isBlank()) {
                return TextHumanizer.humanize(text.trim());
            }
        } catch (Exception ex) {
            log.warn("Story beat LLM failed ({}): {}", outline.key(), ex.getMessage());
        }
        return fallbackBeat(outline);
    }

    private static String fallbackBeat(ProductStoryPrompts.BeatOutline outline) {
        return outline.title() + "\n\n"
                + "Админы каналов знают эту паузу перед «что постить». "
                + "Pulse AI как раз про ясность и следующий шаг — без суеты.\n\n"
                + "Продолжение — в следующем эпизоде.\n"
                + productCta();
    }

    private static String productCta() {
        return "→ https://t.me/Pulsse_AI_bot";
    }

    @Transactional(readOnly = true)
    public String formatPlan(long arcId) {
        ProductStoryArcEntity arc = arcRepository.findById(arcId).orElse(null);
        if (arc == null) {
            return "Арка не найдена.";
        }
        List<ProductStoryBeatEntity> list = beats(arcId);
        StringBuilder sb = new StringBuilder();
        sb.append("📖 <b>").append(esc(arc.getTitle())).append("</b>\n");
        sb.append("Статус: ").append(arc.getStatus()).append(" · эпизодов: ").append(list.size()).append("\n\n");
        for (ProductStoryBeatEntity b : list) {
            sb.append("<b>").append(b.getBeatIndex()).append(".</b> ")
                    .append(esc(b.getTitle()))
                    .append(" — <i>").append(b.getStatus()).append("</i>\n");
            if (b.getDraftText() != null) {
                sb.append(esc(trim(b.getDraftText(), 140))).append("\n\n");
            }
        }
        sb.append("Сюжет идёт серией: сначала план, потом эпизоды в канал — не одним вбросом.");
        return sb.toString().trim();
    }

    /** Публикует следующий READY/SCHEDULED эпизод. */
    @Transactional
    public PublishBeatResult publishNext(long arcId, long ownerTelegramId) {
        Optional<ProductStoryBeatEntity> next = beatRepository
                .findFirstByArcIdAndStatusInOrderByBeatIndexAsc(arcId, List.of("READY", "SCHEDULED"));
        if (next.isEmpty()) {
            return PublishBeatResult.fail("Нет готовых эпизодов к публикации.");
        }
        return publishBeat(next.get(), ownerTelegramId);
    }

    @Transactional
    public PublishBeatResult publishBeat(ProductStoryBeatEntity beat, long ownerTelegramId) {
        ProductChannelService.ChannelReadiness readiness = productChannelService.checkChannel();
        if (!readiness.ready()) {
            return PublishBeatResult.fail(readiness.message());
        }
        String text = beat.getDraftText();
        if (text == null || text.isBlank()) {
            return PublishBeatResult.fail("У эпизода нет текста.");
        }

        ProductChannelPostEntity post = new ProductChannelPostEntity();
        post.setRubric(mapRubric(beat.getBeatKey()));
        post.setDraftText(text);
        post.setCreatedByTelegramId(ownerTelegramId);
        post.setStatus(ProductChannelPostStatus.DRAFT);
        // save via publish path
        var outcome = productChannelService.publishDraftNow(post, text);
        if (!outcome.success()) {
            return PublishBeatResult.fail(outcome.error());
        }

        beat.setStatus("PUBLISHED");
        beat.setPublishedAt(Instant.now());
        beat.setTelegramMessageId(outcome.messageId());
        beat.setPostLink(outcome.link());
        beat.setChannelPostId(outcome.postId());
        beatRepository.save(beat);

        ProductStoryArcEntity arc = arcRepository.findById(beat.getArcId()).orElse(null);
        if (arc != null) {
            if (!"RUNNING".equals(arc.getStatus())) {
                arc.setStatus("RUNNING");
                arc.setStartedAt(Instant.now());
            }
            boolean allDone = beats(arc.getId()).stream().allMatch(b -> "PUBLISHED".equals(b.getStatus())
                    || "SKIPPED".equals(b.getStatus()));
            if (allDone) {
                arc.setStatus("DONE");
                arc.setCompletedAt(Instant.now());
            }
            arcRepository.save(arc);
        }
        return PublishBeatResult.ok(beat, outcome.link());
    }

    /**
     * Эпизод 1 сейчас, остальные — по одному в день в 11:00 МСК.
     */
    @Transactional
    public StartArcResult startArcDaily(long arcId, long ownerTelegramId) {
        List<ProductStoryBeatEntity> list = beats(arcId);
        if (list.isEmpty()) {
            return new StartArcResult(false, "Арка пустая", 0, 0);
        }
        ProductStoryBeatEntity first = list.get(0);
        PublishBeatResult firstPub = publishBeat(first, ownerTelegramId);
        if (!firstPub.ok()) {
            return new StartArcResult(false, firstPub.error(), 0, 0);
        }

        ZonedDateTime cursor = ZonedDateTime.now(MSK).toLocalDate().plusDays(1).atTime(11, 0).atZone(MSK);
        int scheduled = 0;
        for (int i = 1; i < list.size(); i++) {
            ProductStoryBeatEntity b = list.get(i);
            if (!"READY".equals(b.getStatus()) && !"PLANNED".equals(b.getStatus())) {
                continue;
            }
            b.setStatus("SCHEDULED");
            b.setScheduledFor(cursor.toInstant());
            beatRepository.save(b);
            scheduled++;
            cursor = cursor.plusDays(1);
        }
        ProductStoryArcEntity arc = arcRepository.findById(arcId).orElse(null);
        if (arc != null) {
            arc.setStatus("RUNNING");
            arc.setStartedAt(Instant.now());
            arcRepository.save(arc);
        }
        return new StartArcResult(true, null, 1, scheduled);
    }

    @Transactional
    public int publishDueScheduled() {
        if (!productProperties.isEnabled()) {
            return 0;
        }
        List<ProductStoryBeatEntity> due = beatRepository
                .findByStatusAndScheduledForLessThanEqualOrderByScheduledForAsc("SCHEDULED", Instant.now());
        int n = 0;
        for (ProductStoryBeatEntity beat : due) {
            Long owner = arcRepository.findById(beat.getArcId()).map(ProductStoryArcEntity::getCreatedBy).orElse(0L);
            PublishBeatResult r = publishBeat(beat, owner);
            if (r.ok()) {
                n++;
            } else {
                log.warn("Story beat {} publish failed: {}", beat.getId(), r.error());
            }
        }
        return n;
    }

    private static ProductPostRubric mapRubric(String key) {
        return switch (key) {
            case "PROMISE", "RITUAL", "ALLY" -> ProductPostRubric.FEATURE;
            case "GROWTH" -> ProductPostRubric.INSIGHT;
            case "INVITE" -> ProductPostRubric.COMMUNITY;
            default -> ProductPostRubric.INSIGHT;
        };
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public record PublishBeatResult(boolean ok, String error, ProductStoryBeatEntity beat, String link) {
        static PublishBeatResult ok(ProductStoryBeatEntity beat, String link) {
            return new PublishBeatResult(true, null, beat, link);
        }

        static PublishBeatResult fail(String error) {
            return new PublishBeatResult(false, error, null, null);
        }
    }

    public record StartArcResult(boolean ok, String error, int publishedNow, int scheduled) {
    }
}
