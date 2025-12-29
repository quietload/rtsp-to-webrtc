package com.example.webrtc.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;

/**
 * RTSP 세션 레지스트리
 * 
 * <p>활성 RTSP 스트리밍 세션을 관리하고 리소스 해제를 담당</p>
 */
@Component
public class RtspSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RtspSessionRegistry.class);

    private final ConcurrentHashMap<String, RtspSession> sessions = new ConcurrentHashMap<>();

    /**
     * 세션 등록
     */
    public void register(RtspSession session) {
        sessions.put(session.getSession().getId(), session);
        log.debug("세션 등록: {} (활성: {})", session.getSession().getId(), sessions.size());
    }

    /**
     * WebSocket 세션으로 RTSP 세션 조회
     */
    public RtspSession getBySession(WebSocketSession session) {
        return sessions.get(session.getId());
    }

    /**
     * 세션 제거 및 리소스 해제
     */
    public RtspSession removeBySession(WebSocketSession session) {
        RtspSession rtspSession = sessions.remove(session.getId());
        if (rtspSession != null) {
            rtspSession.release();
            log.debug("세션 제거: {} (활성: {})", session.getId(), sessions.size());
        }
        return rtspSession;
    }

    /**
     * 세션 존재 여부 확인
     */
    public boolean exists(WebSocketSession session) {
        return sessions.containsKey(session.getId());
    }

    /**
     * 활성 세션 수 조회
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }
}
