package com.example.webrtc.handler;

import com.example.webrtc.registry.UserRegistry;
import com.example.webrtc.registry.UserSession;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Component
public class WebRtcHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WebRtcHandler.class);
    private static final Gson gson = new GsonBuilder().create();

    private final KurentoClient kurentoClient;
    private final UserRegistry registry;

    public WebRtcHandler(KurentoClient kurentoClient, UserRegistry registry) {
        this.kurentoClient = kurentoClient;
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 연결됨: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
        String messageId = jsonMessage.get("id").getAsString();

        log.info("메시지 수신 [{}]: {}", session.getId(), messageId);

        try {
            switch (messageId) {
                case "start" -> handleStart(session, jsonMessage);
                case "stop" -> handleStop(session);
                case "onIceCandidate" -> handleIceCandidate(session, jsonMessage);
                default -> sendError(session, "Invalid message id: " + messageId);
            }
        } catch (Throwable t) {
            log.error("에러 발생", t);
            sendError(session, t.getMessage());
        }
    }

    private void handleStart(WebSocketSession session, JsonObject jsonMessage) throws IOException {
        // 기존 세션 정리
        if (registry.exists(session)) {
            registry.removeBySession(session);
        }

        // 새 세션 생성
        UserSession user = new UserSession(session);
        registry.register(user);

        // 미디어 파이프라인 생성
        MediaPipeline pipeline = kurentoClient.createMediaPipeline();
        user.setMediaPipeline(pipeline);

        // WebRTC 엔드포인트 생성
        WebRtcEndpoint webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline).build();
        user.setWebRtcEndpoint(webRtcEndpoint);

        // 루프백 연결 (자신의 비디오를 다시 받음 - 테스트용)
        webRtcEndpoint.connect(webRtcEndpoint);

        // ICE 후보 이벤트 리스너
        webRtcEndpoint.addIceCandidateFoundListener(event -> {
            JsonObject response = new JsonObject();
            response.addProperty("id", "iceCandidate");
            response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(response.toString()));
                }
            } catch (IOException e) {
                log.error("ICE 후보 전송 실패", e);
            }
        });

        // SDP Offer 처리
        String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
        String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

        // SDP Answer 전송
        JsonObject response = new JsonObject();
        response.addProperty("id", "startResponse");
        response.addProperty("sdpAnswer", sdpAnswer);
        sendMessage(session, response.toString());

        // ICE 수집 시작
        webRtcEndpoint.gatherCandidates();

        log.info("WebRTC 세션 시작됨: {}", session.getId());
    }

    private void handleStop(WebSocketSession session) {
        UserSession user = registry.removeBySession(session);
        if (user != null) {
            log.info("WebRTC 세션 종료됨: {}", session.getId());
        }
    }

    private void handleIceCandidate(WebSocketSession session, JsonObject jsonMessage) {
        UserSession user = registry.getBySession(session);
        if (user != null && user.getWebRtcEndpoint() != null) {
            JsonObject candidateJson = jsonMessage.getAsJsonObject("candidate");
            IceCandidate candidate = new IceCandidate(
                    candidateJson.get("candidate").getAsString(),
                    candidateJson.get("sdpMid").getAsString(),
                    candidateJson.get("sdpMLineIndex").getAsInt()
            );
            user.getWebRtcEndpoint().addIceCandidate(candidate);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket 연결 종료: {} - {}", session.getId(), status);
        registry.removeBySession(session);
    }

    private void sendMessage(WebSocketSession session, String message) throws IOException {
        synchronized (session) {
            session.sendMessage(new TextMessage(message));
        }
    }

    private void sendError(WebSocketSession session, String message) {
        try {
            JsonObject response = new JsonObject();
            response.addProperty("id", "error");
            response.addProperty("message", message);
            sendMessage(session, response.toString());
        } catch (IOException e) {
            log.error("에러 메시지 전송 실패", e);
        }
    }
}
