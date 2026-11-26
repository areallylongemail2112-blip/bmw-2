#!/system/bin/sh
# Runs on the TV itself, in Android's shell. run.sh / run.ps1 push it to
# /data/local/tmp/tvtune/ together with packages.txt and start it.
#
#   sh tvtune.sh scan          report what's on the TV, change nothing
#   sh tvtune.sh apply         turn off the x packages, speed up animations, clear caches, recompile apps
#   sh tvtune.sh launcher      install Projectivy Launcher and let it take over the Home button
#   sh tvtune.sh replace-home  turn off the stock Google TV home screen (only if Home still opens it)
#   sh tvtune.sh restore       undo everything apply / replace-home turned off

DIR=/data/local/tmp/tvtune
LIST=$DIR/packages.txt
STATE=$DIR/disabled-by-tvtune.txt   # every package this script turned off, for restore

LAUNCHER=com.spocky.projengmenu     # Projectivy Launcher
STOCK_HOME=com.google.android.apps.tv.launcherx
SETUP_WIZARD=com.google.android.tungsten.setupwraith
AMBIENT=com.google.android.apps.tv.dreamx
ANIMATION_SCALE=0.5                 # 1 = stock, 0 = no animations at all

# packages.txt may have been saved with Windows line endings.
CLEAN=$DIR/packages.clean
tr -d '\r' < "$LIST" > "$CLEAN" || exit 1

installed() { pm path "$1" 2>/dev/null | grep -q '^package:'; }
disabled() { pm list packages -d "$1" 2>/dev/null | grep -qxF "package:$1"; }
free_ram_mb() { echo $(( $(sed -n 's/^MemAvailable: *\([0-9]*\).*/\1/p' /proc/meminfo) / 1024 )); }

turn_off() {  # turn_off <package> <description>
  installed "$1" || return 0
  if disabled "$1"; then
    echo "  already off  $1"
    return 0
  fi
  out=$(pm disable-user --user 0 "$1" 2>&1)
  if echo "$out" | grep -q 'new state: disabled'; then
    echo "$1" >> "$STATE"
    echo "  turned off   $1  ${2:+($2)}"
  else
    echo "  FAILED       $1: $out"
  fi
}

set_animations() {
  for key in window_animation_scale transition_animation_scale animator_duration_scale; do
    settings put global "$key" "$1"
  done
}

scan() {
  echo "TV:      $(getprop ro.product.manufacturer) $(getprop ro.product.model)"
  echo "Android: $(getprop ro.build.version.release) (build $(getprop ro.build.display.id))"
  echo "RAM:     $(free_ram_mb) MB free of $(( $(sed -n 's/^MemTotal: *\([0-9]*\).*/\1/p' /proc/meminfo) / 1024 )) MB"
  echo "Storage: $(df -h /data | tail -n 1)"
  echo
  echo "packages.txt (x = apply turns it off):"
  : > "$DIR/listed.txt"
  while read -r mark pkg desc; do
    case $mark in x|-) ;; *) continue ;; esac
    echo "$pkg" >> "$DIR/listed.txt"
    if ! installed "$pkg"; then state="not on TV"
    elif disabled "$pkg"; then state="off"
    else state="on"
    fi
    printf '  %s  %-44s %-10s %s\n' "$mark" "$pkg" "$state" "$desc"
  done < "$CLEAN"
  echo
  echo "Other TCL packages on this TV (not in packages.txt; ask before adding any):"
  pm list packages | sed -n 's/^package://p' | grep -i tcl | sort | while read -r pkg; do
    grep -qxF "$pkg" "$DIR/listed.txt" || echo "  $pkg"
  done
  echo
  echo "Downloaded apps (uninstall any you don't use in Settings > Apps):"
  pm list packages -3 | sed 's/^package:/  /' | sort
  echo
  echo "Home screen apps: $(pm query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | grep / | tr '\n' ' ')"
}

apply() {
  before=$(free_ram_mb)
  echo "Turning off packages marked x:"
  while read -r mark pkg desc; do
    [ "$mark" = x ] && turn_off "$pkg" "$desc"
  done < "$CLEAN"

  if disabled "$AMBIENT"; then
    # Don't leave the screensaver pointing at an app that's turned off.
    settings put secure screensaver_enabled 0
  fi

  echo
  echo "Animations: ${ANIMATION_SCALE}x (menus feel snappier)"
  set_animations "$ANIMATION_SCALE"

  echo "Clearing app caches..."
  pm trim-caches 999G

  echo "Recompiling apps for this TV's CPU. This can take 5-15 minutes; leave the TV on..."
  cmd package bg-dexopt-job >/dev/null 2>&1 && echo "  done" || echo "  skipped (not supported on this firmware)"

  echo
  echo "Free RAM: $before MB before, $(free_ram_mb) MB now."
  echo "Restart the TV to finish (Settings > System > Restart, or: adb reboot)."
}

launcher() {
  if ! installed "$LAUNCHER"; then
    echo "Opening Projectivy Launcher in the Play Store on the TV. Select Install there..."
    am start -a android.intent.action.VIEW -d "market://details?id=$LAUNCHER" >/dev/null
    tries=0
    until installed "$LAUNCHER"; do
      tries=$((tries + 1))
      if [ "$tries" -gt 100 ]; then
        echo "Still not installed after 5 minutes. Install it from the Play Store, then run this step again."
        exit 1
      fi
      sleep 3
    done
    echo "  installed"
  fi

  # Projectivy takes over the Home button through an accessibility service.
  service=$(dumpsys package "$LAUNCHER" | grep -A3 'android.accessibilityservice.AccessibilityService:' \
    | grep -o "$LAUNCHER/[A-Za-z0-9_.\$]*" | head -n 1)
  if [ -z "$service" ]; then
    echo "Couldn't find Projectivy's accessibility service. Opening Accessibility settings;"
    echo "turn Projectivy on there."
    am start -a android.settings.ACCESSIBILITY_SETTINGS >/dev/null
  else
    current=$(settings get secure enabled_accessibility_services)
    case ":$current:" in
      *":$service:"*) ;;
      *)
        if [ -z "$current" ] || [ "$current" = null ]; then
          settings put secure enabled_accessibility_services "$service"
        else
          settings put secure enabled_accessibility_services "$current:$service"
        fi
        settings put secure accessibility_enabled 1
        ;;
    esac
    echo "Projectivy's Home-button service is on ($service)."
  fi

  monkey -p "$LAUNCHER" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
  echo
  echo "Projectivy is open on the TV. In its settings, turn on the option to override the"
  echo "current launcher, then press Home on the remote. If Home still opens Google TV after"
  echo "a restart, run the replace-home step."
}

replace_home() {
  if ! installed "$LAUNCHER"; then
    echo "Install Projectivy first (the launcher step). Otherwise the TV would have no home screen."
    exit 1
  fi
  echo "Turning off the stock Google TV home screen:"
  turn_off "$STOCK_HOME" "Google TV home screen"
  turn_off "$SETUP_WIZARD" "setup wizard, which also claims the Home button"
  input keyevent KEYCODE_HOME
  echo
  echo "Press Home on the remote. If the TV asks which app to use, pick Projectivy > Always."
  echo "Google TV settings stay reachable from Projectivy's settings icon. The restore step brings the old home screen back."
}

restore() {
  if [ ! -s "$STATE" ]; then
    echo "Nothing to restore: this script hasn't turned anything off on this TV."
  else
    echo "Turning back on everything this script turned off:"
    sort -u "$STATE" | while read -r pkg; do
      pm enable "$pkg" >/dev/null 2>&1 && echo "  on  $pkg" || echo "  FAILED  $pkg"
      [ "$pkg" = "$AMBIENT" ] && settings put secure screensaver_enabled 1
    done
    rm -f "$STATE"
  fi
  set_animations 1
  echo "Animations back to 1x. Restart the TV to finish."
}

case $1 in
  scan) scan ;;
  apply) apply ;;
  launcher) launcher ;;
  replace-home) replace_home ;;
  restore) restore ;;
  *) echo "usage: sh tvtune.sh scan|apply|launcher|replace-home|restore"; exit 2 ;;
esac
