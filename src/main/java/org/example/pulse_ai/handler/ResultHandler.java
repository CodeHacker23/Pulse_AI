package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.analysis.AnalysisSnapshotService;
import org.example.pulse_ai.domain.analysis.DeepAnalysisSections;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.example.pulse_ai.visual.AnalysisChartPack;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResultHandler {

    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final UserSessionService sessionService;
    private final ResultCallbackHandler resultCallbackHandler;
    private final AnalysisSnapshotService snapshotService;

    public void sendProgress(long chatId, AnalysisRequestEntity request, String text) {
        messageSender.sendText(chatId, text + "\n\nЗапрос #" + request.getId() + " · " + request.getProgressPercent() + "%");
    }

    public void sendProgressSafe(long chatId, AnalysisRequestEntity request, String text) {
        messageSender.sendTextSafe(chatId, text + "\n\nЗапрос #" + request.getId() + " · " + request.getProgressPercent() + "%");
    }

    public void sendFailure(long chatId, String message) {
        messageSender.sendTextWithInlineSafe(chatId, "❌ " + TgHtml.esc(message), keyboards.backToMainInline());
        sessionService.resetToMainMenu(chatId);
    }

    /** Первое сообщение: 2 графика, без простыни текста. */
    public void deliverQuickStats(long chatId, Long requestId, ChannelEntity channel, AnalysisChartPack charts) {
        sessionService.setState(chatId, BotState.REQUEST_RESULT);
        var session = sessionService.getOrCreate(chatId);
        session.setRequestId(requestId);
        session.setLastRequestId(requestId);
        session.setFreeDraftsUsed(0);

        String caption = "📊 <b>" + TgHtml.esc(channel.getTitle()) + "</b>";

        List<TelegramMessageSender.AlbumPhoto> album = List.of(
                new TelegramMessageSender.AlbumPhoto(charts.dashboard(), caption),
                new TelegramMessageSender.AlbumPhoto(charts.viewsTrend(), "📈 <b>Просмотры по дням</b>")
        );

        if (!messageSender.sendPhotoAlbumSafe(chatId, album)) {
            messageSender.sendPhotoSafe(chatId, charts.dashboard(), caption);
            messageSender.sendPhotoSafe(chatId, charts.viewsTrend(), "📈 <b>Просмотры по дням</b>");
        }
    }

    public void deliverDeepAnalysis(long chatId, long requestId, String analysis) {
        deliverDeepAnalysisSections(chatId, requestId, analysis, false);
    }

    public void deliverDeepAnalysisTeaser(long chatId, long requestId, String analysis) {
        deliverDeepAnalysisSections(chatId, requestId, analysis, true);
    }

    private void deliverDeepAnalysisSections(long chatId, long requestId, String analysis, boolean teaserMode) {
        List<DeepAnalysisSections.Section> sections = snapshotService.getSections(requestId);
        if (sections.isEmpty()) {
            sections = DeepAnalysisSections.parse(analysis);
        }
        if (sections.isEmpty()) {
            messageSender.sendTextSafe(chatId, "🧠 <b>Разбор канала</b>\n\n" + TgHtml.fromMarkdown(analysis.trim()));
            return;
        }
        resultCallbackHandler.sendSectionMessage(chatId, requestId, sections, teaserMode);
    }

    /** Анализ завершён — оставляем requestId для навигации по разделам отчёта. */
    public void finishSession(long chatId) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.REQUEST_RESULT);
    }
}
