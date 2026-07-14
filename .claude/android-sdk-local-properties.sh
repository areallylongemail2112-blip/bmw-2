#!/usr/bin/env bash
# SessionStart helper: point Gradle at an Android SDK by writing local.properties.
# Safe + fast + idempotent:
#   - If local.properties already names a valid SDK dir, leave it untouched
#     (so a developer's own path — e.g. on Windows — is never clobbered).
#   - Otherwise detect an SDK from env / common locations and write sdk.dir.
#   - If no SDK is found, do nothing but hint at the installer.
# Emits a JSON {"systemMessage": ...} so the result shows in the Claude Code UI.
set -u
cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0

has_sdk() { [ -n "${1:-}" ] && { [ -d "$1/platform-tools" ] || [ -d "$1/cmdline-tools" ] || [ -d "$1/build-tools" ]; }; }

# 1. Respect an already-valid local.properties.
if [ -f local.properties ]; then
  cur=$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)
  if [ -n "${cur:-}" ] && [ -d "$cur" ]; then
    exit 0
  fi
fi

# 2. Detect an SDK.
sdk=""
for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/android-sdk \
         "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
  if has_sdk "$c"; then sdk="$c"; break; fi
done

if [ -n "$sdk" ]; then
  printf 'sdk.dir=%s\n' "$sdk" > local.properties
  printf '{"systemMessage":"Android SDK detected at %s — wrote local.properties for Gradle."}\n' "$sdk"
else
  printf '{"systemMessage":"No Android SDK found. Run .claude/android-sdk-setup.sh to install it before ./gradlew builds."}\n'
fi
