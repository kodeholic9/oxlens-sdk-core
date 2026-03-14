#!/bin/bash
# author: kodeholic (powered by Claude)
# Android .so 빌드 스크립트 (WSL2 전용)
#
# 사용법: bash scripts/build-android.sh [--debug]
#
# 사전 조건:
#   1. WSL2에서 실행
#   2. ANDROID_NDK_HOME 설정됨
#   3. cargo-ndk 설치됨: cargo install cargo-ndk
#   4. libunwind.so 우회는 스크립트가 최초 1회 자동 처리

set -e

# ================================================================
#  설정
# ================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NDK_HOME="${ANDROID_NDK_HOME:-$HOME/android-ndk-r29}"
TARGET="arm64-v8a"
PLATFORM="24"
CRATE="oxlens-jni"
SO_NAME="liboxlens_jni.so"
JNILIB_DIR="platform/android/oxlens-sdk/src/main/jniLibs/arm64-v8a"

# 빌드 모드
if [ "$1" == "--debug" ]; then
    BUILD_MODE=""
    PROFILE="debug"
    echo "🔧 빌드 모드: DEBUG"
else
    BUILD_MODE="--release"
    PROFILE="release"
    echo "🔧 빌드 모드: RELEASE"
fi

# ================================================================
#  NDK 검증
# ================================================================

if [ ! -d "$NDK_HOME" ]; then
    echo "❌ ANDROID_NDK_HOME 경로 없음: $NDK_HOME"
    exit 1
fi

echo "📦 NDK: $NDK_HOME"
echo "📁 프로젝트: $PROJECT_ROOT"

# ================================================================
#  libunwind 우회 (최초 1회만 — .bak 없으면 rename, 이후 영구)
# ================================================================

LIBUNWIND="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/libunwind.so"
LIBUNWIND_BAK="$LIBUNWIND.bak"

if [ -f "$LIBUNWIND" ] && [ ! -f "$LIBUNWIND_BAK" ]; then
    echo "⚠️  libunwind.so 최초 우회: rename → .bak"
    mv "$LIBUNWIND" "$LIBUNWIND_BAK"
    echo "✅ libunwind.so → libunwind.so.bak (영구 적용)"
elif [ -f "$LIBUNWIND" ] && [ -f "$LIBUNWIND_BAK" ]; then
    echo "⚠️  libunwind.so가 복원돼 있음 — 다시 제거"
    rm "$LIBUNWIND"
elif [ ! -f "$LIBUNWIND" ]; then
    echo "✅ libunwind.so 이미 우회됨"
fi

# ================================================================
#  빌드
# ================================================================

cd "$PROJECT_ROOT"

echo ""
echo "🔨 cargo ndk 빌드 시작..."
echo "   target:   $TARGET"
echo "   platform: $PLATFORM"
echo "   crate:    $CRATE"
echo "   profile:  $PROFILE"
echo ""

cargo ndk -t "$TARGET" --platform "$PLATFORM" build $BUILD_MODE -p "$CRATE"

# ================================================================
#  산출물 배치
# ================================================================

SRC="target/aarch64-linux-android/$PROFILE/$SO_NAME"
DST="$JNILIB_DIR/$SO_NAME"

if [ ! -f "$SRC" ]; then
    echo "❌ 산출물 없음: $SRC"
    exit 1
fi

mkdir -p "$JNILIB_DIR"
cp "$SRC" "$DST"

SIZE=$(du -h "$DST" | cut -f1)
echo ""
echo "✅ 빌드 완료!"
echo "   $DST ($SIZE)"
echo ""
echo "👉 Android Studio에서 Run 누르면 됩니다."
