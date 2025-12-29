# Kurento WebRTC Server

RTSP 스트림을 WebRTC로 변환하여 브라우저에서 실시간 재생하는 서버입니다.

## 기술 스택

- **Spring Boot 3.2**
- **Kurento Media Server 7.0**
- **WebSocket + WebRTC**

## 기능

- RTSP → WebRTC 변환 및 스트리밍
- H.265 → H.264 트랜스코딩 지원
- Profile별 해상도 스케일링 (FHD, HD, D1, CIF)
- STUN 서버 선택적 사용 (오프라인 환경 지원)

## 프로젝트 구조

```
src/main/java/com/example/webrtc/
├── config/
│   ├── KurentoConfig.java      # Kurento 클라이언트 설정
│   └── WebSocketConfig.java    # WebSocket 엔드포인트 설정
├── handler/
│   ├── StreamHandler.java      # /stream 엔드포인트 핸들러
│   ├── RtspHandler.java        # /rtsp 엔드포인트 핸들러
│   └── WebRtcHandler.java      # /webrtc 루프백 핸들러
├── registry/
│   ├── RtspSession.java        # RTSP 세션 정보
│   └── RtspSessionRegistry.java # 세션 관리
└── WebRtcApplication.java
```

## WebSocket 엔드포인트

| 엔드포인트 | 용도 | RTSP URL 전달 방식 |
|-----------|------|-------------------|
| `/webrtc` | 루프백 테스트 | - |
| `/rtsp` | RTSP 스트림 | `start` 메시지에 포함 |
| `/stream` | RTSP 스트림 | 쿼리 파라미터 |

## /stream 프로토콜

### 연결
```
ws://host:port/stream?url=rtsp://...&profile=HD&transcode=true
```

### 쿼리 파라미터

| 파라미터 | 설명 | 값 |
|---------|------|-----|
| `url` | RTSP URL (필수) | URL 인코딩된 RTSP 주소 |
| `profile` | 해상도 프로필 | FHD, HD, D1, CIF |
| `transcode` | H.265 트랜스코딩 | true, false |

### 메시지 프로토콜

**클라이언트 → 서버:**
```json
{ "id": "start", "sdpOffer": "v=0..." }
{ "id": "stop" }
{ "id": "onIceCandidate", "candidate": {...} }
```

**서버 → 클라이언트:**
```json
{ "id": "startResponse", "sdpAnswer": "v=0..." }
{ "id": "iceCandidate", "candidate": {...} }
{ "id": "error", "message": "..." }
{ "id": "streamEnded" }
```

### Profile 옵션

| Profile | 해상도 |
|---------|--------|
| FHD | 1920x1080 |
| HD | 1280x720 |
| D1 | 720x480 |
| CIF | 352x288 |
| (없음) | 원본 해상도 |

## 실행 방법

### 1. Kurento Media Server 실행 (Docker)

```bash
# WSL/Linux (host 네트워크 모드)
docker run -d --name kurento \
  --network host \
  kurento/kurento-media-server:7.3.0

# Docker Desktop for Windows
docker run -d --name kurento \
  -p 8888:8888 \
  kurento/kurento-media-server:7.3.0
```

### 2. application.properties 설정

```properties
# Kurento 서버 URL
kurento.ws.url=ws://172.18.116.168:8888/kurento

# STUN 서버 (선택사항)
stun.server.url=

# H.265 트랜스코딩 기본값
transcode.h265.enabled=false
```

### 3. Spring Boot 실행

```bash
./mvnw spring-boot:run
```

### 4. 테스트 페이지 접속

- http://localhost:8090/stream.html - 스트림 플레이어
- http://localhost:8090/rtsp.html - RTSP 플레이어
- http://localhost:8090/index.html - 루프백 테스트

## H.265 트랜스코딩

H.265(HEVC) 카메라를 사용하는 경우, WebRTC가 H.265를 지원하지 않으므로 트랜스코딩이 필요합니다.

### 사용 방법

1. **쿼리 파라미터로 설정:**
   ```
   ws://host:port/stream?url=rtsp://...&transcode=true
   ```

2. **전역 설정 (application.properties):**
   ```properties
   transcode.h265.enabled=true
   ```

### 트랜스코딩 파이프라인

```
RTSP(H.265) → decodebin → x264enc → WebRTC(H.264)
```

**참고:** 트랜스코딩은 CPU 리소스를 많이 사용합니다. 가능하면 카메라 설정에서 H.264를 사용하세요.

## STUN 서버 설정

### 온라인 환경
```properties
stun.server.url=stun.l.google.com:19302
```

### 오프라인 환경
```properties
stun.server.url=
```

클라이언트와 서버가 같은 네트워크에 있어야 합니다.

## 트러블슈팅

### ICE 연결 실패 (connection failed)

- 브라우저와 Kurento가 같은 네트워크에 있는지 확인
- 오프라인 환경이면 STUN 서버 설정 제거
- 방화벽 설정 확인
- Docker 네트워크 모드 확인 (`--network host` 권장)

### RTSP 연결 실패

- RTSP URL 형식 확인: `rtsp://user:pass@host:port/path`
- 특수문자 URL 인코딩 필요 (! → %21)
- Kurento 컨테이너에서 카메라 IP 접근 가능한지 확인

### H.265 코덱 미지원

WebRTC는 H.265를 지원하지 않습니다. `transcode=true` 옵션을 사용하거나 카메라 설정에서 H.264로 변경하세요.
