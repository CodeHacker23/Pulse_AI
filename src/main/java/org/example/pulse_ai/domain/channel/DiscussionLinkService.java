package org.example.pulse_ai.domain.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.telegram.TelegramBotApiService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Надёжная связка канал → группа обсуждений (комменты).
 * Источники: сохранённый linked_discussion_chat_id, GetChat.linkedChatId, авто-форвард в CommentLeadAgent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscussionLinkService {

    private final ChannelRepository channelRepository;
    private final TelegramBotApiService botApi;

    @Transactional
    public Long resolveDiscussionChatId(ChannelEntity channel) {
        if (channel == null) {
            return null;
        }
        if (channel.getLinkedDiscussionChatId() != null) {
            return channel.getLinkedDiscussionChatId();
        }
        if (channel.getTelegramChatId() == null) {
            return null;
        }
        return botApi.getChat(channel.getTelegramChatId())
                .map(chat -> chat.getLinkedChatId())
                .map(linkedId -> {
                    channel.setLinkedDiscussionChatId(linkedId);
                    channelRepository.save(channel);
                    log.info("Resolved discussion {} for channel {}", linkedId, channel.getId());
                    return linkedId;
                })
                .orElse(null);
    }

    @Transactional
    public void rememberLinkage(Long channelTelegramId, long discussionGroupId) {
        if (channelTelegramId == null) {
            return;
        }
        channelRepository.findByTelegramChatId(channelTelegramId).ifPresent(channel -> {
            if (!java.util.Objects.equals(channel.getLinkedDiscussionChatId(), discussionGroupId)) {
                channel.setLinkedDiscussionChatId(discussionGroupId);
                channelRepository.save(channel);
                log.info("Linked discussion group {} to channel {}", discussionGroupId, channel.getId());
            }
        });
    }
}
