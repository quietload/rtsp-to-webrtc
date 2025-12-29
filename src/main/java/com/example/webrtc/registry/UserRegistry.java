package com.example.webrtc.registry;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class UserRegistry {

    private final ConcurrentHashMap<String, UserSession> usersBySessionId = new ConcurrentHashMap<>();

    public void register(UserSession user) {
        usersBySessionId.put(user.getSession().getId(), user);
    }

    public UserSession getBySession(WebSocketSession session) {
        return usersBySessionId.get(session.getId());
    }

    public UserSession removeBySession(WebSocketSession session) {
        UserSession user = usersBySessionId.remove(session.getId());
        if (user != null) {
            user.release();
        }
        return user;
    }

    public boolean exists(WebSocketSession session) {
        return usersBySessionId.containsKey(session.getId());
    }
}
