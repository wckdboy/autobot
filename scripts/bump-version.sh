#!/bin/sh
# Sets the same version on Android and iOS: scripts/bump-version.sh 1.3.0
# The build number (Android versionCode = iOS CURRENT_PROJECT_VERSION) goes up by one.
set -eu
cd "$(dirname "$0")/.."
version="${1:?usage: scripts/bump-version.sh X.Y.Z}"
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
next=$((code + 1))
sed -i '' -e "s/versionCode = $code/versionCode = $next/" -e "s/versionName = \"[^\"]*\"/versionName = \"$version\"/" app/build.gradle.kts
sed -i '' -e "s/MARKETING_VERSION: \"[^\"]*\"/MARKETING_VERSION: \"$version\"/" -e "s/CURRENT_PROJECT_VERSION: \"[^\"]*\"/CURRENT_PROJECT_VERSION: \"$next\"/" ios/project.yml
sed -i '' -e "s/^VERSION=\"[^\"]*\"/VERSION=\"$version\"/" ios/Packages/AutobotEngines/scripts/build-xcframeworks.sh
echo "Autobot $version ($next)"
