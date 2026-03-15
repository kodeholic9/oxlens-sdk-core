# 세션 컨텍스트 — 2026-03-14

> **Android 크로스 빌드 성공** — `liboxlens_jni.so` (20MB, aarch64) 생성 완료.
> jniLibs/arm64-v8a/ 배치 완료.

---

## 이번 세션 완료 작업

### 1. Android 크로스 빌드 성공 ✔️

**빌드 환경**: WSL2 Ubuntu + Rust 1.93 + cargo-ndk + Android NDK r29

**해결한 이슈들**:
1. **OpenSSL 크로스 빌드 실패** → `tokio-tungstenite` TLS 백엔드를 `native-tls` → `rustls-tls-native-roots`로 전환
2. **webrtc-sys 0.2/0.3 prebuilt 충돌** → `webrtc-sys = "0.3"` 제거, `livekit-webrtc 0.2`가 끌어오는 0.2만 사용
3. **abseil/rtc_base 헤더 누락** → 위 2번으로 해결 (0.2 prebuilt에 완전한 헤더 포함)
4. **libc++abi.a incompatible** → `webrtc-sys-0.2.0/build.rs` 패치: NDK sysroot aarch64 경로 추가
5. **libunwind.so incompatible** → NDK 호스트용 `libunwind.so`를 임시 rename하여 우회

**webrtc-sys 0.2.0 build.rs 패치 (WSL cargo registry)**:
```
경로: ~/.cargo/registry/src/index.crates.io-*/webrtc-sys-0.2.0/build.rs
182번 줄 근처 android 블록에 추가:
  if let Ok(ndk) = std::env::var("ANDROID_NDK_HOME") {
      println!("cargo:rustc-link-search=native={}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android", ndk);
      println!("cargo:rustc-link-search=native={}/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/21/lib/linux/aarch64", ndk);
  }
```

**libunwind 우회 (빌드 시마다 필요)**:
```bash
mv $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/libunwind.so \
   $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/libunwind.so.bak
cargo ndk -t arm64-v8a build --release -p oxlens-jni
mv $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/libunwind.so.bak \
   $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/libunwind.so
```

**산출물**: `target/aarch64-linux-android/release/liboxlens_jni.so` (20MB)
**배치**: `platform/android/oxlens-sdk/src/main/jniLibs/arm64-v8a/liboxlens_jni.so`

### 2. Workspace 정리 ✔️
- `webrtc-sys = "0.3"` workspace에서 제거
- `oxlens-webrtc-sys`: `webrtc-sys` 직접 의존 → `livekit-webrtc` 의존으로 변경
- TLS: `native-tls` → `rustls-tls-native-roots` (Windows/Android 모두 통과)

---

## 이전 세션 완료 작업
- `oxlens-jni` crate 스캐폴딩 (lib.rs, client.rs, callback.rs) ✔️
- `oxlens-sdk` Android Library 모듈 (OxLensClient.kt, OxLensEventListener.kt) ✔️
- Gradle/Cargo 구성 연결 ✔️
- WSL2 빌드 환경 구축 (Rust, NDK r29, cargo-ndk) ✔️

---

## 다음 작업

### 즉시: demo-app 연동 테스트
- Android Studio에서 demo-app 빌드 + 실기기 설치
- `System.loadLibrary("oxlens_jni")` 로딩 확인
- 최소 connect 테스트 (서버 연결 + 이벤트 콜백)

### 후속: 최소 PoC UI
- PTT 버튼 + 상태 표시
- 서버 연결 → 방 입장 → Floor Control 시연

---

## 빌드 명령어 요약

**Windows (기존 bench/테스트)**:
```bash
cargo check -p oxlens-core
cargo build -p oxlens-bench
```

**WSL2 (Android .so 빌드)**:
```bash
cd /mnt/d/X.WORK/GitHub/repository/oxlens-sdk-core
# libunwind 우회
mv $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/libunwind.so \
   $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/libunwind.so.bak
cargo ndk -t arm64-v8a build --release -p oxlens-jni
mv $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/libunwind.so.bak \
   $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/libunwind.so
# 산출물 배치
cp target/aarch64-linux-android/release/liboxlens_jni.so \
   platform/android/oxlens-sdk/src/main/jniLibs/arm64-v8a/
```

---

*author: kodeholic (powered by Claude)*
