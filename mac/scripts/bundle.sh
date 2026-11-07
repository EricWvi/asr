#!/usr/bin/env bash
# Builds TingXie.app into mac/build.
#
#   VERSION            release version such as 1.2.3 (default: dev, which
#                      never checks for updates)
#   MAC_SIGN_IDENTITY  code signing identity (default: "TingXie Code Signing"
#                      if the keychain has it, else ad-hoc). Use the same
#                      certificate for every build: macOS ties the
#                      Accessibility permission to it, and an ad-hoc
#                      signature changes with every build.
#   UNIVERSAL=1        build for arm64 and x86_64
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=${VERSION:-dev}
IDENTITY=${MAC_SIGN_IDENTITY:-}
if [[ -z $IDENTITY ]]; then
  IDENTITY=-
  if security find-identity -p codesigning 2>/dev/null | grep -q '"TingXie Code Signing"'; then
    IDENTITY="TingXie Code Signing"
  fi
fi
BUILD=0
if [[ $VERSION =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  # Same scheme as the Android versionCode: 1.2.3 → 1002003.
  BUILD=$((BASH_REMATCH[1] * 1000000 + BASH_REMATCH[2] * 1000 + BASH_REMATCH[3]))
fi

ARCHS=()
if [[ ${UNIVERSAL:-} == 1 ]]; then ARCHS=(--arch arm64 --arch x86_64); fi
swift build -c release ${ARCHS[@]+"${ARCHS[@]}"}
BIN=$(swift build -c release ${ARCHS[@]+"${ARCHS[@]}"} --show-bin-path)/TingXie

APP=build/TingXie.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/TingXie"
cp Resources/* "$APP/Contents/Resources/"
sed -e "s/__VERSION__/$VERSION/" -e "s/__BUILD__/$BUILD/" Info.plist > "$APP/Contents/Info.plist"
codesign --force --sign "$IDENTITY" "$APP"
codesign --verify --strict "$APP"
echo "$APP"
