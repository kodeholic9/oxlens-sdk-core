# 세션 컨텍스트 — 2026-03-12 v3

> OxLensClient Arc 핸들 패턴 리팩터링 완료.
> LiveKit Rust SDK의 `(Room, event_rx)` 패턴 채택.
> bench를 OxLensClient 기반으로 전환, Conference + PTT + List E2E 전체 통과.
> **subscribe PC on_track 검증 완료** — NativeAudioStream으로 수신 프레임 확인.
> **Android libwebrtc AAR 임포트 검증 완료** — 실기기에서 PeerConnectionFactory 정상.

---

## 이번 세션 완료 작업 (v2 이후 추가분)

### 1. List 모드 E2E 검증 ✔️

- 터미널 1: conference 모드 (bench-19256) — 방 생성 + 접속 유지
- 터미널 2: list 모드 (bench-4044) — ROOM_LIST → 이름 매칭 → JOIN → 2PC
- 양방향 미디어 흐름 확인 (양쪽 모두 on_track → 오디오 프레임 수신)
- 상대방 퇴장 시 TRACKS_UPDATE(remove) + ROOM_EVENT 정상 수신
- **3개 모드 (Conference, PTT, List) 전체 E2E 통과**

### 2. Android 프로젝트 스캐폴딩 ✔️

**프로젝트 구조**:
```
oxlens-sdk-core/
├── crates/              ← Rust (기존)
├── platform/
│   └── android/         ← ★ Android Studio 루트
│       ├── settings.gradle.kts
│       ├── build.gradle.kts     (AGP 8.7.3, Kotlin 2.0.21)
│       ├── gradle.properties
│       ├── gradle/wrapper/
│       └── demo-app/            ← 검증용 앱 모듈
│           ├── build.gradle.kts (minSdk=24, targetSdk=35)
│           ├── libs/libwebrtc.aar
│           └── src/main/
│               ├── AndroidManifest.xml
│               ├── java/com/oxlens/demo/MainActivity.kt
│               └── res/
└── libwebrtc.aar        ← WSL2 빌드 원본
```

**Android Studio Open 경로**: `D:\X.WORK\GitHub\repository\oxlens-sdk-core\platform\android`

### 3. libwebrtc AAR 임포트 검증 ✔️

- 실기기(arm64)에서 테스트
- `PeerConnectionFactory.initialize()` → OK
- `PeerConnectionFactory.builder().createPeerConnectionFactory()` → OK
- `factory.dispose()` → OK
- **크래시 없음, libwebrtc 네이티브 라이브러리 정상 로드**

---

## 이전 세션 완료 작업 (v2)

### Arc 핸들 패턴 리팩터링
- `OxLensClient`를 `Arc<ClientInner>` 기반 Clone 가능 구조로 변경
- `connect()` → `(OxLensClient, Receiver<ClientEvent>)` 패턴
- 모든 공개 API `&self` — `join_room()`, `create_room()`, `request_floor()` 등

### subscribe PC on_track 검증
- on_track 콜백 → mpsc → `track_receive_loop` 패턴
- 수신 프레임: 48kHz, mono, 480 samples/frame = 10ms
- connect() 시점 1회 spawn으로 중복 방지

### bench 프레임 사이즈 정렬
- 송신: 10ms/480 samples (libwebrtc 내부 단위에 맞춤)

---

## 설계 결정 사항

### MBCP over UDP → WS(TCP) 확정
- PTT Floor Control은 WS 시그널링 경로를 primary로 확정
- UDP MBCP 경로는 보류

### Android 프로젝트 구조
- `platform/android/` 구조 채택 (향후 iOS 등 확장 대비)
- `oxlens-sdk-core` 안에 `platform/` 디렉토리로 관리
- Rust crates와 Android Studio 프로젝트를 한 repo에서 관리

---

## 알려진 이슈

### 1. `FloorGranted`의 `room_id`가 빈 문자열
- 서버가 FLOOR_REQUEST 응답에 room_id 미포함
- 서버 수정 또는 클라이언트에서 로컬 room_id 사용으로 해결 가능

### 2. libwebrtc AAR ABI
- WSL2에서 빌드 → arm64-v8a만 포함 가능성 (에뮬레이터 x86_64 미지원)
- 실기기 테스트 통과 확인

---

## 다음 세션 작업 계획

### 1순위: Kotlin JNI wrapper 설계
- Rust `.so` → JNI → Kotlin 호출 구조 설계
- `oxlens-sdk` Android Library 모듈 추가 (platform/android/ 하위)
- cargo-ndk 빌드 파이프라인 구성

### 2순위: SRTP 암복호화 (서버)
- 현재 평문 RTP로 동작 중, 프로덕션 필수

### 3순위: 좀비 세션 정리 (서버)
- reaper 타이머 구현

---

## 변경 파일 목록

```
oxlens-sdk-core/
├── crates/
│   ├── oxlens-core/
│   │   └── src/
│   │       └── client.rs          ← Arc 핸들 + on_track 수신 루프
│   ├── oxlens-webrtc/
│   │   └── src/
│   │       ├── lib.rs             ← NativeAudioStream 등 re-export 추가
│   │       └── session.rs         ← on_track 콜백 + track_tx
│   └── oxlens-bench/
│       └── src/
│           └── main.rs            ← AudioFrameReceived arm
├── platform/
│   └── android/                   ← ★ 신규 (Android Studio 프로젝트)
│       ├── settings.gradle.kts
│       ├── build.gradle.kts
│       ├── gradle.properties
│       └── demo-app/
│           ├── build.gradle.kts
│           ├── libs/libwebrtc.aar
│           └── src/main/
└── doc/
    └── SESSION_CONTEXT_20260312.md  ← 이 파일 (v3)
```

---

## 빌드 환경 메모

- Rust: `.cargo/config.toml` `+crt-static` 필수
- Android: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, minSdk 24, targetSdk 35
- 실기기 테스트 완료 (arm64)

---

*author: kodeholic (powered by Claude)*
