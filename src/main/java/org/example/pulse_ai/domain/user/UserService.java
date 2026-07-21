package org.example.pulse_ai.domain.user;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.domain.channel.ConnectionStatus;
import org.example.pulse_ai.persistence.entity.BalanceTransactionEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.BalanceTransactionRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final ChannelRepository channelRepository;
    private final BalanceTransactionRepository balanceTransactionRepository;

    @Transactional
    public UserEntity findOrCreate(User telegramUser) {
        return userRepository.findByTelegramId(telegramUser.getId())
                .map(existing -> updateProfile(existing, telegramUser))
                .orElseGet(() -> createUser(telegramUser));
    }

    @Transactional(readOnly = true)
    public Optional<UserEntity> findByTelegramId(long telegramId) {
        return userRepository.findByTelegramId(telegramId);
    }

    @Transactional(readOnly = true)
    public Optional<ChannelEntity> findActiveChannel(UserEntity user) {
        if (user.getActiveChannelId() == null) {
            return Optional.empty();
        }
        return channelRepository.findById(user.getActiveChannelId())
                .filter(channel -> channel.getConnectionStatus() == ConnectionStatus.ACTIVE);
    }

    @Transactional
    public void chargeRequest(UserEntity user, Long requestId) {
        if (user.getBalance() <= 0) {
            throw new IllegalStateException("Недостаточно запросов на балансе");
        }
        user.setBalance(user.getBalance() - 1);
        user.setTotalRequests(user.getTotalRequests() + 1);
        userRepository.save(user);
        recordBalanceChange(user.getId(), -1, user.getBalance(), "request_charge", requestId);
    }

    @Transactional
    public void refundRequest(Long userId, Long requestId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setBalance(user.getBalance() + 1);
            userRepository.save(user);
            recordBalanceChange(userId, 1, user.getBalance(), "request_refund", requestId);
        });
    }

    @Transactional
    public void creditBalance(Long userId, int amount, String reason, Long referenceId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setBalance(user.getBalance() + amount);
            userRepository.save(user);
            recordBalanceChange(userId, amount, user.getBalance(), reason, referenceId);
        });
    }

    private void recordBalanceChange(Long userId, int delta, int balanceAfter, String reason, Long referenceId) {
        BalanceTransactionEntity tx = new BalanceTransactionEntity();
        tx.setUserId(userId);
        tx.setDelta(delta);
        tx.setBalanceAfter(balanceAfter);
        tx.setReason(reason);
        tx.setReferenceId(referenceId);
        balanceTransactionRepository.save(tx);
    }

    @Transactional
    public void markFreeAnalysisUsed(UserEntity user) {
        user.setFreeAnalysisUsed(true);
        user.setTotalRequests(user.getTotalRequests() + 1);
        userRepository.save(user);
    }

    @Transactional
    public void markFreeAnalysisUsedById(Long userId) {
        userRepository.findById(userId).ifPresent(this::markFreeAnalysisUsed);
    }

    @Transactional
    public void addIdeasReceived(Long userId, int count) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setTotalIdeasReceived(user.getTotalIdeasReceived() + count);
            userRepository.save(user);
        });
    }

    private UserEntity updateProfile(UserEntity user, User telegramUser) {
        user.setUsername(telegramUser.getUserName());
        user.setFirstName(telegramUser.getFirstName());
        user.setLastName(telegramUser.getLastName());
        if (telegramUser.getLanguageCode() != null) {
            user.setLanguageCode(telegramUser.getLanguageCode());
        }
        user.setLastActiveAt(Instant.now());
        return userRepository.save(user);
    }

    private UserEntity createUser(User telegramUser) {
        UserEntity user = new UserEntity();
        user.setTelegramId(telegramUser.getId());
        user.setUsername(telegramUser.getUserName());
        user.setFirstName(telegramUser.getFirstName());
        user.setLastName(telegramUser.getLastName());
        user.setLanguageCode(telegramUser.getLanguageCode() != null ? telegramUser.getLanguageCode() : "ru");
        return userRepository.save(user);
    }
}
