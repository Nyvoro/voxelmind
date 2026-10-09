#!/bin/bash
# Production-style dedicated-server boot smoke test for every Minecraft target.
#
#   tools/smoke-servers.sh                 all targets from settings.gradle
#   tools/smoke-servers.sh 1.21.1 26.2     only these targets
#
# Runs the REMAPPED release jar (dist/voxelmind-local-<mod>+<mc>.jar, from tools/build-all.sh) in a
# plain Fabric server directory under build/smoke/<mc>/ - the same way a player's server runs it:
# vanilla server jar (fetched by the Fabric launcher), Fabric loader at the target's minimum version,
# the Fabric API jar of that target, our jar in mods/. This is what proves the mixin remapping
# for the intermediary-mapped 1.21.x targets, which a Gradle dev run (Mojang names) cannot.
#
# Per target: wait for "Done (", send two console commands (vml list, stop), then check the log:
# "VoxelMind Local loaded" must be present and there must be no mixin / class-loading / mod-loading
# errors. Prints a table; exit code 1 if any target fails. Downloads are cached in build/smoke-cache/.
#
# JDK 21 (1.21.x) and JDK 25 (26.x): set JAVA21 / JAVA25 to the java executables, otherwise the usual
# Windows install locations and then PATH are tried.

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BOOT_TIMEOUT="${SMOKE_BOOT_TIMEOUT:-300}"
STOP_TIMEOUT="${SMOKE_STOP_TIMEOUT:-60}"
LAUNCHER_INSTALLER="1.0.1"
MOD_VERSION="$(grep -E '^mod_version=' gradle.properties | cut -d= -f2 | tr -d '\r ')"
BAD_PATTERN='Mixin apply failed|InvalidInjectionException|MixinApplyError|MixinTransformerError|MixinPreProcessorException|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|IncompatibleClassChangeError|AbstractMethodError|Failed to load mod|Incompatible mod set|Could not execute entrypoint|\[mixin\].*(ERROR|FATAL)|Exception in thread|Encountered an unexpected exception|Mixin transformation of'

if [ "$#" -gt 0 ]; then
  VERSIONS=("$@")
else
  mapfile -t VERSIONS < <(grep -o 'versions([^)]*)' settings.gradle | grep -o '"[^"]*"' | tr -d '"')
fi

find_java() { # $1 = major version, $2 = override from the environment
  if [ -n "${2:-}" ]; then echo "$2"; return; fi
  local c
  for c in "C:/Program Files/Java/jdk-$1"*/bin/java.exe "C:/Program Files/Eclipse Adoptium/jdk-$1"*/bin/java.exe "C:/Program Files/Java/jdk$1"*/bin/java.exe; do
    [ -x "$c" ] && { echo "$c"; return; }
  done
  echo java
}
JAVA21="$(find_java 21 "${JAVA21:-}")"
JAVA25="$(find_java 25 "${JAVA25:-}")"

prop() { grep -E "^$2=" "versions/$1/gradle.properties" | cut -d= -f2 | tr -d '\r '; }
fetch() { # $1 = url, $2 = target file (cached)
  [ -s "$2" ] && return 0
  mkdir -p "$(dirname "$2")"
  curl -fsSL -o "$2.part" "$1" && mv "$2.part" "$2"
}

CACHE="$ROOT/build/smoke-cache"
OUT="$ROOT/build/smoke"
mkdir -p "$CACHE" "$OUT"
declare -a ROWS
FAILED=0
PORT=25700

for v in "${VERSIONS[@]}"; do
  PORT=$((PORT + 1))
  DIR="$OUT/$v"
  LOG="$DIR/server.log"
  GO="$DIR/send-commands.flag"
  MOD_JAR="dist/voxelmind-local-$MOD_VERSION+$v.jar"
  loader="$(prop "$v" loader_version)"
  fabric_api="$(prop "$v" fabric_version)"
  java_major="$(prop "$v" java_version)"
  if [ "$java_major" = 25 ]; then JAVA="$JAVA25"; else JAVA="$JAVA21"; fi
  rm -rf "$DIR"
  mkdir -p "$DIR/mods"
  start=$(date +%s)
  status="FAIL"
  note=""

  if [ ! -f "$MOD_JAR" ]; then
    note="$MOD_JAR missing (run tools/build-all.sh)"
  elif ! fetch "https://meta.fabricmc.net/v2/versions/loader/$v/$loader/$LAUNCHER_INSTALLER/server/jar" "$CACHE/launch-$v-$loader.jar"; then
    note="could not download the Fabric server launcher"
  elif ! fetch "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$fabric_api/fabric-api-$fabric_api.jar" "$CACHE/fabric-api-$fabric_api.jar"; then
    note="could not download Fabric API $fabric_api"
  else
    cp "$CACHE/launch-$v-$loader.jar" "$DIR/fabric-server-launch.jar"
    cp "$CACHE/fabric-api-$fabric_api.jar" "$DIR/mods/"
    cp "$MOD_JAR" "$DIR/mods/"
    # Reuse the vanilla server jar and libraries the Fabric launcher fetched for this version earlier.
    [ -d "$CACHE/dotfabric-$v" ] && cp -r "$CACHE/dotfabric-$v" "$DIR/.fabric"
    echo "eula=true" > "$DIR/eula.txt"
    cat > "$DIR/server.properties" <<EOF
online-mode=false
server-port=$PORT
level-type=minecraft\:flat
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
max-tick-time=-1
enable-status=false
EOF
    feeder() { while [ ! -f "$GO" ]; do sleep 1; done; echo "vml list"; sleep 3; echo "stop"; }
    ( cd "$DIR" && exec "$JAVA" -Xmx2G -jar fabric-server-launch.jar nogui < <(feeder) > "$LOG" 2>&1 ) &
    SERVER_PID=$!
    booted=0
    while [ $(( $(date +%s) - start )) -lt "$BOOT_TIMEOUT" ]; do
      if grep -q 'Done (' "$LOG" 2>/dev/null; then booted=1; break; fi
      if ! kill -0 "$SERVER_PID" 2>/dev/null; then note="server exited before Done"; break; fi
      sleep 2
    done
    if [ "$booted" = 1 ]; then
      sleep 3
      touch "$GO"
      stop_start=$(date +%s)
      while kill -0 "$SERVER_PID" 2>/dev/null && [ $(( $(date +%s) - stop_start )) -lt "$STOP_TIMEOUT" ]; do sleep 2; done
    elif [ -z "$note" ]; then
      note="no 'Done (' within ${BOOT_TIMEOUT}s"
    fi
    touch "$GO"
    kill "$SERVER_PID" 2>/dev/null
    [ ! -d "$CACHE/dotfabric-$v" ] && [ -d "$DIR/.fabric" ] && cp -r "$DIR/.fabric" "$CACHE/dotfabric-$v"
    if [ "$booted" = 1 ]; then
      loaded=$(grep -c 'VoxelMind Local loaded' "$LOG")
      bad=$(grep -cE "$BAD_PATTERN" "$LOG")
      listed=$(grep -c 'Companions: none yet' "$LOG")
      if [ "$loaded" -ge 1 ] && [ "$bad" -eq 0 ] && [ "$listed" -ge 1 ]; then
        status="OK"
      else
        [ "$loaded" -lt 1 ] && note="mod not loaded"
        [ "$listed" -lt 1 ] && note="$note; /vml list gave no answer"
        [ "$bad" -gt 0 ] && note="$note; $bad error line(s): $(grep -mE1 "$BAD_PATTERN" "$LOG" | cut -c1-140)"
      fi
    fi
  fi
  secs=$(( $(date +%s) - start ))
  [ "$status" = OK ] || FAILED=1
  ROWS+=("$(printf '%-9s %-5s %5ss  %s' "$v" "$status" "$secs" "$note")")
  echo "${ROWS[-1]}"
done

echo
echo "=== production server boot smoke (mod $MOD_VERSION) ==="
printf '%-9s %-5s %6s  %s\n' version state time note
for r in "${ROWS[@]}"; do echo "$r"; done
echo "logs: $OUT/<mc>/server.log"
exit $FAILED
