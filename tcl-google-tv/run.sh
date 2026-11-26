#!/usr/bin/env bash
# Mac/Linux: connects to the TV over the network, copies tvtune.sh and packages.txt
# onto it and runs one step there. Output is also saved to tvtune-<step>.log.
#
#   ./run.sh <tv-ip> scan|apply|launcher|replace-home|restore
set -euo pipefail

usage="usage: $0 <tv-ip> scan|apply|launcher|replace-home|restore"
[ $# -eq 2 ] || { echo "$usage" >&2; exit 2; }
ip=$1
step=$2
case $step in
  scan|apply|launcher|replace-home|restore) ;;
  *) echo "$usage" >&2; exit 2 ;;
esac
here=$(cd "$(dirname "$0")" && pwd)
PATH="$here:$PATH"   # also finds adb copied into this folder
command -v adb >/dev/null || { echo "adb not found: install Android platform-tools and add it to your PATH" >&2; exit 1; }

case $ip in *:*) target=$ip ;; *) target=$ip:5555 ;; esac

echo "Connecting to $target. If the TV asks 'Allow USB debugging?', tick 'Always allow' and press OK."
state=""
for _ in $(seq 1 30); do
  adb connect "$target" >/dev/null 2>&1 || true
  state=$(adb -s "$target" get-state 2>/dev/null || true)
  [ "$state" = device ] && break
  sleep 2
done
if [ "$state" != device ]; then
  echo "Couldn't connect to $target. Check the IP, that the PC is on the same network, and that USB debugging is on." >&2
  exit 1
fi

# Strip Windows line endings: Android's shell can't run a script that has them.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
tr -d '\r' < "$here/tvtune.sh" > "$tmp/tvtune.sh"
tr -d '\r' < "$here/packages.txt" > "$tmp/packages.txt"

adb -s "$target" shell mkdir -p /data/local/tmp/tvtune
adb -s "$target" push "$tmp/tvtune.sh" "$tmp/packages.txt" /data/local/tmp/tvtune/ >/dev/null
adb -s "$target" shell sh /data/local/tmp/tvtune/tvtune.sh "$step" | tee "$here/tvtune-$step.log"
