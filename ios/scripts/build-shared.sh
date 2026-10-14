#!/bin/sh
# Builds the shared Kotlin runtime (AutobotShared.xcframework) for a configuration and copies it
# to ios/Frameworks/. Usage: ios/scripts/build-shared.sh [Debug|Release]
set -eu
CONFIGURATION="${1:-Debug}"
[ "${AUTOBOT_SKIP_SHARED_BUILD:-}" = "1" ] && exit 0
IOS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$IOS_DIR/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  BREW="$(command -v brew || echo /opt/homebrew/bin/brew)"
  JAVA_HOME="$("$BREW" --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
  export JAVA_HOME
fi
if [ "$CONFIGURATION" = "Release" ]; then VARIANT=Release; DIR=release; else VARIANT=Debug; DIR=debug; fi
cd "$ROOT"
sh ./gradlew --configure-on-demand ":shared:assembleAutobotShared${VARIANT}XCFramework"
mkdir -p "$IOS_DIR/Frameworks"
rm -rf "$IOS_DIR/Frameworks/AutobotShared.xcframework"
cp -R "shared/build/XCFrameworks/$DIR/AutobotShared.xcframework" "$IOS_DIR/Frameworks/"
echo "AutobotShared ($VARIANT) → ios/Frameworks/AutobotShared.xcframework"
