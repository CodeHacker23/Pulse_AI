package org.example.pulse_ai.telegram;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Creates "live" progress messages in Telegram: a single message that is edited
 * on a timer so the clock emoji keeps spinning while a long operation runs.
 * This makes it obvious to the user that the bot is working and did not freeze.
 */
@Service
@RequiredArgsConstructor
public class LiveProgressService {

    private static final String[] CLOCK_FRAMES = {
            "🕐", "🕑", "🕒", "🕓", "🕔", "🕕", "🕖", "🕗", "🕘", "🕙", "🕚", "🕛"
    };
    private static final long TICK_MS = 1500;
    private static final int BAR_LENGTH = 10;

    /** Fixed analysis pipeline: short label (for the chain), gerund phrase (active line) and % for the bar. */
    private static final String[] STAGE_LABELS = {
            "сбор постов", "метрики", "внешние площадки", "разбор", "идеи"
    };
    private static final String[] STAGE_PHRASES = {
            "Собираю посты и просмотры…",
            "Считаю метрики и вовлечённость…",
            "Собираю данные с TGStat / Telemetr / Telega.in…",
            "Анализирую стиль, подачу и просадки просмотров…",
            "Готовлю рекомендации и идеи контента…"
    };
    private static final int[] STAGE_PERCENTS = {10, 30, 45, 65, 85};

    private final TelegramMessageSender messageSender;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "live-progress");
                thread.setDaemon(true);
                return thread;
            });

    /** Starts a live progress message. Returns a handle even if the send failed (it becomes a no-op). */
    public LiveProgress start(long chatId, String header) {
        Integer messageId = messageSender.sendReturningMessageId(chatId, header + "\n\n" + CLOCK_FRAMES[0] + " Запускаю…");
        LiveProgress progress = new LiveProgress(chatId, messageId, header);
        if (messageId != null) {
            progress.future = scheduler.scheduleAtFixedRate(
                    () -> tick(progress), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        }
        return progress;
    }

    private void tick(LiveProgress progress) {
        if (progress.messageId == null) {
            return;
        }
        progress.frame = (progress.frame + 1) % CLOCK_FRAMES.length;
        messageSender.editTextSafe(progress.chatId, progress.messageId, render(progress));
    }

    private String render(LiveProgress progress) {
        StringBuilder sb = new StringBuilder(progress.header).append("\n\n");
        sb.append(CLOCK_FRAMES[progress.frame]).append(' ').append(progress.stage).append('\n');
        sb.append(bar(progress.percent)).append(' ').append(progress.percent).append('%').append("\n\n");
        sb.append(pipeline(progress.step)).append("\n\n");
        sb.append("Обычно занимает 1–3 минуты. Не закрывайте чат.");
        return sb.toString();
    }

    private static String pipeline(int currentStep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < STAGE_LABELS.length; i++) {
            if (i > 0) {
                sb.append(" → ");
            }
            if (i < currentStep) {
                sb.append("✅ ").append(STAGE_LABELS[i]);
            } else if (i == currentStep) {
                sb.append("🔸 ").append(STAGE_LABELS[i]);
            } else {
                sb.append(STAGE_LABELS[i]);
            }
        }
        return sb.toString();
    }

    private static String bar(int percent) {
        int filled = Math.max(0, Math.min(BAR_LENGTH, Math.round(percent / (100f / BAR_LENGTH))));
        return "▰".repeat(filled) + "▱".repeat(BAR_LENGTH - filled);
    }

    /** Handle returned to callers to advance the stage or finish the live message. */
    public class LiveProgress {
        private final long chatId;
        private final Integer messageId;
        private final String header;
        private volatile String stage = "Запускаю…";
        private volatile int percent = 0;
        private volatile int step = 0;
        private volatile int frame = 0;
        private ScheduledFuture<?> future;

        private LiveProgress(long chatId, Integer messageId, String header) {
            this.chatId = chatId;
            this.messageId = messageId;
            this.header = header;
        }

        /** Advances the pipeline to the given step (0-based) using its predefined phrase and percent. */
        public void step(int index) {
            int clamped = Math.max(0, Math.min(STAGE_LABELS.length - 1, index));
            this.step = clamped;
            this.stage = STAGE_PHRASES[clamped];
            this.percent = STAGE_PERCENTS[clamped];
            if (messageId != null) {
                messageSender.editTextSafe(chatId, messageId, render(this));
            }
        }

        /** Удаляет сообщение прогресса без финального текста. */
        public void dismiss() {
            stopTicker();
            if (messageId != null) {
                messageSender.deleteMessageSafe(chatId, messageId);
            }
        }

        /** Stops the animation and replaces the message with a final one-line status. */
        public void finish(String finalText) {
            stopTicker();
            if (messageId != null) {
                messageSender.editTextSafe(chatId, messageId, finalText);
            }
        }

        /** Stops the animation without touching the message text (used on failure paths). */
        public void stop() {
            stopTicker();
        }

        private void stopTicker() {
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
