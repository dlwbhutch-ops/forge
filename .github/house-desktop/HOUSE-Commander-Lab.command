#!/bin/bash
# HOUSE Commander Lab macOS launcher, compatible with macOS /bin/bash 3.2.
# Diagnostics live outside the app, so reinstalling never removes them.
set -u
set -o pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd -P)" || exit 1
DATA_DIR="$HOME/.house-commander-lab"
LOG_DIR="$DATA_DIR/logs"
if ! mkdir -p "$LOG_DIR"; then
  echo "HOUSE could not create its diagnostic directory: $LOG_DIR"
  echo "Existing decks and tournament data were not changed."
  if [ -t 0 ]; then read -r -p "Press Return to close..." _answer; fi
  exit 1
fi
LOG_FILE="$LOG_DIR/macos-launch.log"

report() { printf '%s\n' "$*" | tee -a "$LOG_FILE"; }
failure() {
  report "HOUSE Commander Lab cannot start: $*"
  report "Diagnostic log: $LOG_FILE"
  report "Your decks and checkpoints remain in $DATA_DIR"
  if [ -t 0 ]; then
    printf 'Press Return to close...'
    read -r _answer
  fi
  exit 1
}

report "----- $(date) -----"
report "HOUSE startup from: $APP_DIR"
report "macOS hardware: $(uname -m)"
cd "$APP_DIR" || failure "Cannot enter extracted app folder."
[ -r "$APP_DIR/HOUSE-Commander-Lab.jar" ] || failure "HOUSE-Commander-Lab.jar missing. Extract the full ZIP; do not move the launcher alone."

if [ -x "$APP_DIR/runtime/Contents/Home/bin/java" ]; then
  JAVA="$APP_DIR/runtime/Contents/Home/bin/java"
elif [ -x "$APP_DIR/runtime/bin/java" ]; then
  JAVA="$APP_DIR/runtime/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
  report "Bundled Java missing; trying the installed Java runtime."
else
  failure "No executable Java runtime found. Extract the correct complete Mac archive."
fi

RELEASE="$APP_DIR/runtime/Contents/Home/release"
if [ -r "$RELEASE" ]; then
  ARCH="$(grep '^OS_ARCH=' "$RELEASE" | head -1)"
  report "Runtime $ARCH"
  case "$(uname -m):$ARCH" in
    arm64:*aarch64*|arm64:*arm64*|x86_64:*x86_64*) ;;
    *) report "WARNING: This package may be for the wrong Mac chip. Apple Silicon Macs use the Apple Silicon ZIP; Intel Macs use the Intel ZIP." ;;
  esac
fi

report "Checking Java runtime: $JAVA"
"$JAVA" -version >>"$LOG_FILE" 2>&1 || failure "Java could not run. Check macOS Privacy & Security settings or try the other Mac package."

report "Starting HOUSE; keeping all existing user data."
# Avoid reserving 4 GB of heap on MacBook Pro models with 8 GB of RAM.
"$JAVA" -Xms128m -Xmx3072m -Dfile.encoding=UTF-8 \
  -Dapple.laf.useScreenMenuBar=true \
  -Dapple.awt.application.name="HOUSE Commander Lab" \
  --add-opens=java.desktop/java.beans=ALL-UNNAMED \
  --add-opens=java.desktop/javax.swing.border=ALL-UNNAMED \
  --add-opens=java.desktop/javax.swing.event=ALL-UNNAMED \
  --add-opens=java.desktop/sun.swing=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.image=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.color=ALL-UNNAMED \
  --add-opens=java.desktop/sun.awt.image=ALL-UNNAMED \
  --add-opens=java.desktop/javax.swing=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.font=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.math=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  -cp "$APP_DIR/HOUSE-Commander-Lab.jar" \
  com.housecommander.desktop.HouseDesktopMain >>"$LOG_FILE" 2>&1
EXIT_CODE=$?

if [ "$EXIT_CODE" -ne 0 ]; then
  report "Java exited with code $EXIT_CODE."
  report "Last diagnostic messages:"
  tail -n 24 "$LOG_FILE"
  failure "The app closed unexpectedly (exit code $EXIT_CODE)."
fi
report "HOUSE closed normally."
exit 0
