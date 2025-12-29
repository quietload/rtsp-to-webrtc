package com.example.webrtc.config;

import com.example.webrtc.handler.RtspHandler;
import com.example.webrtc.handler.StreamHandler;
import com.example.webrtc.handler.WebRtcHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 설정
 * 
 * <p>엔드포인트:</p>
 * <ul>
 *   <li>/webrtc - WebRTC 루프백 테스트</li>
 *   <li>/rtsp - RTSP 스트림 (JSON, RTSP URL을 메시지로 전달)</li>
 *   <li>/stream - RTSP 스트림 (JSON, RTSP URL을 쿼리 파라미터로 전달)</li>
 * </ul>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebRtcHandler webRtcHandler;
    private final RtspHandler rtspHandler;
    private final StreamHandler streamHandler;

    public WebSocketConfig(WebRtcHandler webRtcHandler, 
                          RtspHandler rtspHandler,
                          StreamHandler streamHandler) {
        this.webRtcHandler = webRtcHandler;
        this.rtspHandler = rtspHandler;
        this.streamHandler = streamHandler;
    }

    /**
     * WebSocket 메시지 버퍼 크기 설정 (SDP 메시지가 클 수 있음)
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024 * 1024);   // 1MB
        container.setMaxBinaryMessageBufferSize(1024 * 1024); // 1MB
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // WebRTC 루프백 테스트
        registry.addHandler(webRtcHandler, "/webrtc")
                .setAllowedOrigins("*");
        
        // RTSP 스트림 (RTSP URL을 start 메시지에 포함)
        registry.addHandler(rtspHandler, "/rtsp")
                .setAllowedOrigins("*");
        
        // RTSP 스트림 (RTSP URL을 쿼리 파라미터로 전달)
        // ws://host:port/stream?url=rtsp://...&profile=FHD|HD|D1|CIF
        registry.addHandler(streamHandler, "/stream")
                .setAllowedOrigins("*");
    }
}
