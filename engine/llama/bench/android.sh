#!/usr/bin/env bash
# Builds llama_bench for arm64 Android with the app's native configuration, pushes it with the
# ggml backends and the app's pinned Hexagon libraries to /data/local/tmp/autobot-bench, and runs
# it there (DSP libraries via ADSP_LIBRARY_PATH, as LlamaService sets it up).
#
#   engine/llama/bench/android.sh [--serial S] [--model local.gguf] -- <llama_bench args>
#
# The model is pushed once (skipped when the phone already has a file of the same size). Run
# `./gradlew :engine:llama:fetchHexagonLibs` first so the Hexagon libraries are in the build dir.
# Example: engine/llama/bench/android.sh --model qwen3.5-0.8b-q4_0.gguf -- --backend npu --speed
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
NDK_VERSION="$(sed -n 's/^ndk = "\(.*\)"/\1/p' "$REPO/gradle/libs.versions.toml")"
NDK="${ANDROID_NDK:-$SDK/ndk/$NDK_VERSION}"
BUILD="${BUILD_DIR:-$REPO/engine/llama/build/bench-android}"
DEST=/data/local/tmp/autobot-bench

SERIAL="${ANDROID_SERIAL:-}"
MODEL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --model) MODEL="$2"; shift 2 ;;
        --) shift; break ;;
        *) break ;;
    esac
done
adb() { if [[ -n "$SERIAL" ]]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

cmake -S "$REPO/engine/llama/bench" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 -DANDROID_STL=c++_shared >/dev/null
cmake --build "$BUILD" >/dev/null

HEXAGON="$(ls -d "$REPO"/engine/llama/build/hexagon/*/arm64-v8a 2>/dev/null | head -1 || true)"
[[ -n "$HEXAGON" ]] || echo "no Hexagon libraries (run ./gradlew :engine:llama:fetchHexagonLibs): NPU unavailable" >&2

adb shell mkdir -p "$DEST"
libs=("$BUILD/llama_bench" "$NDK"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so)
while IFS= read -r f; do libs+=("$f"); done < <(find "$BUILD" -name '*.so' -not -path '*/CMakeFiles/*' -not -name 'libOpenCL.so*')
[[ -n "$HEXAGON" ]] && libs+=("$HEXAGON"/*.so)
adb push "${libs[@]}" "$DEST/" >/dev/null 2>&1

ARGS=()
if [[ -n "$MODEL" ]]; then
    name="$(basename "$MODEL")"
    local_size="$(stat -f%z "$MODEL" 2>/dev/null || stat -c%s "$MODEL")"
    remote_size="$(adb shell "stat -c%s $DEST/$name 2>/dev/null" | tr -d '\r' || true)"
    [[ "$local_size" == "$remote_size" ]] || adb push "$MODEL" "$DEST/$name" >/dev/null 2>&1
    ARGS+=("$DEST/$name")
fi

DSP="$DEST;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp/dsp;/vendor/dsp"
adb shell "cd $DEST && LD_LIBRARY_PATH=$DEST ADSP_LIBRARY_PATH='$DSP' ./llama_bench --libs $DEST ${ARGS[*]-} $*"
