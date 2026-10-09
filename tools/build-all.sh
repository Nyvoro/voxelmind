#!/bin/bash
# Builds the release jar of every Minecraft target and verifies each one.
#
#   tools/build-all.sh                 all targets from settings.gradle
#   tools/build-all.sh 1.21.1 26.2     only these targets
#
# Per target: "Set active project to <mc>" -> :<mc>:assemble -> checks -> copy to dist/.
# Checks (the Stonecutter pitfall is a silent ~475 byte EMPTY jar with BUILD SUCCESSFUL):
#   size > 100 KB, fabric.mod.json inside with id voxelmind_local, version <mod>+<mc>,
#   depends.minecraft == <mc>, mixin config + classes present.
# Always resets the active project to the vcs version (26.2) at the end. Targets are built
# one after the other: never run two builds concurrently in one checkout.
# The 26.2 test suites are separate: see docs/VERSIONS.md (runGameTest / runClientGameTest).

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VCS_VERSION="26.2"
MIN_JAR_BYTES=102400
MOD_VERSION="$(grep -E '^mod_version=' gradle.properties | cut -d= -f2 | tr -d '\r ')"

if [ "$#" -gt 0 ]; then
  VERSIONS=("$@")
else
  mapfile -t VERSIONS < <(grep -o 'versions([^)]*)' settings.gradle | grep -o '"[^"]*"' | tr -d '"')
fi

gradle() { ./gradlew --console=plain "$@"; }
reset_active() { gradle "Set active project to $VCS_VERSION" > /dev/null 2>&1; }
trap reset_active EXIT

mkdir -p dist build/build-all
declare -a ROWS
FAILED=0

for v in "${VERSIONS[@]}"; do
  jar="versions/$v/build/libs/voxelmind-local-$MOD_VERSION+$v.jar"
  log="build/build-all/$v.log"
  status="FAIL"; note=""; size="-"
  if ! gradle "Set active project to $v" > "$log" 2>&1; then
    note="switch failed (see $log)"
  elif ! gradle ":$v:clean" ":$v:assemble" >> "$log" 2>&1; then
    note="build failed (see $log)"
  elif [ ! -f "$jar" ]; then
    note="jar missing: $jar"
  else
    bytes=$(stat -c %s "$jar")
    size="$((bytes / 1024)) KB"
    if [ "$bytes" -le "$MIN_JAR_BYTES" ]; then
      note="jar only $size - the silent empty-jar trap"
    else
      check=$(node -e '
        const { execFileSync } = require("child_process");
        const [jar, mod, mc] = process.argv.slice(1);
        const list = execFileSync("unzip", ["-Z1", jar]).toString().split(/\r?\n/);
        const meta = JSON.parse(execFileSync("unzip", ["-p", jar, "fabric.mod.json"]).toString());
        const errors = [];
        if (meta.id !== "voxelmind_local") errors.push("id " + meta.id);
        if (meta.version !== mod + "+" + mc) errors.push("version " + meta.version);
        if (meta.depends.minecraft !== mc) errors.push("depends.minecraft " + meta.depends.minecraft);
        if (!list.includes("voxelmind_local.mixins.json")) errors.push("no mixin config");
        if (!list.includes("com/voxelmind/local/VoxelMindLocal.class")) errors.push("no main class");
        if (!list.includes("com/voxelmind/local/client/VoxelMindLocalClient.class")) errors.push("no client class");
        console.log(errors.length ? errors.join("; ") : "OK java>=" + meta.depends.java.replace(">=", "") + " fabric>=" + meta.depends.fabricloader.replace(">=", ""));
      ' "$jar" "$MOD_VERSION" "$v" 2>&1)
      if [[ "$check" == OK* ]]; then
        status="OK"; note="$check"
        cp "$jar" "dist/"
      else
        note="metadata: $check"
      fi
    fi
  fi
  [ "$status" = OK ] || FAILED=1
  ROWS+=("$(printf '%-9s %-5s %8s  %s' "$v" "$status" "$size" "$note")")
  echo "${ROWS[-1]}"
done

echo
echo "=== build matrix (mod $MOD_VERSION) ==="
printf '%-9s %-5s %8s  %s\n' version state size note
for r in "${ROWS[@]}"; do echo "$r"; done
echo "jars: dist/voxelmind-local-$MOD_VERSION+<mc>.jar"
exit $FAILED
