#!/usr/bin/env bash
# Builds AutobotLlama.xcframework (llama.cpp + common + mtmd, Metal, Accelerate) and
# AutobotWhisper.xcframework (whisper.cpp, Metal, Core ML encoder) from the pinned submodules in
# /third_party, each linked with the shared engine core from /engine/*/src/main/cpp/core.
#
# usage: scripts/build-xcframeworks.sh [llama|whisper ...]   (default: both)
# Output: Frameworks/<Name>.xcframework with ios-arm64 and ios-arm64_x86_64-simulator slices,
# each with its dSYM (built with -g, split by dsymutil, then stripped) so archives upload symbols.
# Needs Xcode, cmake (>= 3.28) and ninja. Nothing is downloaded.
set -euo pipefail

PKG="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$PKG/../../.." && pwd)"
BUILD="${BUILD_DIR:-$PKG/.build-native}"
OUT="$PKG/Frameworks"
IOS_MIN="18.0"
VERSION="1.6.5"
JOBS="$(sysctl -n hw.logicalcpu)"

for tool in cmake ninja xcrun; do
    command -v "$tool" >/dev/null || { echo "error: $tool not found (brew install cmake ninja / install Xcode)" >&2; exit 1; }
done
for sub in llama.cpp whisper.cpp; do
    [[ -f "$REPO/third_party/$sub/CMakeLists.txt" ]] || { echo "error: third_party/$sub is missing (git submodule update --init third_party/$sub)" >&2; exit 1; }
done

ENGINES=("$@")
[[ ${#ENGINES[@]} -eq 0 ]] && ENGINES=(llama whisper)

# slice:sdk:platform:archs (the simulator slice also covers Intel Macs)
SLICES=("ios-arm64:iphoneos:iPhoneOS:arm64" "ios-arm64_x86_64-simulator:iphonesimulator:iPhoneSimulator:arm64;x86_64")

build_slice() {
    local engine=$1 name=$2 slice=$3 sdk=$4 archs=$5
    local dir="$BUILD/$engine-$slice"
    echo "==> $name [$slice]"
    mkdir -p "$BUILD"
    cmake -S "$PKG/native/$engine" -B "$dir" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME=iOS \
        -DCMAKE_OSX_SYSROOT="$sdk" \
        -DCMAKE_OSX_ARCHITECTURES="$archs" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET="$IOS_MIN" \
        -DCMAKE_C_FLAGS="-g -Wno-shorten-64-to-32 -Wno-macro-redefined" \
        -DCMAKE_CXX_FLAGS="-g -Wno-shorten-64-to-32 -Wno-macro-redefined" \
        > "$dir.configure.log" 2>&1 || { tail -40 "$dir.configure.log"; exit 1; }
    cmake --build "$dir" --target "$name" -j "$JOBS" > "$dir.build.log" 2>&1 || { tail -60 "$dir.build.log"; exit 1; }
}

# Wraps the dylib as a flat iOS framework with a Clang module so Swift can `import <name>`.
make_framework() {
    local engine=$1 name=$2 slice=$3 platform=$4 header=$5
    local fw="$BUILD/frameworks/$slice/$name.framework"
    rm -rf "$fw"
    mkdir -p "$fw/Headers" "$fw/Modules"
    cp "$BUILD/$engine-$slice/$name" "$fw/$name"
    # Debug symbols leave the binary before it is stripped (same UUIDs, so crashes symbolicate).
    rm -rf "$fw.dSYM"
    xcrun dsymutil "$BUILD/$engine-$slice/$name" -o "$fw.dSYM"
    xcrun strip -x "$fw/$name"
    cp "$header" "$fw/Headers/"
    cat > "$fw/Modules/module.modulemap" <<EOF
framework module $name {
    header "$(basename "$header")"
    export *
}
EOF
    cat > "$fw/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key><string>en</string>
    <key>CFBundleExecutable</key><string>$name</string>
    <key>CFBundleIdentifier</key><string>ai.wckd.autobot.$name</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>CFBundleName</key><string>$name</string>
    <key>CFBundlePackageType</key><string>FMWK</string>
    <key>CFBundleShortVersionString</key><string>$VERSION</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>CFBundleSupportedPlatforms</key><array><string>$platform</string></array>
    <key>MinimumOSVersion</key><string>$IOS_MIN</string>
</dict>
</plist>
EOF
}

build_engine() {
    local engine=$1 name header
    case "$engine" in
        llama)   name=AutobotLlama;   header="$REPO/engine/llama/src/main/cpp/core/autobot_llama.h" ;;
        whisper) name=AutobotWhisper; header="$REPO/engine/speech/src/main/cpp/core/autobot_whisper.h" ;;
        *) echo "error: unknown engine '$engine' (llama, whisper)" >&2; exit 1 ;;
    esac
    local args=()
    for spec in "${SLICES[@]}"; do
        IFS=: read -r slice sdk platform archs <<< "$spec"
        build_slice "$engine" "$name" "$slice" "$sdk" "$archs"
        make_framework "$engine" "$name" "$slice" "$platform" "$header"
        args+=(-framework "$BUILD/frameworks/$slice/$name.framework" -debug-symbols "$BUILD/frameworks/$slice/$name.framework.dSYM")
    done
    mkdir -p "$OUT"
    rm -rf "$OUT/$name.xcframework"
    xcodebuild -create-xcframework "${args[@]}" -output "$OUT/$name.xcframework" > /dev/null
    echo "    $OUT/$name.xcframework"
}

for engine in "${ENGINES[@]}"; do build_engine "$engine"; done
