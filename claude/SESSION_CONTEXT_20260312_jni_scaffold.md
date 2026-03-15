# 세션 컨텍스트 — 2026-03-12 v4

> Kotlin JNI wrapper 설계 및 스캐폴딩 완료.
> `oxlens-jni` crate + `oxlens-sdk` Android Library 모듈 생성.

---

## 이번 세션 완료 작업 (v3 이후 추가분)

### 1. Kotlin JNI Wrapper 설계 확정 ✔️

**기술 선택**: 수동 JNI (`jni` crate 0.21) — uniffi 대비 빌드 단순, async 이벤트 제어 용이
**향후 iOS 확장 시 uniffi 마이그레이션 검토 가능**

### 2. `oxlens-jni` crate 스캐폴딩 ✔️

```
crates/oxlens-jni/
├── Cargo.toml          (crate-type = ["cdylib"])
└── src/
    ├── lib.rs           — JNI_OnLoad + Tokio Runtime 싱글턴 (OnceLock)
    ├── client.rs        — JNI 바인딩 함수 (connect, join, leave, floor 등)
    └── callback.rs      — 이벤트 펌프 (ClientEvent → Kotlin 콜백)
```

**핵심 패턴**:
- `OnceLock<Runtime>` — 전용 Tokio Runtime 1개 (.so 수명)
- `OnceLock<JavaVM>` — JNI_OnLoad에서 캐시
- `ClientHandle` — `Box::into_raw()` → Long 핸들 → Kotlin
- `event_pump` — `runtime.spawn()` → `jvm.attach_current_thread()` → 리스너 콜백
- `nativeDestroy()` — `Box::from_raw()` → drop

**JNI API 목록**:
| JNI 함수 | Kotlin 메서드 |
|---|---|
| `nativeConnect(url, token, userId, listener)` → Long | `companion object { fun connect(...) }` |
| `nativeCreateRoom(handle, name, cap, mode)` | `createRoom(...)` |
| `nativeListRooms(handle)` | `listRooms()` |
| `nativeJoinRoom(handle, roomId)` | `joinRoom(roomId)` |
| `nativeLeaveRoom(handle)` | `leaveRoom()` |
| `nativeRequestFloor(handle, roomId)` | `requestFloor(roomId)` |
| `nativeReleaseFloor(handle, roomId)` | `releaseFloor(roomId)` |
| `nativeFloorPing(handle, roomId)` | `floorPing(roomId)` |
| `nativeRoomId(handle)` → String? | `val roomId` |
| `nativeDestroy(handle)` | `destroy()` |

### 3. `oxlens-sdk` Android Library 모듈 ✔️

```
platform/android/oxlens-sdk/
├── build.gradle.kts     (com.android.library, minSdk=24)
├── consumer-rules.pro   (JNI 콜백 클래스 keep)
├── proguard-rules.pro
├── src/main/
│   ├── AndroidManifest.xml   (INTERNET + RECORD_AUDIO)
│   ├── java/com/oxlens/sdk/
│   │   ├── OxLensEventListener.kt   — 콜백 인터페이스 (16개 메서드)
│   │   └── OxLensClient.kt          — Kotlin API surface
│   └── jniLibs/arm64-v8a/           — .so 배치 위치 (빈 상태)
```

### 4. Gradle 구성 업데이트 ✔️

- `settings.gradle.kts`: `include(":oxlens-sdk")` 추가
- `demo-app/build.gradle.kts`: `implementation(project(":oxlens-sdk"))` 추가
- Cargo workspace `Cargo.toml`: `"crates/oxlens-jni"` 추가
- `.cargo/config.toml`: `[target.aarch64-linux-android]` 섹션 추가

---

## 이전 세션 완료 작업 (v3)

- List 모드 E2E 검증 ✔️
- Android 프로젝트 스캐폴딩 + libwebrtc AAR 임포트 검증 ✔️
- (v2) Arc 핸들 패턴 리팩터링 + subscribe on_track 검증 ✔️

---

## 설계 결정 사항

### JNI 기술 선택: 수동 JNI (jni crate)
- API surface 좁음 (10개 함수) → uniffi 오버헤드 불필요
- async 이벤트 스트림 → GlobalRef 콜백 패턴으로 직접 제어
- iOS 확장 시 uniffi 마이그레이션 검토

### 이벤트 전달: Rust → Kotlin
- `event_pump` 태스크가 `mpsc::Receiver<ClientEvent>` 소비
- 매 이벤트마다 `jvm.attach_current_thread()` → `env.call_method(listener, ...)`
- Kotlin 콜백은 Rust 워커 스레드에서 호출 → UI 갱신 시 메인 스레드 전환 필요

---

## 알려진 이슈

### 1. `FloorGranted`의 `room_id`가 빈 문자열 (미해결)
### 2. libwebrtc AAR — arm64-v8a만 포함 (에뮬레이터 미지원)

### 3. cargo-ndk 빌드 미검증 (신규)
- `cargo ndk` 설치 필요: `cargo install cargo-ndk`
- Android NDK 설치 필요 (Android Studio → SDK Manager → NDK)
- Rust 타겟 추가 필요: `rustup target add aarch64-linux-android`
- **oxlens-webrtc-sys의 C++ FFI가 Android NDK 컴파일러로 빌드되는지 미확인**
  - livekit webrtc-sys의 prebuilt 바이너리가 Android arm64 지원 여부 확인 필요
  - 미지원 시 libwebrtc Android 바이너리를 직접 링크하는 빌드 스크립트 필요

---

## 다음 작업

### 즉시: cargo check 확인
- Windows 환경에서 `cargo check -p oxlens-jni` 통과 확인
- (Android 타겟 크로스 빌드는 별도 준비 필요)

### 후속: cargo-ndk 빌드 파이프라인
- cargo-ndk 설치 + NDK 설정
- oxlens-webrtc-sys Android 크로스 빌드 문제 해결
- `liboxlens_jni.so` 생성 → jniLibs에 배치

### 후속: demo-app 연동 테스트
- demo-app에서 OxLensClient.connect() 호출
- 실기기에서 이벤트 콜백 동작 확인

---

## 변경 파일 목록

```
oxlens-sdk-core/
├── Cargo.toml                     ← members에 oxlens-jni 추가
├── .cargo/config.toml             ← aarch64-linux-android 타겟 추가
├── crates/
│   └── oxlens-jni/                ← ★ 신규 crate
│       ├── Cargo.toml
│       └── src/
│           ├── lib.rs
│           ├── client.rs
│           └── callback.rs
├── platform/
│   └── android/
│       ├── settings.gradle.kts    ← oxlens-sdk 모듈 추가
│       ├── demo-app/
│       │   └── build.gradle.kts   ← oxlens-sdk 의존성 추가
│       └── oxlens-sdk/            ← ★ 신규 Android Library 모듈
│           ├── build.gradle.kts
│           ├── consumer-rules.pro
│           ├── proguard-rules.pro
│           └── src/main/
│               ├── AndroidManifest.xml
│               ├── java/com/oxlens/sdk/
│               │   ├── OxLensClient.kt
│               │   └── OxLensEventListener.kt
│               └── jniLibs/arm64-v8a/
└── doc/
    └── SESSION_CONTEXT_20260312_v2.md  ← 이 파일 (v4)
```

---

*author: kodeholic (powered by Claude)*
