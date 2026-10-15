#!/bin/sh
# Builds, tests and publishes one Autobot version on both platforms:
#
#   scripts/release.sh 1.4.0 [notes.md]
#
# 1. bumps Android + iOS to the version (scripts/bump-version.sh) and commits the bump
# 2. runs every test suite: Android/JVM (Gradle), iOS app, iOS engines
# 3. builds the signed release APK + debug APK, archives iOS and uploads it to TestFlight
# 4. tags vX.Y.Z, pushes, and publishes the GitHub release with the APKs and SHA256SUMS.txt
#
# Stops at the first failure, before anything is tagged or published. Needs: keystore.properties
# (release signing), an Xcode account with App Store Connect access, and `gh` logged in.
# Set SKIP_TESTFLIGHT=1 to publish only the APKs.
set -eu
cd "$(dirname "$0")/.."
ROOT="$PWD"
VERSION="${1:?usage: scripts/release.sh X.Y.Z [notes.md]}"
NOTES="${2:-}"
TAG="v$VERSION"
WORK="${TMPDIR:-/tmp}/autobot-release-$VERSION"
export JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home}"

step() { printf '\n== %s\n' "$*"; }
fail() { echo "release: $*" >&2; exit 1; }

[ -f keystore.properties ] || fail "keystore.properties is missing (release APKs must be signed)"
[ -z "$(git status --porcelain)" ] || fail "the working tree is not clean"
git rev-parse "$TAG" >/dev/null 2>&1 && fail "$TAG already exists"
[ -z "$NOTES" ] || [ -f "$NOTES" ] || fail "notes file $NOTES not found"
rm -rf "$WORK" && mkdir -p "$WORK"

step "version $VERSION"
scripts/bump-version.sh "$VERSION"
BUILD=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)

step "Android + shared: tests, debug and signed release"
./gradlew testDebugUnitTest :shared:jvmTest :agent:core:jvmTest assembleDebug :app:assembleFossRelease --console=plain > "$WORK/gradle.log" 2>&1 ||
    { tail -40 "$WORK/gradle.log"; fail "Gradle failed (log: $WORK/gradle.log)"; }
APK=app/build/outputs/apk/foss/release/app-foss-release.apk
[ -f "$APK" ] || fail "no signed release APK (is keystore.properties valid?)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
"$BT/apksigner" verify "$APK" || fail "release APK signature does not verify"

step "iOS: engines, tests"
rm -rf ios/Packages/AutobotEngines/Frameworks/*.xcframework
ios/Packages/AutobotEngines/scripts/build-xcframeworks.sh > "$WORK/engines.log" 2>&1 || fail "engine frameworks (log: $WORK/engines.log)"
(cd ios && xcodegen generate -q)
SIM=$(xcrun simctl list devices available | grep -E "iPhone [0-9]+ Pro \(" | grep -oE "[0-9A-F-]{36}" | tail -1)
[ -n "$SIM" ] || fail "no iPhone simulator available"
xcodebuild -project ios/Autobot.xcodeproj -scheme Autobot -destination "id=$SIM" test > "$WORK/ios-test.log" 2>&1 ||
    { grep -E "✘|error:" "$WORK/ios-test.log" | head -20; fail "iOS tests failed (log: $WORK/ios-test.log)"; }
(cd ios/Packages/AutobotEngines && xcodebuild -scheme AutobotEngines -destination "id=$SIM" test > "$WORK/engines-test.log" 2>&1) ||
    fail "engine tests failed (log: $WORK/engines-test.log)"

if [ "${SKIP_TESTFLIGHT:-}" != "1" ]; then
    step "iOS: archive and TestFlight upload"
    xcodebuild -project ios/Autobot.xcodeproj -scheme Autobot -configuration Release -destination 'generic/platform=iOS' \
        -archivePath "$WORK/Autobot.xcarchive" archive -allowProvisioningUpdates > "$WORK/archive.log" 2>&1 || fail "archive (log: $WORK/archive.log)"
    cat > "$WORK/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>upload</string>
  <key>teamID</key><string>XKA8CGC2AB</string>
  <key>signingStyle</key><string>automatic</string>
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict></plist>
EOF
    xcodebuild -exportArchive -archivePath "$WORK/Autobot.xcarchive" -exportOptionsPlist "$WORK/ExportOptions.plist" \
        -exportPath "$WORK/export" -allowProvisioningUpdates > "$WORK/upload.log" 2>&1 ||
        { grep -E "error" -A2 "$WORK/upload.log" | head; fail "TestFlight upload (log: $WORK/upload.log)"; }
fi

step "commit, tag, publish $TAG"
git add app/build.gradle.kts ios/project.yml ios/Packages/AutobotEngines/scripts/build-xcframeworks.sh
git commit -q -m "Release $VERSION ($BUILD)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git tag -a "$TAG" -m "Autobot $VERSION"
git push origin HEAD "$TAG"
cp "$APK" "$WORK/autobot-$VERSION-release.apk"
cp app/build/outputs/apk/foss/debug/app-foss-debug.apk "$WORK/autobot-$VERSION-debug.apk"
(cd "$WORK" && shasum -a 256 autobot-*.apk | sed 's/  / */' > SHA256SUMS.txt)
if [ -n "$NOTES" ]; then set -- --notes-file "$NOTES"; else set -- --generate-notes; fi
gh release create "$TAG" --title "Autobot $VERSION" --latest "$@" \
    "$WORK/autobot-$VERSION-release.apk" "$WORK/autobot-$VERSION-debug.apk" "$WORK/SHA256SUMS.txt"
# Download from the repo directory (gh needs it to know the repository), check in $WORK.
rm -rf "$WORK/dl" && gh release download "$TAG" -D "$WORK/dl" && (cd "$WORK/dl" && shasum -a 256 -c SHA256SUMS.txt) || fail "published assets do not match"
echo "Released Autobot $VERSION ($BUILD): $(gh release view "$TAG" --json url -q .url)"
