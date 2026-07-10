package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.domain.channel.ChannelConnectException;
import org.example.pulse_ai.domain.channel.ChannelService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.stats.ChannelSyncService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.BotMessages;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;

@Component
@RequiredArgsConstructor
public class ChannelHandler {

    private final ChannelService channelService;
    private final ChannelSyncService channelSyncService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public void showConnectInstructions(long chatId) {
        sessionService.setState(chatId, BotState.CONNECT_CHANNEL);
        messageSender.sendTextWithInline(chatId, BotMessages.CONNECT_CHANNEL, keyboards.backToMainInline());
    }

    public void handleConnectInput(long chatId, UserEntity user, Message message) {
        try {
            ChannelEntity channel;
            if (message.getForwardFromChat() != null) {
                channel = channelService.connectFromForward(user, message);
            } else if (message.hasText()) {
                channel = channelService.connectPublicChannel(user, message.getText().trim());
            } else {
                messageSender.sendText(chatId, "Отправьте ссылку на канал или @username (например, https://t.me/durov).");
                return;
            }

            sessionService.setState(chatId, BotState.CHANNEL_CONNECTED);
            sessionService.getOrCreate(chatId).setChannelId(channel.getId());

            ChannelSyncService.SyncResult sync = channelSyncService.syncChannel(channel);
            int subscribers = channel.getSubscriberCount() != null ? channel.getSubscriberCount() : 0;

            messageSender.sendTextWithInline(
                    chatId,
                    BotMessages.channelConnected(channel.getTitle(), subscribers, sync.totalPosts()),
                    keyboards.channelConnectedInline()
            );
        } catch (ChannelConnectException ex) {
            messageSender.sendTextWithInline(
                    chatId,
                    BotMessages.channelConnectFailed(ex.getMessage()),
                    keyboards.backToMainInline()
            );
        }
    }
}
