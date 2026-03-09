# DEV_ENVIRONMENT_SETUP

> oxlens-sdk-core 개발 환경 구축 가이드
>
> author: kodeholic (powered by Claude)  
> created: 2025-03-09  
> updated: 2025-03-09 (v2 — libwebrtc 빌드 완료, Rust 워크스페이스 단계 추가)  
> target: Windows 11 + WSL2 + Rust + libwebrtc

---

## 0. 사전 확인

| 항목        | 최소       | 권장  | 비고                                    |
| ----------- | ---------- | ----- | --------------------------------------- |
| RAM         | 16GB       | 16GB+ | 빌드 시 `-j4` 병렬도 제한 권장          |
| 디스크 여유 | 50GB       | 80GB+ | WSL2 이미지 + WebRTC 소스 + 빌드 산출물 |
| OS          | Windows 11 | —     | WSL2 기본 지원                          |

> ⚠️ WSL2 가상 디스크는 기본적으로 C: 드라이브에 생성됩니다.
> C: 여유가 부족하면 D: 드라이브로 이동하는 절차를 Phase 1에서 안내합니다.

---

## Phase 1: WSL2 + Ubuntu 설치 ✅ 완료

### 1.1 WSL2 설치

PowerShell을 **관리자 권한**으로 실행:

```powershell
wsl --install
```

설치 완료 후 **재부팅**.

### 1.2 Ubuntu 설치

재부팅 후 PowerShell(관리자):

```powershell
# 사용 가능한 배포판 확인
wsl --list --online

# Ubuntu 22.04 설치 (libwebrtc 빌드 호환성 최적)
wsl --install -d Ubuntu-22.04
```

> **왜 22.04인가?**  
> libwebrtc의 `install-build-deps.sh`가 Ubuntu 22.04(jammy)를 공식 지원합니다.
> 24.04는 일부 의존성 패키지가 달라져서 빌드 에러 가능성이 있습니다.

### 1.3 WSL2 리소스 제한 설정

Windows 측에서 `C:\Users\<사용자명>\.wslconfig` 파일 생성:

```ini
[wsl2]
memory=12GB
processors=4
swap=4GB
```

### 1.4 (선택) WSL2 가상 디스크를 D: 드라이브로 이동

C: 드라이브 여유가 부족한 경우:

```powershell
wsl --export Ubuntu-22.04 D:\backup\ubuntu-22.04.tar
wsl --unregister Ubuntu-22.04
mkdir D:\wsl
wsl --import Ubuntu-22.04 D:\wsl\ D:\backup\ubuntu-22.04.tar
ubuntu2204.exe config --default-user <사용자명>
```

---

## Phase 2: Ubuntu 기본 패키지 + depot_tools ✅ 완료

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y \
  git python3 python3-pip curl wget \
  build-essential pkg-config \
  openjdk-11-jdk \
  unzip zip gnupg flex bison gperf \
  libnss3-tools rsync file lsb-release

# depot_tools
cd ~
git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
echo 'export PATH="$HOME/depot_tools:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

---

## Phase 3: WebRTC Android 소스 + 빌드 ✅ 완료

### 소스 다운로드

```bash
mkdir -p ~/webrtc-android && cd ~/webrtc-android
fetch --nohooks webrtc_android
cd src
gclient sync
./build/install-build-deps.sh
./build/install-build-deps-android.sh
```

### libwebrtc.aar 빌드 ✅ 완료

```bash
cd ~/webrtc-android/src
python3 tools_webrtc/android/build_aar.py
```

산출물: `~/webrtc-android/src/out/libwebrtc.aar`

### Windows 복사 ✅ 완료

```bash
cp ~/webrtc-android/src/out/libwebrtc.aar /mnt/d/X.WORK/GitHub/repository/oxlens-sdk-core/
```

> **참고**: 이 .aar은 Android JNI 바인딩(Phase 3 로드맵)에서 사용.
> Phase 1 로드맵에서는 LiveKit `webrtc-sys-build`가 프리빌드 바이너리를 자동 다운로드하므로 직접 빌드한 .aar은 아직 불필요.

---

## Phase 4: Rust 툴체인 설치 🔜 다음 작업

### 4.1 Windows 측 Rust 설치

```powershell
# rustup 설치 (https://rustup.rs)
winget install Rustlang.Rustup

# 설치 확인
rustc --version
cargo --version
```

> **Rust는 이미 설치되어 있을 가능성 높음** (oxlens-sfu-server 개발에 사용 중).
> `rustc --version`으로 확인 후, 1.75+ 이면 OK.

### 4.2 필수 타겟 추가

```powershell
# Android 크로스 컴파일 타겟 (Phase 3 로드맵에서 필요, 지금은 선택)
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
```

### 4.3 빌드 도구

```powershell
# LLVM/Clang (webrtc-sys 빌드 시 bindgen에 필요)
winget install LLVM.LLVM

# CMake
winget install Kitware.CMake
```

### 4.4 환경변수 확인

```powershell
# LIBCLANG_PATH 설정 (webrtc-sys bindgen에 필요)
# 보통 C:\Program Files\LLVM\bin 에 설치됨
[System.Environment]::SetEnvironmentVariable("LIBCLANG_PATH", "C:\Program Files\LLVM\bin", "User")
```

---

## Phase 5: Rust 워크스페이스 초기 셋업 🔜 다음 작업

### 5.1 워크스페이스 구조

```
oxlens-sdk-core/
├── Cargo.toml              ← 워크스페이스 루트
├── crates/
│   ├── oxlens-core/        ← PTT 비즈니스 로직 (Floor FSM, WS 시그널링, MBCP)
│   │   ├── Cargo.toml
│   │   └── src/lib.rs
│   ├── oxlens-webrtc/      ← libwebrtc Safe Rust 래퍼
│   │   ├── Cargo.toml
│   │   └── src/lib.rs
│   └── oxlens-webrtc-sys/  ← C++ FFI (webrtc-sys fork)
│       ├── Cargo.toml
│       └── src/lib.rs
├── examples/
│   └── bench/              ← sfu-bench 통합 벤치마크
└── platform/
    └── android/            ← JNI 바인딩 (Phase 3 로드맵)
```

### 5.2 LiveKit webrtc-sys 의존성

```toml
# crates/oxlens-webrtc-sys/Cargo.toml
[dependencies]
webrtc-sys = { git = "https://github.com/livekit/rust-sdks", branch = "main" }

# 또는 특정 커밋 고정 (권장)
# webrtc-sys = { git = "https://github.com/livekit/rust-sdks", rev = "abc1234" }
```

### 5.3 빌드 검증

```powershell
cd D:\X.WORK\GitHub\repository\oxlens-sdk-core
cargo build
```

> 첫 빌드 시 `webrtc-sys-build`가 플랫폼별 프리빌드 libwebrtc 바이너리를 자동 다운로드.
> 다운로드 크기: ~200MB. 이후 캐시되어 재빌드 시 생략.

---

## Phase 6: Android Studio + SDK/NDK 설치 (Phase 3 로드맵 시점)

> Phase 1~2 로드맵에서는 불필요. Rust 순수 프로젝트로 개발 후, JNI 바인딩 시점에 설치.

1. https://developer.android.com/studio 에서 다운로드
2. SDK Platforms: API 34 (Android 14), API 28 (Android 9)
3. SDK Tools: Build-Tools, Command-line Tools, NDK (Side by side), CMake
4. 환경변수: `ANDROID_HOME`, Path에 `platform-tools` 추가

---

## 검증 체크리스트

### Phase 1~3 (완료)

- [x] WSL2 Ubuntu 22.04 설치
- [x] depot_tools 설치
- [x] WebRTC Android 소스 fetch + gclient sync
- [x] libwebrtc.aar 빌드 성공
- [x] .aar → Windows 복사 완료

### Phase 4~5 (다음)

- [ ] Rust 툴체인 확인 (rustc 1.75+, LLVM, CMake)
- [ ] LIBCLANG_PATH 환경변수 설정
- [ ] Cargo 워크스페이스 구조 생성
- [ ] LiveKit webrtc-sys 의존성 추가
- [ ] `cargo build` 성공 (프리빌드 바이너리 자동 다운로드 확인)
- [ ] oxlens-core 빈 크레이트 빌드 성공

### Phase 6 (나중)

- [ ] Android Studio 설치
- [ ] Gradle Sync 성공
- [ ] `import org.webrtc.PeerConnectionFactory` 자동완성 확인

---

## 트러블슈팅

### WSL2 메모리 부족 (OOM)

```bash
ninja -C out/Debug-arm64 -j2  # 병렬도 낮춤
```

### webrtc-sys 빌드 시 LIBCLANG_PATH 에러

```powershell
# bindgen이 libclang을 못 찾는 경우
$env:LIBCLANG_PATH = "C:\Program Files\LLVM\bin"
cargo build
```

### webrtc-sys-build 다운로드 실패

```powershell
# 프록시/방화벽 문제 시 수동 다운로드 후 캐시 경로에 배치
# 캐시 경로는 빌드 로그에서 확인 가능
```

---

*author: kodeholic (powered by Claude)*
