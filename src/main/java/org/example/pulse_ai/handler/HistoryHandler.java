package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.BotMessages;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class HistoryHandler {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Europe/Moscow"));

    private final AnalysisRequestRepository analysisRequestRepository;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public void showHistory(long chatId, UserEntity user) {
        List<AnalysisRequestEntity> requests =
                analysisRequestRepository.findTop10ByUserIdOrderByCreatedAtDesc(user.getId());

        if (requests.isEmpty()) {
            messageSender.sendText(
                    chatId,
                    """
                            📁 История запросов

                            Пока пусто. Сделайте первый анализ — он сохранится здесь.""",
                    keyboards.mainMenuKeyboard()
            );
            return;
        }

        StringBuilder text = new StringBuilder("📁 История запросов\n\n");
        for (int i = 0; i < requests.size(); i++) {
            AnalysisRequestEntity request = requests.get(i);
            text.append(i + 1)
                    .append(". #")
                    .append(request.getId())
                    .append(" · ")
                    .append(DATE_FORMAT.format(request.getCreatedAt()))
                    .append(" · ")
                    .append(request.getType())
                    .append(" · ")
                    .append(request.getStatus())
                    .append('\n');
        }
        text.append("\nПоказаны последние 10 запросов.");

        messageSender.sendText(chatId, text.toString(), keyboards.mainMenuKeyboard());
    }
}
