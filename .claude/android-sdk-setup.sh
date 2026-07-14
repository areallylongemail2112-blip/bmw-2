#!/usr/bin/env bash
# One-shot Android SDK installer for headless/remote Linux sessions so `./gradlew
# assembleDebug` works. Idempotent: exits fast if the SDK is already present.
# Installs: command-line tools, platform android-34, build-tools 34.0.0, platform-tools.
# (An emulator can't run in a container without KVM, so this does NOT install one —
# build/assemble/unit-test only. Run builds on a real device or a KVM-enabled host.)
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
CMDVER="commandlinetools-linux-11076708_latest.zip"

case "$(uname -s)" in
  Linux) : ;;
  *) echo "This installer targets Linux. On your machine, install the SDK via Android Studio instead."; exit 0 ;;
esac

if [ -d "$SDK/platform-tools" ] && [ -d "$SDK/platforms/android-34" ] && [ -d "$SDK/build-tools/34.0.0" ]; then
  echo "Android SDK already installed at $SDK"
else
  echo "Installing Android SDK to $SDK ..."
  mkdir -p "$SDK/cmdline-tools"
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    tmp="$(mktemp -d)"
    curl -sSL -o "$tmp/cmdtools.zip" "https://dl.google.com/android/repository/$CMDVER"
    unzip -q "$tmp/cmdtools.zip" -d "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
  fi
  SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
  yes | "$SDKM" --licenses >/dev/null 2>&1 || true
  yes | "$SDKM" "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
  echo "SDK install complete."
fi

# Point Gradle at it.
proj_dir="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
echo "sdk.dir=$SDK" > "$proj_dir/local.properties"
echo "Wrote $proj_dir/local.properties (sdk.dir=$SDK)"
echo "Build with: ./gradlew assembleDebug"
