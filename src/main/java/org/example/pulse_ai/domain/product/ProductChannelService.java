package org.example.pulse_ai.domain.product;



import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.example.pulse_ai.ai.LlmService;

import org.example.pulse_ai.config.PulseAnalysisProperties;

import org.example.pulse_ai.config.PulseProductChannelProperties;

import org.example.pulse_ai.domain.publish.ChannelPublishService;

import org.example.pulse_ai.persistence.entity.ChannelEntity;

import org.example.pulse_ai.persistence.entity.ProductChannelPostEntity;

import org.example.pulse_ai.persistence.repository.ProductChannelPostRepository;

import org.example.pulse_ai.telegram.TelegramBotApiService;

import org.example.pulse_ai.telegram.TelegramMessageSender;

import org.example.pulse_ai.text.TextHumanizer;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import org.telegram.telegrambots.meta.api.objects.Chat;



import java.time.Instant;

import java.util.Optional;



@Slf4j

@Service

@RequiredArgsConstructor

public class ProductChannelService {



    private final LlmService llmService;

    private final PulseProductChannelProperties properties;

    private final PulseAnalysisProperties analysisProperties;

    private final ProductChannelPostRepository postRepository;

    private final TelegramBotApiService botApi;

    private final TelegramMessageSender messageSender;

    private final ProductStyleLearnerService styleLearner;

    private final ProductReleaseService releaseService;



    @Transactional

    public ProductChannelPostEntity generateDraft(ProductPostRubric rubric, long ownerTelegramId, String extraContext) {

        styleLearner.ensureFreshStyle();

        String brainContext = styleLearner.buildContextForPrompt()
                + "\n\n" + releaseService.factsBlock(5);

        String text = null;

        // Changelog из реестра — факты, не фантазия LLM
        if (rubric == ProductPostRubric.CHANGELOG) {
            text = releaseService.composeLatestReadyPatchNote().orElse(null);
        }

        if (text == null) {
            String prompt = buildPrompt(rubric, brainContext, extraContext);
            try {
                text = llmService.completeTextWithTimeout(
                        ProductChannelPrompts.SYSTEM,
                        prompt,
                        analysisProperties.getLlmTimeoutSeconds(),
                        1500
                );
                text = text != null ? TextHumanizer.humanize(text.trim()) : fallback(rubric);
            } catch (Exception ex) {
                log.warn("Product post generation failed: {}", ex.getMessage());
                text = fallback(rubric);
            }
        }

        ProductChannelPostEntity post = new ProductChannelPostEntity();
        post.setRubric(rubric);
        post.setDraftText(text);
        post.setCreatedByTelegramId(ownerTelegramId);
        post.setStatus(ProductChannelPostStatus.DRAFT);
        return postRepository.save(post);
    }

    /** Патчноут конкретного релиза из реестра. */
    @Transactional
    public ProductChannelPostEntity generateChangelogFromRelease(long releaseId, long ownerTelegramId) {
        var release = releaseService.findById(releaseId)
                .orElseThrow(() -> new IllegalArgumentException("Релиз не найден"));
        ProductChannelPostEntity post = new ProductChannelPostEntity();
        post.setRubric(ProductPostRubric.CHANGELOG);
        post.setDraftText(releaseService.composePatchNote(release));
        post.setCreatedByTelegramId(ownerTelegramId);
        post.setStatus(ProductChannelPostStatus.DRAFT);
        return postRepository.save(post);
    }



    @Transactional(readOnly = true)

    public Optional<ProductChannelPostEntity> findById(long postId) {

        return postRepository.findById(postId);

    }



    @Transactional

    public ProductChannelPostEntity updateDraftText(long postId, String text) {

        ProductChannelPostEntity post = postRepository.findById(postId)

                .orElseThrow(() -> new IllegalArgumentException("Черновик не найден"));

        post.setDraftText(text.trim());

        return postRepository.save(post);

    }



    public ChannelReadiness checkChannel() {

        if (!properties.isEnabled()) {

            return ChannelReadiness.blocked("Модуль канала продукта отключён в конфиге.");

        }

        ResolvedChannel resolved = resolveChannel().orElse(null);

        if (resolved == null) {

            return ChannelReadiness.blocked(

                    "Укажите pulse.product.channel-chat-id или channel-username в конфиге.");

        }

        TelegramBotApiService.BotAdminStatus status = botApi.verifyBotIsAdmin(resolved.chatId());

        if (!status.isAdmin() || !status.canPost()) {

            return ChannelReadiness.blocked(

                    "Бот не админ канала или нет права публикации. Добавьте @Pulsse_AI_bot админом.");

        }

        return ChannelReadiness.ok(resolved);

    }



    @Transactional

    public PublishOutcome publish(long postId, String finalText) {

        ChannelReadiness readiness = checkChannel();

        if (!readiness.ready()) {

            return PublishOutcome.failure(readiness.message());

        }

        ProductChannelPostEntity post = postRepository.findById(postId).orElse(null);

        if (post == null) {

            return PublishOutcome.failure("Черновик не найден.");

        }



        ResolvedChannel channel = readiness.channel();

        Integer messageId = messageSender.sendToChannel(channel.chatId(), finalText);

        if (messageId == null) {

            return PublishOutcome.failure("Не удалось опубликовать в канал.");

        }



        ChannelEntity linkSource = new ChannelEntity();

        linkSource.setTelegramChatId(channel.chatId());

        linkSource.setUsername(channel.username());

        String link = ChannelPublishService.buildPostLink(linkSource, messageId);



        post.setFinalText(finalText);

        post.setTelegramMessageId(messageId);

        post.setPostLink(link);

        post.setStatus(ProductChannelPostStatus.PUBLISHED);

        post.setPublishedAt(Instant.now());

        postRepository.save(post);

        if (post.getRubric() == ProductPostRubric.CHANGELOG) {
            releaseService.markPostedFromPatchNote(finalText, messageId.longValue());
        }

        log.info("Product channel post published: id={}, rubric={}", postId, post.getRubric());

        return PublishOutcome.success(post.getId(), messageId, link);

    }

    /** Сохранить черновик и сразу опубликовать в канал продукта. */
    @Transactional
    public PublishOutcome publishDraftNow(ProductChannelPostEntity draft, String finalText) {
        draft.setDraftText(finalText);
        draft.setStatus(ProductChannelPostStatus.DRAFT);
        ProductChannelPostEntity saved = postRepository.save(draft);
        return publish(saved.getId(), finalText);
    }



    private Optional<ResolvedChannel> resolveChannel() {
        if (properties.getChannelChatId() != null) {
            for (long chatId : candidateChatIds(properties.getChannelChatId())) {
                Optional<Chat> chat = botApi.getChat(chatId);
                if (chat.isPresent()) {
                    String username = chat.get().getUserName();
                    if (username == null || username.isBlank()) {
                        username = properties.getChannelUsername();
                    }
                    return Optional.of(new ResolvedChannel(chat.get().getId(), username));
                }
            }
            String username = properties.getChannelUsername();
            return Optional.of(new ResolvedChannel(properties.getChannelChatId(), username));
        }

        if (properties.getChannelUsername() == null || properties.getChannelUsername().isBlank()) {

            return Optional.empty();

        }

        String username = properties.getChannelUsername().replace("@", "");

        Optional<Chat> chat = botApi.getChatByUsername(username);

        return chat.map(c -> new ResolvedChannel(c.getId(), c.getUserName() != null ? c.getUserName() : username));

    }

    private static java.util.List<Long> candidateChatIds(long configured) {
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        ids.add(configured);
        if (configured > 0 && configured < 10_000_000_000L) {
            ids.add(Long.parseLong("-100" + configured));
        }
        if (configured < 0 && String.valueOf(configured).startsWith("-100")) {
            String tail = String.valueOf(configured).substring(4);
            try {
                ids.add(Long.parseLong(tail));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return java.util.List.copyOf(ids);
    }

    public String bootstrapWelcomeText() {
        String botLink = properties.getBotLink();
        return """
                👋 <b>Pulse AI — ваш разбор Telegram-канала за пару минут</b>

                Мы помогаем админам каналов:
                • увидеть, что реально заходит у аудитории
                • получить идеи и готовые черновики постов
                • публиковать прямо из бота

                <b>Попробовать бесплатно:</b> <a href="%s">@Pulsse_AI_bot</a>

                <i>Канал — демо продукта. Бот — рабочий инструмент.</i>""".formatted(botLink);
    }

    @Transactional
    public PublishOutcome publishBootstrapWelcome() {
        ChannelReadiness readiness = checkChannel();
        if (!readiness.ready()) {
            return PublishOutcome.failure(readiness.message());
        }

        String text = bootstrapWelcomeText();
        ResolvedChannel channel = readiness.channel();

        var existingBootstrap = postRepository
                .findFirstByCreatedByTelegramIdAndStatusOrderByPublishedAtDesc(
                        0L, ProductChannelPostStatus.PUBLISHED);
        if (existingBootstrap.isPresent() && existingBootstrap.get().getTelegramMessageId() != null) {
            ProductChannelPostEntity post = existingBootstrap.get();
            if (messageSender.editChannelMessage(channel.chatId(), post.getTelegramMessageId(), text)) {
                post.setDraftText(text);
                post.setFinalText(text);
                postRepository.save(post);
                log.info("Product channel welcome post repaired: messageId={}", post.getTelegramMessageId());
                return PublishOutcome.success(post.getId(), post.getTelegramMessageId(), post.getPostLink());
            }
        }

        if (postRepository.countByStatus(ProductChannelPostStatus.PUBLISHED) > 0) {
            return PublishOutcome.failure("В канале уже есть публикации Pulse AI.");
        }
        Integer messageId = messageSender.sendToChannel(channel.chatId(), text);
        if (messageId == null) {
            return PublishOutcome.failure("Не удалось опубликовать приветственный пост.");
        }

        ProductChannelPostEntity post = new ProductChannelPostEntity();
        post.setRubric(ProductPostRubric.FEATURE);
        post.setDraftText(text);
        post.setFinalText(text);
        post.setTelegramMessageId(messageId);
        post.setStatus(ProductChannelPostStatus.PUBLISHED);
        post.setPublishedAt(Instant.now());
        post.setCreatedByTelegramId(0L);
        ChannelEntity linkSource = new ChannelEntity();
        linkSource.setTelegramChatId(channel.chatId());
        linkSource.setUsername(channel.username());
        post.setPostLink(ChannelPublishService.buildPostLink(linkSource, messageId));
        postRepository.save(post);

        log.info("Product channel bootstrap welcome published: messageId={}", messageId);
        return PublishOutcome.success(post.getId(), messageId, post.getPostLink());
    }

    private String buildPrompt(ProductPostRubric rubric, String brainContext, String extraContext) {

        String ctx = extraContext != null && !extraContext.isBlank()

                ? "\nКонтекст от редактора: " + extraContext

                : "";

        String base = brainContext + "\n\nРубрика: " + rubric.label() + "\n" + rubric.hint() + ctx + "\n";

        String cta = "\nCTA (одна строка в конце): " + properties.getBotLink();



        return switch (rubric) {

            case MORNING -> base + """

                    Формат «утро команды Pulse» (НЕ live-видео):
                    • Одна строка: день + что сегодня в фокусе продукта
                    • 2 пункта прогресса / тестов (конкретно: что сделали или проверяем)
                    • 1 вопрос или микро-CTA: что полезно читателю-админу сегодня
                    • Связь с ассистентом: лиды / план контента / реклама — по делу
                    500–750 символов.""" + cta;

            case PROMO -> base + """

                    Акция для подписчиков канала: бесплатный разбор, бонусы пакета, ограничение по времени/местам.

                    Честно, без фейкового дефицита. 400–700 символов.""" + cta;

            case FEATURE -> base + """

                    Одна фича бота: разбор по секциям, идеи, жёсткий аудит, разбор поста, пакеты с бонусами.

                    Покажи пользу на примере «админ канала X». 500–800 символов.""" + cta;

            case DEMO -> base + """

                    Демо ценности анализа без выдуманных цифр — общие формулировки про охваты и идеи.

                    600–900 символов.""" + cta;

            case INSIGHT -> base + """

                    Один сильный инсайт про Telegram-контент (время, hook, форматы). Без воды. 400–700 символов.""" + cta;

            case NEWS_DAY -> base + """

                    «День в Telegram»: если в проверенных источниках нет факта — напиши инсайт из практики бота,

                    НЕ выдумывай новости. Можно: тренд формата, изменение привычек аудитории. 450–750 символов.""" + cta;

            case HOWTO -> base + """

                    3 шага: ссылка на канал → отчёт за 1–3 мин → идеи и черновик. 500–800 символов.""" + cta;

            case CHANGELOG -> base + """

                    Патчноут в стиле игрового апдейта:
                    Заголовок: 🛠Обновление X.Y.Z
                    Подзаголовок — коротко о сути для админа канала
                    Буллеты ▪️ — только польза и интрига из ФАКТОВ.
                    ЗАПРЕЩЕНО: схемы, роли, прокси, лимиты, названия внутренних модулей.
                    500–1200 символов.""" + cta;

            case COMMUNITY -> base + """

                    Мягко: канал = халява и демо; закрытый чат/подписка = обсуждения и ранняя доступность.

                    Без давления. 400–650 символов.""" + cta;

            case CASE -> base + """

                    Мини-кейс обобщённо: было/стало после разбора и идей. Без фейковых имён. 500–800 символов.""" + cta;

            case FEATURE_VOTE -> base + """

                    Голосование за следующую фичу Pulse Ассистента (2–3 варианта).
                    Тон: «мы строим с вами». Без манипуляций. 400–700 символов.
                    В конце — чёткие варианты ответа в комментариях.""" + cta;

        };

    }



    private static String fallback(ProductPostRubric rubric) {

        return rubric.label() + "\n\nPulse AI разбирает Telegram-каналы за пару минут: метрики, 5 секций разбора, идеи и черновики.\n\nПопробуйте бесплатно: @Pulsse_AI_bot";

    }



    public record ResolvedChannel(long chatId, String username) {

    }



    public record ChannelReadiness(boolean ready, String message, ResolvedChannel channel) {

        public static ChannelReadiness ok(ResolvedChannel channel) {

            return new ChannelReadiness(true, null, channel);

        }



        public static ChannelReadiness blocked(String message) {

            return new ChannelReadiness(false, message, null);

        }

    }



    public record PublishOutcome(boolean success, Long postId, Integer messageId, String link, String error) {

        public static PublishOutcome success(long postId, int messageId, String link) {

            return new PublishOutcome(true, postId, messageId, link, null);

        }



        public static PublishOutcome failure(String error) {

            return new PublishOutcome(false, null, null, null, error);

        }

    }

}


