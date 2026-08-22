#!/usr/bin/env bash
# Builds the release APK (signed with the real release key — see
# ~/.android/keystores/palewake/, never the debug key), reads its version from
# build.gradle.kts, and publishes it as a GitHub release (tag "v<versionCode>")
# with the APK attached. A debug-signed build can't install as an update over a
# real release, only uninstall/reinstall, so this must always be a release build.
# Requires: gh CLI, authenticated (`gh auth login`).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_FILE="$SCRIPT_DIR/app/build.gradle.kts"
NOTES="${1:-}"

VERSION_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$GRADLE_FILE")"
VERSION_NAME="$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE_FILE")"
TAG="v$VERSION_CODE"

echo "Building release APK (versionCode=$VERSION_CODE, versionName=$VERSION_NAME)..."
(cd "$SCRIPT_DIR" && ./gradlew assembleRelease)

APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "Build succeeded but APK not found at $APK_PATH" >&2
  exit 1
fi

echo "Publishing release $TAG..."
gh release create "$TAG" "$APK_PATH" \
  --title "$VERSION_NAME" \
  --notes "$NOTES"
echo "Published."
