# VoxelMind Local on 16 Minecraft versions

One source tree builds a separate jar for every Minecraft version the old VoxelMind mod shipped for.
The setup is the one of `voxelmind-mod`: Stonecutter 0.5.2, Fabric Loom 1.15.5 on Gradle 9.4.0, per
target `versions/<mc>/gradle.properties`. The checked-in source is the **26.2 state** (Stonecutter
`vcsVersion = "26.2"`), so the code you read in `src/` is exactly the 26.2 code; older targets are
derived from it.

Use the jar whose `+<mc>` suffix matches the game exactly; each jar depends on that exact Minecraft
version, on Fabric API and on the Fabric loader listed below. Minecraft 1.21.x runs on Java 21, 26.x on Java 25.

## Version matrix

| Minecraft | Java | Fabric loader (min) | Fabric API (min) | Mappings | Jar |
|---|---|---|---|---|---|
| 1.21 | 21 | 0.19.5 | 0.116.12+1.21.1 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.jar` |
| 1.21.1 | 21 | 0.16.9 | 0.116.12+1.21.1 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.1.jar` |
| 1.21.2 | 21 | 0.19.5 | 0.106.1+1.21.2 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.2.jar` |
| 1.21.3 | 21 | 0.19.5 | 0.114.1+1.21.3 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.3.jar` |
| 1.21.4 | 21 | 0.16.9 | 0.113.0+1.21.4 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.4.jar` |
| 1.21.5 | 21 | 0.16.10 | 0.119.5+1.21.5 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.5.jar` |
| 1.21.6 | 21 | 0.16.13 | 0.128.2+1.21.6 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.6.jar` |
| 1.21.7 | 21 | 0.19.5 | 0.129.0+1.21.7 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.7.jar` |
| 1.21.8 | 21 | 0.16.13 | 0.136.1+1.21.8 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.8.jar` |
| 1.21.9 | 21 | 0.19.5 | 0.134.1+1.21.9 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.9.jar` |
| 1.21.10 | 21 | 0.19.5 | 0.138.4+1.21.10 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.10.jar` |
| 1.21.11 | 21 | 0.19.5 | 0.141.6+1.21.11 | Mojang, remapped | `voxelmind-local-0.4.2+1.21.11.jar` |
| 26.1 | 25 | 0.19.5 | 0.145.1+26.1 | none (unobfuscated) | `voxelmind-local-0.4.2+26.1.jar` |
| 26.1.1 | 25 | 0.19.5 | 0.145.4+26.1.1 | none (unobfuscated) | `voxelmind-local-0.4.2+26.1.1.jar` |
| 26.1.2 | 25 | 0.19.5 | 0.155.2+26.1.2 | none (unobfuscated) | `voxelmind-local-0.4.2+26.1.2.jar` |
| 26.2 | 25 | 0.19.5 | 0.159.0+26.2 | none (unobfuscated) | `voxelmind-local-0.4.2+26.2.jar` |

The Fabric API / loader versions are the ones of `voxelmind-mod/versions/*/gradle.properties`
(Minecraft 1.21 uses the Fabric API build `0.116.12+1.21.1`, which also runs on 1.21). The mod is
"client and server": it runs in the integrated or a dedicated server, so dedicated servers need Fabric API too.

Verification status of 0.4.1 (09.10.2026; details below):

| Check | 1.21 .. 1.21.10 | 1.21.11 | 26.1, 26.1.1, 26.1.2 | 26.2 |
|---|---|---|---|---|
| Release jar builds, metadata checked (`tools/build-all.sh`) | yes | yes | yes | yes |
| Production dedicated-server boot, remapped jar (`tools/smoke-servers.sh`) | yes | yes | yes | yes |
| Client smoke: every screen, real body actions, summon after the owner went 300 blocks away (`tools/smoke-client.sh`) | 1.21.1 and 1.21.8 run for 0.4.1 (all in 0.4.0) | yes | 0.4.0 only | yes |
| 38 server game tests (`runGameTest`) | not available | yes | 0.4.0 only (36 then) | yes |
| Long client game test (`runClientGameTest`) | not available | not available | not available | yes |

## Build commands

All commands run in `voxelmind-local/`. Gradle caches live on the V: drive, JDK 21 and JDK 25 must both be
installed (Gradle toolchains find them). The first Gradle call configures 16 Loom projects and can take
minutes (the first time ever: ~9 min), later calls ~10 s.

```bash
export GRADLE_USER_HOME=V:/Android/Gradle

# one target (the active Stonecutter project decides which comment blocks of src/ are live)
./gradlew "Set active project to 1.21.1"
./gradlew :1.21.1:assemble          # jar: versions/1.21.1/build/libs/voxelmind-local-0.4.1+1.21.1.jar
./gradlew "Reset active project"    # back to 26.2 - always do this before you edit or commit

# all 16 targets, checked, copied to dist/ (6 minutes with a warm daemon)
bash tools/build-all.sh                      # or: bash tools/build-all.sh 1.21.1 26.2

# 26.2 tests (26.2 is the default active project)
./gradlew :26.2:runGameTest                  # 38 server game tests
./gradlew :26.2:runClientGameTest            # the long client test with screenshots
./gradlew :26.2:runServer                    # dev server in voxelmind-local/run (port 25599, owner-bot scenarios)
./gradlew :26.2:build                        # jar + game tests

# smoke tests over all targets
bash tools/smoke-servers.sh                  # production-style server boot of dist/ jars, table at the end (about 10 minutes)
bash tools/smoke-client.sh                   # client smoke for 1.21.11 1.21.1 1.21.8
bash tools/smoke-client.sh --all             # ... for all 16 (about two minutes each, opens a game window)
SMOKE_SHOTS=1 bash tools/smoke-client.sh 1.21.1   # also photographs every screen into build/client-smoke/<mc>/shots
```

Never build two targets at the same time in one checkout: switching the active project rewrites `src/`.
`tools/build-all.sh` and the smoke scripts always reset the active project to 26.2 at the end. If a run was
killed, run `./gradlew "Reset active project"` before touching the source (`stonecutter.gradle` must read
`stonecutter.active "26.2"` when you commit; do not use the generated `chiseledBuild` task, it is the empty-jar trap).
The old `./gradlew build` / `runGameTest` from the README now need the `:26.2:` prefix.

Silent-empty-jar trap (inherited from the old mod): with a wrong source path Stonecutter 0.5 can finish
with BUILD SUCCESSFUL and a ~475 byte jar. `build-all.sh` fails any jar under 100 KB and checks that
`fabric.mod.json` (id, version, `depends.minecraft`), the mixin config and the main/client classes are inside.

Fabric API jars for the production smoke and the downloads of the Fabric server launcher are cached in
`build/smoke-cache/`; the generated worlds, logs and run directories are under `build/`.

## How the targets are derived from the 26.2 source

1. **Renames** (`tools/versioned-renames.gradle`): `prepareVersionedSources` copies `src/main/java` to
   `build/versionedSources` and applies line-by-line text rules (literal or `re:` regex) for the target. This
   covers everything that is only another name for the same thing: `Identifier`/`ResourceLocation`,
   `GuiGraphicsExtractor`/`GuiGraphics`, `PayloadTypeRegistry.clientboundPlay()`/`playS2C()`, Fabric API renames,
   click/hover event constructors, `GameProfile.name()`, permission levels and so on. The rules are grouped
   by the version in which the new spelling appeared (26.2, 26.1, 1.21.11, 1.21.9, 1.21.6, 1.21.5, 1.21.4, 1.21.2).
2. **`compat/Compat.java`**: small static helpers for calls whose shape differs (recipe API, furnace fuel,
   player-data loading, game profile with skin textures, `ServerPlayer.level()`, loaded packet, ingredient items).
   The 26.2 body is checked in; the other bodies sit in `//?` Stonecutter blocks.
3. **`//?` Stonecutter blocks** (`//? if >=1.21.9 {` ... `//?} else {` ... `//?}`) where code, not just a name,
   differs. The checked-in text is always the 26.2 branch; Stonecutter comments/uncomments blocks when the active
   project is switched.

Write new code in the 26.2 names. Then, for every target that no longer compiles, in this order of preference:
add a rename rule; add a `Compat` helper; add a `//?` block at the call site.
To see all errors of a target: `./gradlew "Set active project to X"` then `./gradlew :X:compileJava`.

## Where version-specific code lives

| Place | What differs |
|---|---|
| `tools/versioned-renames.gradle` | all text renames above (the file is the list; every rule block starts with the version that introduced the new spelling) |
| `src/main/java/.../compat/Compat.java` | `assemble` (recipe result, >=26.1 vs 1.21.x registry argument), `ingredientItems`, `ingredients`, `shapedCells` (crafting recipes, 1.21.2 / 1.21.4 changes), `smeltInput`, `cookingTime`, `isFuel`, `burnDuration` (1.21.2), `texturedProfile` (authlib 7, 1.21.9), `acceptPlayerLoad` (>=1.21.4), `level(ServerPlayer)` (>=1.21.6), `loadPlayerData` (1.21.6 and 1.21.9 changes) |
| `body/CompanionPlayer.java` | `hurtServer(level, source, damage)` from 1.21.2, `hurt(source, damage)` before |
| `client/CompanionEditScreen.java` | persona box: `MultiLineEditBox.builder()` from 1.21.6; before, the constructor, and the 500-character limit is enforced by the value listener (a limit on the old box draws a second counter) |
| `client/ModelPickerScreen.java` | row rendering/click handling: `renderContent` + `MouseButtonEvent` from 1.21.9; before, `render(index, top, left, ...)`, rows drawn at the row origin and a 250 ms double-click timer instead of the `doubleClick` flag |
| `client/PanelScreen.java` | `mouseClicked(MouseButtonEvent, boolean)` from 1.21.9, `mouseClicked(x, y, button)` before |
| `client/ui/Ui.java` | `wordWrap`: `textWithWordWrap` (26.1+) vs a shadowed line loop (the old `drawWordWrap` draws without shadow) |
| `client/VoxelMindSignIn.java`, `client/VoxelMindLocalClient.java` and the many plain renames | session service (`mc.services()` from 1.21.9), key mapping category (a `Category` object from 1.21.9, a string before), `Screens.getWidgets/getButtons`, `sendSystemMessage` |
| `src/main/resources/fabric.mod.json`, `build.gradle` | `depends.minecraft` = exact target version, Java and loader minimum per target, Loom plugin id (`fabric-loom` for 1.21.x with Mojang mappings and a `jspecify` compile-only dependency, `net.fabricmc.fabric-loom` for the unobfuscated 26.x) |
| `versions/<mc>/gradle.properties` | `minecraft_version`, `fabric_version`, `loader_version`, `java_version` |
| `src/main/resources/voxelmind_local.mixins.json`, `mixin/*.java` | no per-version code: the two mixins (`PlayerList.respawn`, `SleepStatus.update`) apply unchanged on all 16 targets; Loom remaps the annotation strings for 1.21.x |
| `src/gametest` | game tests only run on 1.21.11 and the 26.x targets (see below); the long client test only on 26.2 |

## Tests and what they prove

- **`tools/build-all.sh`**: builds the 16 jars sequentially and checks size, metadata, mixin config and main/client classes.
- **`tools/smoke-servers.sh`**: for every target starts the release jar from `dist/` in a plain Fabric server directory
  (Fabric server launcher at the target's minimum loader, vanilla server jar, the target's Fabric API) and waits for
  `Done (`, runs `vml list` and `stop`, and fails on mixin / class loading / mod loading errors or a missing
  "VoxelMind Local loaded". Because it runs the **remapped** jar it proves the intermediary remapping (mixin targets
  and all) of the 1.21.x targets, which a Gradle dev run cannot.
- **`tools/smoke-client.sh`** (code in `src/clientSmoke`, a dev-only mod that is never packaged): a real client joins a flat
  singleplayer world (dev run), summons a companion with `/vml summon`, opens every screen (companions, edit x2,
  settings, model picker, monitor, memories, pause menu with the VoxelMind button), then runs the body without any language
  model: walk, craft planks/sticks/table/pickaxe, a failing craft, smelt, collect, mine, place, chest, dig, survey, drop.
  The `VML-SMOKE body ...` lines are written to `build/client-smoke/<mc>/smoke-lines.txt`; apart from coordinates they are
  identical on all 16 versions. A render error would crash the client and fail the run. With `SMOKE_SHOTS=1` it also photographs
  each screen for visual comparison against 26.2.
- **26.2 game tests**: `runGameTest` (38 server tests: body, tools, settings, hosted minds) and `runClientGameTest`
  (one long client test with screenshots) - must stay green; they also run for 1.21.11 and 26.1 / 26.1.1 / 26.1.2
  (server tests). Older targets differ in `GameTestHelper` (Component messages instead of Strings from 1.21.11) and, below
  1.21.5, in how tests are registered; the client smoke covers the same body actions there.

## Known differences on older versions

- Default skins: the fallback face for a companion without a borrowed skin is the game's own default skin for its id; the game picks
  differently per version, so the same companion can show a different default head. Below 1.21.2 the "no companion" placeholder is
  the default skin for the nil UUID.
- Edit screen on 1.21.5 and older: the 500-character persona limit is applied by truncating in the value listener; typing in the middle at the
  limit behaves slightly differently from the vanilla limit.
- Model list on 1.21.8 and older: a double click is a second click on the same row within 250 ms (the game passed a flag from 1.21.9).
- Dark-gray text-field hints are emulated with a style on 1.21.8 and older (the game applies it itself from 1.21.9).
- Fabric API for 1.21 is the `0.116.12+1.21.1` build (as in the old mod); a player on 1.21 needs exactly that line of Fabric API or newer
  1.21.1-line builds that still declare 1.21.
- `fabric.mod.json` states the loader minimum of the old mod per target (0.16.9 .. 0.19.5).
- Game tests do not run on 1.21 .. 1.21.10; the long client test runs only on 26.2.

## Adding a new Minecraft version

1. `versions/<mc>/gradle.properties` (copy a neighbour, set `minecraft_version`, `fabric_version`, `loader_version`, `java_version`).
2. Add the version to `settings.gradle` (`versions(...)`, in order).
3. Switch to it, compile, fix with rename rules / `Compat` / `//?` blocks as above, build, run the smoke scripts.
4. Add it to `voxelmind-werkstatt/ops/curseforge.config.json` (`surfaces.local.mcVersions`, `gameVersions`, `javaIdsByMinecraft`).

## Release upload

`voxelmind-werkstatt/ops/curseforge.config.json`, surface `local`, lists the 16 versions with the per-version jar path
`voxelmind-local/versions/{mc}/build/libs/voxelmind-local-{version}+{mc}.jar` (`dist/` holds the same jars). Check with:

```bash
cd voxelmind-werkstatt/ops
node curseforge-release.mjs --surface local --version 0.4.1 --changelog ../../voxelmind-local/CHANGELOG.md --dry-run   # beta is the default of this surface until 08.11.
```
