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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * RTSP to WebRTC 스트리밍 핸들러
 * 
 * <p>프로토콜 특징:</p>
 * <ul>
 *   <li>WebSocket URL 쿼리 파라미터로 RTSP URL 전달</li>
 *   <li>JSON 메시지 교환</li>
 *   <li>클라이언트가 Offer, 서버가 Answer</li>
 *   <li>Profile에 따른 해상도 트랜스코딩 지원 (FHD, HD, D1, CIF)</li>
 * </ul>
 * 
 * <p>WebSocket 엔드포인트: /stream?url={rtspUrl}&profile={profile}</p>
 * 
 * <p>메시지 프로토콜:</p>
 * <pre>
 * 클라이언트 → 서버:
 *   { "id": "start", "sdpOffer": "..." }
 *   { "id": "stop" }
 *   { "id": "onIceCandidate", "candidate": {...} }
 * 
 * 서버 → 클라이언트:
 *   { "id": "startResponse", "sdpAnswer": "..." }
 *   { "id": "iceCandidate", "candidate": {...} }
 *   { "id": "error", "message": "..." }
 *   { "id": "streamEnded" }
 * </pre>
 */
@Component
public class StreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamHandler.class);
    private static final Gson gson = new GsonBuilder().create();

    /** Profile별 해상도 설정 (width, height) */
    private static final Map<String, int[]> PROFILE_RESOLUTIONS = Map.of(
        "FHD", new int[]{1920, 1080},
        "HD",  new int[]{1280, 720},
        "D1",  new int[]{720, 480},
        "CIF", new int[]{352, 288}
    );

    private final KurentoClient kurentoClient;
    private final RtspSessionRegistry registry;

    @Value("${stun.server.url:}")
    private String stunServerUrl;

    public StreamHandler(KurentoClient kurentoClient, RtspSessionRegistry registry) {
        this.kurentoClient = kurentoClient;
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null) {
            log.error("WebSocket URI is null");
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        // 쿼리 파라미터 추출
        Map<String, String> params = UriComponentsBuilder.fromUri(uri).build()
                .getQueryParams().toSingleValueMap();
        
        String rtspUrl = params.get("url");
        String profile = params.get("profile");

        // URL 디코딩
        if (rtspUrl != null) {
            rtspUrl = URLDecoder.decode(rtspUrl, StandardCharsets.UTF_8);
        }

        if (rtspUrl == null || rtspUrl.isEmpty()) {
            log.error("RTSP URL이 누락됨");
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        log.info("연결됨 [{}] - RTSP: {}, Profile: {}", session.getId(), rtspUrl, profile);

        // 세션 생성 및 등록
        RtspSession rtspSession = new RtspSession(session);
        rtspSession.setRtspUrl(rtspUrl);
        rtspSession.setProfile(profile);
        registry.register(rtspSession);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
        String messageId = jsonMessage.get("id").getAsString();

        log.debug("메시지 수신 [{}]: {}", session.getId(), messageId);

        RtspSession rtspSession = registry.getBySession(session);
        if (rtspSession == null) {
            log.error("세션을 찾을 수 없음: {}", session.getId());
            return;
        }

        try {
            switch (messageId) {
                case "start" -> handleStart(session, rtspSession, jsonMessage);
                case "stop" -> handleStop(session);
                case "onIceCandidate" -> handleIceCandidate(rtspSession, jsonMessage);
                default -> sendError(session, "Invalid message id: " + messageId);
            }
        } catch (Throwable t) {
            log.error("메시지 처리 에러", t);
            sendError(session, t.getMessage());
        }
    }

    /**
     * 스트림 시작 처리
     */
    private void handleStart(WebSocketSession session, RtspSession rtspSession, JsonObject jsonMessage) {
        String rtspUrl = rtspSession.getRtspUrl();
        String profile = rtspSession.getProfile();
        
        log.info("스트림 시작 - RTSP: {}, Profile: {}", rtspUrl, profile);

        // 미디어 파이프라인 생성
        MediaPipeline pipeline = kurentoClient.createMediaPipeline();
        rtspSession.setMediaPipeline(pipeline);

        // PlayerEndpoint 생성 (RTSP 소스)
        PlayerEndpoint player = new PlayerEndpoint.Builder(pipeline, rtspUrl).build();
        rtspSession.setPlayerEndpoint(player);

        // WebRtcEndpoint 생성
        WebRtcEndpoint webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline).build();
        rtspSession.setWebRtcEndpoint(webRtcEndpoint);

        // STUN 서버 설정 (설정된 경우)
        if (stunServerUrl != null && !stunServerUrl.isEmpty()) {
            webRtcEndpoint.setStunServerAddress(stunServerUrl);
            log.info("STUN 서버 설정: {}", stunServerUrl);
        }

        // 파이프라인 연결 (Profile 적용)
        connectPipeline(player, webRtcEndpoint, pipeline, profile);

        // 이벤트 리스너 등록
        registerEventListeners(session, player, webRtcEndpoint);

        // SDP 협상
        String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
        String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

        // SDP Answer 전송
        JsonObject response = new JsonObject();
        response.addProperty("id", "startResponse");
        response.addProperty("sdpAnswer", sdpAnswer);
        
        // STUN 서버 정보도 함께 전송
        if (stunServerUrl != null && !stunServerUrl.isEmpty()) {
            response.addProperty("stunServer", stunServerUrl);
        }
        
        sendMessage(session, response.toString());

        // ICE 수집 및 재생 시작
        webRtcEndpoint.gatherCandidates();
        player.play();

        log.info("스트림 재생 시작 [{}]", session.getId());
    }

    /**
     * 파이프라인 연결 (Profile에 따라 스케일링 필터 적용)
     */
    private void connectPipeline(PlayerEndpoint player, WebRtcEndpoint webRtcEndpoint, 
                                  MediaPipeline pipeline, String profile) {
        if (profile != null && PROFILE_RESOLUTIONS.containsKey(profile.toUpperCase())) {
            int[] resolution = PROFILE_RESOLUTIONS.get(profile.toUpperCase());
            int width = resolution[0];
            int height = resolution[1];
            
            // GStreamer 스케일링 필터
            GStreamerFilter scaleFilter = new GStreamerFilter.Builder(pipeline, "videoscale")
                    .withFilterType(FilterType.VIDEO)
                    .build();
            
            String capsCommand = String.format("capsfilter caps=video/x-raw,width=%d,height=%d", width, height);
            GStreamerFilter capsFilter = new GStreamerFilter.Builder(pipeline, capsCommand)
                    .withFilterType(FilterType.VIDEO)
                    .build();
            
            // Player → Scale → Caps → WebRTC
            player.connect(scaleFilter);
            scaleFilter.connect(capsFilter);
            capsFilter.connect(webRtcEndpoint);
            
            log.info("Profile {} 적용: {}x{}", profile.toUpperCase(), width, height);
        } else {
            // 원본 해상도
            player.connect(webRtcEndpoint);
            log.debug("원본 해상도 사용");
        }
    }

    /**
     * 이벤트 리스너 등록
     */
    private void registerEventListeners(WebSocketSession session, PlayerEndpoint player, 
                                         WebRtcEndpoint webRtcEndpoint) {
        // Player 에러
        player.addErrorListener(event -> {
            log.error("Player 에러: {}", event.getDescription());
            sendError(session, "RTSP 에러: " + event.getDescription());
        });

        // 스트림 종료
        player.addEndOfStreamListener(event -> {
            log.info("스트림 종료 [{}]", session.getId());
            JsonObject response = new JsonObject();
            response.addProperty("id", "streamEnded");
            sendMessage(session, response.toString());
        });

        // ICE Candidate
        webRtcEndpoint.addIceCandidateFoundListener(event -> {
            JsonObject response = new JsonObject();
            response.addProperty("id", "iceCandidate");
            response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
            sendMessage(session, response.toString());
        });
    }

    /**
     * 스트림 정지 처리
     */
    private void handleStop(WebSocketSession session) {
        RtspSession rtspSession = registry.removeBySession(session);
        if (rtspSession != null) {
            log.info("스트림 정지 [{}]", session.getId());
        }
    }

    /**
     * ICE Candidate 처리
     */
    private void handleIceCandidate(RtspSession rtspSession, JsonObject jsonMessage) {
        WebRtcEndpoint webRtcEndpoint = rtspSession.getWebRtcEndpoint();
        if (webRtcEndpoint != null) {
            JsonObject candidateJson = jsonMessage.getAsJsonObject("candidate");
            IceCandidate candidate = new IceCandidate(
                    candidateJson.get("candidate").getAsString(),
                    candidateJson.get("sdpMid").getAsString(),
                    candidateJson.get("sdpMLineIndex").getAsInt()
            );
            webRtcEndpoint.addIceCandidate(candidate);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("연결 종료 [{}] - {}", session.getId(), status);
        registry.removeBySession(session);
    }

    private void sendMessage(WebSocketSession session, String message) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                }
            }
        } catch (IOException e) {
            log.error("메시지 전송 실패: {}", e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "error");
        response.addProperty("message", message);
        sendMessage(session, response.toString());
    }
}
