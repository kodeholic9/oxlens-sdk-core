# 세션 컨텍스트 — 2026-03-15 (0314 세션에서 이어짐)

> **Android .so 로딩 성공, 서버 연결 시 SIGSEGV — LSE atomics 초기화 문제 해결 중**

---

## 이번 세션 완료 작업

### 1. .so 로딩 테스트 통과 ✔️
- `System.loadLibrary("oxlens_jni")` 성공
- libwebrtc AAR + Rust JNI .so 모두 크래시 없이 로드

### 2. 서버 연결 테스트 → SIGSEGV 크래시 발견
- `OxLensClient.connect()` 호출 시 SIGSEGV (null pointer dereference)
- 원인 분석 과정:
  1. **pthread_atfork 미해결** → `--platform 24`로 해결
  2. **init_have_lse_atomics → getauxval null deref** → 근본 원인 발견

### 3. 근본 원인 분석 완료
- Rust 1.93 stable의 pre-compiled std (aarch64-linux-android)에 **outline-atomics**가 기본 활성화
- cdylib(.so)는 Rust std 초기화 경로를 안 거쳐서 `__aarch64_have_lse_atomics` 미초기화
- compiler_builtins가 자체 `getauxval` stub을 .so에 포함 → libc의 진짜 getauxval을 가림
- 참조: rust-lang/rust#109064, rust-lang/rust#144938

### 4. LSE atomics constructor 구현 ✔️
- `crates/oxlens-jni/c/lse_init.c` — `__attribute__((constructor))`로 초기화
- `crates/oxlens-jni/build.rs` — cc crate로 C 코드 컴파일
- **핵심**: `dlsym(RTLD_NEXT, "getauxval")`로 libc의 진짜 함수를 찾아서 호출
  - 직접 `getauxval()` 호출하면 .so 내부의 stub으로 resolve되어 동일 크래시
  - `RTLD_NEXT`로 현재 .so 다음 라이브러리(libc)에서 검색

### 5. android_logger 전환 ✔️
- `oxlens-jni`의 로깅: tracing → log + android_logger
- Logcat에 `oxlens-jni` 태그로 Rust 로그 출력 가능

### 6. 빌드 스크립트 생성 ✔️
- `scripts/build-android.sh` — libunwind 우회 + cargo-ndk + .so 복사 자동화

---

## 다음 세션 즉시 할 일

### 1순위: 빌드 + 실행 테스트
- `bash scripts/build-android.sh` → Android Studio Run
- SIGSEGV 해결 확인 → `oxlens-jni` 태그 Logcat에서 `[JNI] step` 로그 확인
- 기대 결과: `✓ onConnected` + `✓ onIdentified`

### 2순위: SIGSEGV 여전하면
- `RTLD_NEXT`가 안 먹힐 경우 대안:
  - `dlsym(RTLD_DEFAULT, "getauxval")` 대신 `dlopen("libc.so", ...)`로 명시적 로드
  - 또는 Rust 버전 다운그레이드 (1.80~1.85, outline-atomics 비활성화 시점)
  - 또는 `__aarch64_have_lse_atomics = 0` 하드코딩 (LSE 미사용, LL/SC only)

### 3순위: 서버 연결 성공 후
- Logcat에서 HELLO → IDENTIFY → Identified 시퀀스 확인
- 이후: ROOM_CREATE → ROOM_JOIN → 2PC 미디어 경로 E2E 검증

---

## 변경된 파일 목록

```
crates/oxlens-jni/
├── Cargo.toml          ← android_logger, log, cc 추가
├── build.rs            ← ★ 신규: aarch64-android에서 lse_init.c 컴파일
├── c/
│   └── lse_init.c      ← ★ 신규: LSE atomics constructor (dlsym + RTLD_NEXT)
└── src/
    ├── lib.rs          ← android_logger 초기화, tracing 제거
    ├── client.rs       ← log::{error,info} 전환, 디버그 step 로그 추가
    └── callback.rs     ← log::{error,info} 전환

platform/android/demo-app/
└── src/.../MainActivity.kt  ← testServerConnect() 추가, testWebRtcFactory 비활성화

scripts/
└── build-android.sh    ← ★ 신규: Android .so 빌드 스크립트

.cargo/config.toml      ← outline-atomics 관련 주석 정리
```

---

## 빌드 명령어

```bash
# WSL2에서
cd /mnt/d/X.WORK/GitHub/repository/oxlens-sdk-core
bash scripts/build-android.sh
# → Android Studio에서 Run
```

---

## 주의사항 (다음 세션 Claude에게)

1. **SIGSEGV 원인은 compiler_builtins의 getauxval stub** — .so 내부 심볼이 libc를 가림
2. **lse_init.c에서 RTLD_NEXT 사용** — 이게 핵심. 직접 getauxval() 호출하면 안 됨
3. **android_logger 사용 중** — Logcat 필터 `oxlens-jni` 태그
4. **testWebRtcFactory() 비활성화** — Rust SDK가 내부에서 초기화하므로 충돌 방지
5. **서버 주소**: `ws://192.168.0.29:1974/ws`

---

*author: kodeholic (powered by Claude)*
