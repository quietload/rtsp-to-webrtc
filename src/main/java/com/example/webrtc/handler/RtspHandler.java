package com.example.webrtc.handler;

import com.example.webrtc.registry.RtspSession;
import com.example.webrtc.registry.RtspSessionRegistry;
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
public class RtspHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RtspHandler.class);
    private static final Gson gson = new GsonBuilder().create();

    private final KurentoClient kurentoClient;
    private final RtspSessionRegistry registry;

    public RtspHandler(KurentoClient kurentoClient, RtspSessionRegistry registry) {
        this.kurentoClient = kurentoClient;
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("RTSP WebSocket 연결됨: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
        String messageId = jsonMessage.get("id").getAsString();

        log.info("RTSP 메시지 수신 [{}]: {}", session.getId(), messageId);

        try {
            switch (messageId) {
                case "start" -> handleStart(session, jsonMessage);
                case "stop" -> handleStop(session);
                case "onIceCandidate" -> handleIceCandidate(session, jsonMessage);
                default -> sendError(session, "Invalid message id: " + messageId);
            }
        } catch (Throwable t) {
            log.error("RTSP 처리 에러", t);
            sendError(session, t.getMessage());
        }
    }

    private void handleStart(WebSocketSession session, JsonObject jsonMessage) throws IOException {
        String rtspUrl = jsonMessage.get("rtspUrl").getAsString();
        log.info("RTSP 스트림 시작 요청: {}", rtspUrl);

        // 기존 세션 정리
        if (registry.exists(session)) {
            registry.removeBySession(session);
        }

        // 새 세션 생성
        RtspSession rtspSession = new RtspSession(session);
        registry.register(rtspSession);

        // 미디어 파이프라인 생성
        MediaPipeline pipeline = kurentoClient.createMediaPipeline();
        rtspSession.setMediaPipeline(pipeline);

        // PlayerEndpoint 생성 (RTSP 소스)
        PlayerEndpoint player = new PlayerEndpoint.Builder(pipeline, rtspUrl).build();
        rtspSession.setPlayerEndpoint(player);

        // WebRTC 엔드포인트 생성
        WebRtcEndpoint webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline).build();
        rtspSession.setWebRtcEndpoint(webRtcEndpoint);

        // Player -> WebRTC 연결
        player.connect(webRtcEndpoint);

        // Player 이벤트 리스너
        player.addErrorListener(event -> {
            log.error("Player 에러: {}", event.getDescription());
            sendError(session, "RTSP 스트림 에러: " + event.getDescription());
        });

        player.addEndOfStreamListener(event -> {
            log.info("RTSP 스트림 종료");
            JsonObject response = new JsonObject();
            response.addProperty("id", "streamEnded");
            try {
                sendMessage(session, response.toString());
            } catch (IOException e) {
                log.error("스트림 종료 알림 실패", e);
            }
        });

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

        // RTSP 재생 시작
        player.play();

        log.info("RTSP 스트림 재생 시작: {}", rtspUrl);
    }

    private void handleStop(WebSocketSession session) {
        RtspSession rtspSession = registry.removeBySession(session);
        if (rtspSession != null) {
            log.info("RTSP 세션 종료됨: {}", session.getId());
        }
    }

    private void handleIceCandidate(WebSocketSession session, JsonObject jsonMessage) {
        RtspSession rtspSession = registry.getBySession(session);
        if (rtspSession != null && rtspSession.getWebRtcEndpoint() != null) {
            JsonObject candidateJson = jsonMessage.getAsJsonObject("candidate");
            IceCandidate candidate = new IceCandidate(
                    candidateJson.get("candidate").getAsString(),
                    candidateJson.get("sdpMid").getAsString(),
                    candidateJson.get("sdpMLineIndex").getAsInt()
            );
            rtspSession.getWebRtcEndpoint().addIceCandidate(candidate);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("RTSP WebSocket 연결 종료: {} - {}", session.getId(), status);
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
