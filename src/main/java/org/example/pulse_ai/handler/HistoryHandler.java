package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.analysis.AnalysisSnapshotService;
import org.example.pulse_ai.domain.analysis.DeepAnalysisSections;
import org.example.pulse_ai.domain.request.RequestStatus;
import org.example.pulse_ai.domain.request.RequestType;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class HistoryHandler {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Europe/Moscow"));

    private final AnalysisRequestRepository analysisRequestRepository;
    private final ChannelRepository channelRepository;
    private final AnalysisSnapshotService snapshotService;
    private final ResultCallbackHandler resultCallbackHandler;
    private final UserSessionService sessionService;
    private final PulseBillingProperties billingProperties;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public void showHistory(long chatId, UserEntity user) {
        List<AnalysisRequestEntity> requests =
                analysisRequestRepository.findTop10ByUserIdOrderByCreatedAtDesc(user.getId());

        if (requests.isEmpty()) {
            messageSender.sendTextWithInline(
                    chatId,
                    """
                            📁 <b>Мои отчёты</b>

                            Пока пусто.

                            Пришлите ссылку на канал — первый разбор сохранится здесь.""",
                    keyboards.backToMainInline()
            );
            return;
        }

        StringBuilder text = new StringBuilder();
        text.append("📁 <b>Мои отчёты</b>\n\n");
        text.append("<i>Нажмите «Открыть» — вернётесь к разбору и идеям.</i>\n\n");

        List<Long> openableIds = new ArrayList<>();
        int n = 1;
        for (AnalysisRequestEntity request : requests) {
            String channelTitle = channelRepository.findById(request.getChannelId())
                    .map(ChannelEntity::getTitle)
                    .orElse("Канал");
            String typeLabel = request.getType() == RequestType.FREE ? "пробный" : "полный";
            String statusIcon = statusIcon(request.getStatus());

            text.append(n++).append(". ").append(statusIcon).append(' ');
            text.append(TgHtml.b(channelTitle));
            text.append(" · ").append(DATE_FORMAT.format(request.getCreatedAt()));
            text.append(" · ").append(typeLabel).append('\n');

            if (request.getStatus() == RequestStatus.COMPLETED) {
                openableIds.add(request.getId());
            }
        }

        InlineKeyboardMarkup keyboard = openableIds.isEmpty()
                ? keyboards.backToMainInline()
                : keyboards.historyListInline(openableIds);

        messageSender.sendTextWithInline(chatId, text.toString().trim(), keyboard);
    }

    public void openReport(long chatId, int messageId, UserEntity user, long requestId) {
        AnalysisRequestEntity request = analysisRequestRepository.findById(requestId).orElse(null);
        if (request == null || !request.getUserId().equals(user.getId())) {
            messageSender.sendTextSafe(chatId, "❌ Отчёт не найден.");
            return;
        }
        if (request.getStatus() != RequestStatus.COMPLETED) {
            messageSender.sendTextSafe(chatId, "⏳ Этот отчёт ещё не готов или завершился с ошибкой.");
            return;
        }

        List<DeepAnalysisSections.Section> sections = snapshotService.getSections(requestId);
        if (sections.isEmpty()) {
            messageSender.sendTextSafe(chatId, "❌ В отчёте нет сохранённого разбора.");
            return;
        }

        boolean teaser = billingProperties.isEnabled() && request.getType() == RequestType.FREE;
        sessionService.getOrCreate(chatId).setState(BotState.REQUEST_RESULT);
        sessionService.getOrCreate(chatId).setRequestId(requestId);

        if (messageId > 0) {
            resultCallbackHandler.editSectionMessage(chatId, messageId, requestId, sections, 0, teaser);
        } else {
            resultCallbackHandler.sendSectionMessage(chatId, requestId, sections, teaser);
        }
    }

    private static String statusIcon(RequestStatus status) {
        return switch (status) {
            case COMPLETED -> "✅";
            case FAILED, CANCELLED -> "❌";
            case PENDING, COLLECTING_STATS, ANALYZING, GENERATING_IDEAS, GENERATING_POSTS -> "⏳";
        };
    }
}
