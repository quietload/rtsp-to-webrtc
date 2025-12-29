package com.example.webrtc.registry;

import org.kurento.client.MediaPipeline;
import org.kurento.client.PlayerEndpoint;
import org.kurento.client.WebRtcEndpoint;
import org.springframework.web.socket.WebSocketSession;

/**
 * RTSP 스트리밍 세션 정보
 * 
 * <p>WebSocket 세션과 Kurento 미디어 요소를 관리</p>
 */
public class RtspSession {

    private final WebSocketSession session;
    private MediaPipeline mediaPipeline;
    private PlayerEndpoint playerEndpoint;
    private WebRtcEndpoint webRtcEndpoint;
    private String rtspUrl;
    private String profile;

    public RtspSession(WebSocketSession session) {
        this.session = session;
    }

    // Getters & Setters

    public WebSocketSession getSession() {
        return session;
    }

    public MediaPipeline getMediaPipeline() {
        return mediaPipeline;
    }

    public void setMediaPipeline(MediaPipeline mediaPipeline) {
        this.mediaPipeline = mediaPipeline;
    }

    public PlayerEndpoint getPlayerEndpoint() {
        return playerEndpoint;
    }

    public void setPlayerEndpoint(PlayerEndpoint playerEndpoint) {
        this.playerEndpoint = playerEndpoint;
    }

    public WebRtcEndpoint getWebRtcEndpoint() {
        return webRtcEndpoint;
    }

    public void setWebRtcEndpoint(WebRtcEndpoint webRtcEndpoint) {
        this.webRtcEndpoint = webRtcEndpoint;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    /**
     * 미디어 리소스 해제
     */
    public void release() {
        if (playerEndpoint != null) {
            try {
                playerEndpoint.stop();
                playerEndpoint.release();
            } catch (Exception ignored) {}
        }
        if (webRtcEndpoint != null) {
            try {
                webRtcEndpoint.release();
            } catch (Exception ignored) {}
        }
        if (mediaPipeline != null) {
            try {
                mediaPipeline.release();
            } catch (Exception ignored) {}
        }
    }
}
