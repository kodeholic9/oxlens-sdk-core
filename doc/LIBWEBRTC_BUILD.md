# libwebrtc 커스텀 빌드 절차

> OxLens AudioInterceptor 패치 적용 + AAR 빌드 절차.
> 다른 머신이나 upstream 업데이트 후 재구성 시 사용.
>
> author: kodeholic (powered by Claude)  
> created: 2026-03-14

---

## 전제 조건

- Ubuntu 22.04+ (WSL2 또는 네이티브)
- 디스크 여유 40GB+
- depot_tools 설치됨 (`gn`, `ninja`, `autoninja` 사용 가능)

---

## 1. 소스 준비 (최초 1회만)

```bash
mkdir -p ~/webrtc-android && cd ~/webrtc-android
fetch --nohooks webrtc_android
cd src
gclient sync
```

## 2. 커스텀 브랜치 생성

```bash
cd ~/webrtc-android/src
git checkout -b oxlens-custom
```

## 3. 패치 적용

### 방법 A: diff 파일로 적용

```bash
cd ~/webrtc-android/src
git apply ~/webrtc-android/oxlens-patch.diff
```

### 방법 B: 수동 확인

패치 대상 파일 (9개 + 신규 3개):

| 파일 | 변경 내용 |
|------|----------|
| `oxlens/audio_interceptor.h` | **신규** — 인터페이스 |
| `oxlens/audio_interceptor_impl.h` | **신규** — 구현 헤더 |
| `oxlens/audio_interceptor_impl.cc` | **신규** — 구현 (silence injection + offset 보정) |
| `call/call.h` | include + `SetAudioInterceptor()` 가상 메서드 |
| `call/call.cc` | include + 멤버 + setter + `DeliverRtpPacket()` 인터셉트 |
| `call/BUILD.gn` | `rtc_library("call")` sources에 oxlens 파일 등록 |
| `api/peer_connection_interface.h` | 4개 가상 메서드 (빈 기본 구현 `{}`) |
| `pc/peer_connection.h` | forward declare + override + 멤버 |
| `pc/peer_connection.cc` | impl include + 4개 메서드 구현 |
| `sdk/android/api/.../PeerConnection.java` | Java API 4개 |
| `sdk/android/src/jni/pc/peer_connection.cc` | JNI 바인딩 4개 |

## 4. 빌드 설정 (gn gen)

```bash
cd ~/webrtc-android/src

# args.gn 생성 (최초 1회 또는 설정 변경 시)
gn gen out/Debug-arm64 --args='target_os="android" target_cpu="arm64" is_debug=true is_component_build=false rtc_include_tests=false'
```

## 5. 증분 빌드 (C++ 커파일 수정 후)

```bash
cd ~/webrtc-android/src
autoninja -C out/Debug-arm64
```

- 증분 빌드: **수초 ~ 수십 초**
- 풀 클린 빌드: ~10분

## 6. AAR 빌드

```bash
cd ~/webrtc-android/src

python3 tools_webrtc/android/build_aar.py \
  --build-dir out \
  --arch arm64-v8a \
  --extra-gn-args 'is_debug=true is_component_build=false rtc_include_tests=false'
```

- 소요 시간: **~30분**
- 출력: `out/libwebrtc.aar` (~13.5MB)

## 7. AAR 복사 (WSL → Windows)

```bash
cp ~/webrtc-android/src/out/libwebrtc.aar \
  /mnt/d/X.WORK/GitHub/repository/oxlens-sdk-core/platform/android/oxlens-sdk/libs/libwebrtc.aar
```

## 8. Android Studio에서 확인

- Gradle sync + 빌드
- 기존 Conference/PTT 기능 E2E 테스트
- 새 API 확인: `PeerConnection.enableAudioInterceptor()` 등 4개 메서드 접근 가능

---

## 패치 관리

### diff 저장

```bash
cd ~/webrtc-android/src
git diff main..oxlens-custom > ~/webrtc-android/oxlens-patch.diff
git log main..oxlens-custom --oneline > ~/webrtc-android/oxlens-commits.txt
```

### upstream 업데이트 시

```bash
cd ~/webrtc-android/src
git checkout main
git pull
gclient sync

# 커스텀 브랜치로 복귀 + rebase
git checkout oxlens-custom
git rebase main
# 충돌 해결 후
autoninja -C out/Debug-arm64
```

---

## 주의사항

1. **`RTC_LOG`에서 `std::hex`/`std::dec` 사용 불가** — WebRTC `LogStreamer`는 `std::ostream`이 아님
2. **`PeerConnectionInterface`에 순수 가상(= 0) 추가 불가** — `PeerConnectionProxy`에서 미구현 에러 → 빈 기본 구현 `{}` 사용
3. **`pc/peer_connection.h`에서 impl 직접 include 불가** — 빌드 의존성 문제 → forward declare + `.cc`에서 include
4. **BUILD.gn 소스 경로는 `//` 접두사** — `"//oxlens/audio_interceptor_impl.cc"` 형식
5. **AAR 빌드는 gn gen을 자체적으로 실행** — `out/Debug-arm64/args.gn`과 별도

---

## 커밋 이력 (2026-03-14 기준)

```
50ef6db088 oxlens: Step 3 — JNI bindings (EnableAudioInterceptor, Silence, Offset, OpusPt)
eef25e3ecf oxlens: Step 2 — AudioInterceptorImpl (silence injection + offset correction)
e4d44e7e9f oxlens: Step 1 — AudioInterceptor interface + Call integration
```

---

*author: kodeholic (powered by Claude)*
