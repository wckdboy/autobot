#!/bin/sh
# Builds the AutobotEngines native xcframeworks (llama.cpp, whisper.cpp) when they are missing.
set -eu
PKG="$(cd "$(dirname "$0")/../Packages/AutobotEngines" && pwd)"
if [ -d "$PKG/Frameworks/AutobotLlama.xcframework" ] && [ -d "$PKG/Frameworks/AutobotWhisper.xcframework" ]; then
  exit 0
fi
"$PKG/scripts/build-xcframeworks.sh"
