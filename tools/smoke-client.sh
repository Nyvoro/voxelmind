#!/bin/bash
# Client smoke test: a real Minecraft client (Gradle dev run, Mojang names) joins a flat singleplayer
# world with the mod and works through src/clientSmoke (dev-only, never packaged):
#   summon a companion with /vml summon -> open every menu screen (a render error crashes the client)
#   -> real body actions without any language model (walk, craft x4, smelt, mine, place, chest, dig,
#   survey, drop; see ServerSmoke) -> dismiss -> quit.
#
#   tools/smoke-client.sh                    1.21.11 1.21.1 1.21.8 (the required minimum set)
#   tools/smoke-client.sh 26.2 1.21.4        only these targets
#   tools/smoke-client.sh --all              all targets from settings.gradle
#
# SMOKE_SHOTS=1 additionally photographs the game window at every screen (tools/capture-shots.ps1) into
# build/client-smoke/<mc>/shots/ - the window must stay visible and uncovered while it runs.
# It opens a game window for about two minutes per target. The world comes from the server smoke
# (build/smoke/<mc>/world, created on demand by tools/smoke-servers.sh). Logs: build/client-smoke/<mc>/.
# Compare the "VML-SMOKE body" lines between targets: they should read the same everywhere.
# Always resets the active project to the vcs version (26.2) at the end.

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VCS_VERSION="26.2"
RUN_TIMEOUT="${SMOKE_CLIENT_TIMEOUT:-600}"
BAD_PATTERN='Mixin apply failed|InvalidInjectionException|MixinApplyError|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|IncompatibleClassChangeError|AbstractMethodError|Failed to load mod|Could not execute entrypoint|Crash report|Minecraft Crash Report|Exception in thread|Rendering screen|Exception ticking world'

if [ "$#" -eq 0 ]; then
  VERSIONS=(1.21.11 1.21.1 1.21.8)
elif [ "$1" = "--all" ]; then
  mapfile -t VERSIONS < <(grep -o 'versions([^)]*)' settings.gradle | grep -o '"[^"]*"' | tr -d '"')
else
  VERSIONS=("$@")
fi

gradle() { ./gradlew --console=plain "$@"; }
reset_active() { gradle "Set active project to $VCS_VERSION" > /dev/null 2>&1; }
trap reset_active EXIT

kill_client() { # stops the Minecraft JVM of this run dir (and nothing else)
  powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.Name -like 'java*' -and \$_.CommandLine -like '*client-smoke*$1*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" > /dev/null 2>&1
}

mkdir -p build/client-smoke
declare -a ROWS
FAILED=0

for v in "${VERSIONS[@]}"; do
  DIR="$ROOT/build/client-smoke/$v"
  LOG="$DIR/gradle.log"
  rm -rf "$DIR"
  mkdir -p "$DIR/run/saves"
  start=$(date +%s)
  status="FAIL"
  note=""

  if [ ! -d "build/smoke/$v/world" ]; then
    bash tools/smoke-servers.sh "$v" > "$DIR/world-prep.log" 2>&1
  fi
  if [ ! -d "build/smoke/$v/world" ]; then
    note="no world (server smoke failed, see $DIR/world-prep.log)"
  else
    cp -r "build/smoke/$v/world" "$DIR/run/saves/smoke"
    # Skip first-run screens; keep the window quiet.
    printf 'tutorialStep:none\nonboardAccessibility:false\npauseOnLostFocus:false\nskipMultiplayerWarning:true\nlang:en_us\n' > "$DIR/run/options.txt"
    if ! gradle "Set active project to $v" > "$DIR/switch.log" 2>&1; then
      note="switch failed"
    else
      gradle ":$v:runClientSmoke" > "$LOG" 2>&1 &
      GRADLE_PID=$!
      SHOTS_PID=""
      if [ "${SMOKE_SHOTS:-0}" = 1 ]; then
        powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w tools/capture-shots.ps1)" -Log "$(cygpath -w "$LOG")" -OutDir "$(cygpath -w "$DIR/shots")" -TimeoutSeconds "$RUN_TIMEOUT" > "$DIR/shots.log" 2>&1 &
        SHOTS_PID=$!
      fi
      finished=0
      while [ $(( $(date +%s) - start )) -lt "$RUN_TIMEOUT" ]; do
        if grep -q 'VML-SMOKE done' "$LOG" 2>/dev/null; then finished=1; break; fi
        if grep -q 'BUILD FAILED' "$LOG" 2>/dev/null; then note="gradle failed"; break; fi
        if ! kill -0 "$GRADLE_PID" 2>/dev/null; then note="gradle exited early"; break; fi
        sleep 3
      done
      if [ "$finished" = 1 ]; then
        # Give the client time to stop by itself.
        for _ in $(seq 1 20); do kill -0 "$GRADLE_PID" 2>/dev/null || break; sleep 2; done
      elif [ -z "$note" ]; then
        note="no 'VML-SMOKE done' within ${RUN_TIMEOUT}s"
      fi
      kill_client "$v"
      kill "$GRADLE_PID" 2>/dev/null
      [ -n "$SHOTS_PID" ] && { wait "$SHOTS_PID" 2>/dev/null || kill "$SHOTS_PID" 2>/dev/null; }
      if [ "$finished" = 1 ]; then
        fails=$(grep -c 'VML-SMOKE FAIL' "$LOG")
        bad=$(grep -cE "$BAD_PATTERN" "$LOG")
        bodydone=$(grep -c 'VML-SMOKE body done failures=0' "$LOG")
        screens=$(grep -c 'VML-SMOKE ok' "$LOG")
        if [ "$fails" -eq 0 ] && [ "$bad" -eq 0 ] && [ "$bodydone" -ge 1 ] && grep -q 'VML-SMOKE done failures=0' "$LOG"; then
          status="OK"; note="$screens steps ok, body actions ok"
        else
          note="$fails FAIL line(s), $bad error line(s), body done=$bodydone"
          [ "$fails" -gt 0 ] && note="$note; $(grep -m1 'VML-SMOKE FAIL' "$LOG" | cut -c1-140)"
          [ "$bad" -gt 0 ] && note="$note; $(grep -mE1 "$BAD_PATTERN" "$LOG" | cut -c1-140)"
        fi
      fi
    fi
  fi
  secs=$(( $(date +%s) - start ))
  grep 'VML-SMOKE' "$LOG" 2>/dev/null | sed 's/^.*VML-SMOKE/VML-SMOKE/' > "$DIR/smoke-lines.txt"
  [ "$status" = OK ] || FAILED=1
  ROWS+=("$(printf '%-9s %-5s %5ss  %s' "$v" "$status" "$secs" "$note")")
  echo "${ROWS[-1]}"
done

echo
echo "=== client smoke ==="
printf '%-9s %-5s %6s  %s\n' version state time note
for r in "${ROWS[@]}"; do echo "$r"; done
echo "VML-SMOKE lines: build/client-smoke/<mc>/smoke-lines.txt"
exit $FAILED
