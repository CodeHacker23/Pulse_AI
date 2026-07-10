package org.example.pulse_ai.session;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserSessionService {

    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();

    public UserSession getOrCreate(long chatId) {
        return sessions.computeIfAbsent(chatId, UserSession::new);
    }

    public void setState(long chatId, BotState state) {
        getOrCreate(chatId).setState(state);
    }

    public void resetToMainMenu(long chatId) {
        UserSession session = getOrCreate(chatId);
        session.clearFlow();
        session.setState(BotState.MAIN_MENU);
    }
}
