# VoxelMind Local

AI beings for Minecraft (Fabric) that run entirely inside the mod: a **companion** with a real player body, a **builder** fairy and a **god** game master.
- No VoxelMind server and no VoxelMind account are needed.
- The player chooses how they think:
  - Player2 (free daily AI power).
  - VoxelMind Sparks: our prepaid API, see `../voxelmind-api`.
  - Their own OpenRouter account (in-game OAuth sign-in or a pasted key).
  - Any OpenAI-compatible endpoint such as Ollama or LM Studio.
- Supported Minecraft versions: see [docs/VERSIONS.md](docs/VERSIONS.md).
- German test guide: [ANLEITUNG.md](ANLEITUNG.md).

## Architecture

The product constitution (`../AGENTS.md`) holds unchanged:

```text
Minecraft → code observes and condenses facts → LLM sees facts, conversation, tools
→ LLM decides freely → code executes the chosen tool → the real result becomes a new fact
```

- **Body** (`body/`): `CompanionPlayer` is a real `ServerPlayer` with a packet-dropping `CompanionConnection`. It joins through `PlayerList.placeNewPlayer`, so Minecraft saves its inventory and position, every client renders it as a player, and it takes damage, eats, picks up items and dies like one. Real players are simulated by their client; this body runs its own physics (`doTick`) each tick from `Motor` inputs. A mixin makes respawn create a companion body again; another keeps companions out of the sleep count.
- **Navigation**: `PathFinder` is A* over standing positions (walk, jump, drop, swim). When no walking route exists it plans through natural terrain with the real break time as cost and builds pillar steps with carried blocks; builds in progress are protected. `Navigator` executes paths, mines what is in the way and re-plans when stuck.
- **Actions** (`action/`): one `BodyAction` per body tool, ticked until it ends as done, failed or preempted, with a result text written from what the world shows (inventory deltas, positions, reasons). Tool contracts state effects and consumption; choices of what to consume (food, fuel, building material) stay with the model.
- **Mind** (`mind/`): events wake two parallel lanes like the hosted brain did — an action lane choosing exactly one body tool, and a speech lane choosing `say` or `stay_silent`. The prompt has the hosted brain's blocks and frames: [IDENTITY], [SENSORY], [PREVIOUSLY], [ABOUT OWNER], [TIMELINE]. Long-term memory is rewritten by the model every 24 events with the hosted brain's memory prompt. `DecisionLog` is passive.
- **Companion/Manager** (`companion/`): per-world state in `<world>/voxelmind-local/`, summon/dismiss, rejoin with the owner, chat hearing within 48 blocks, whispers, borrowed skins.
- **LLM** (`mind/LlmClient`): OpenAI-compatible chat completions; on OpenRouter one provider at a time (`providerOrder`, measured for the default model only), failing over only on provider errors. A global request budget per minute is the only cost guard. Models that refuse reasoning switched off get the lowest effort.
- **Worlds**: `CompanionState.scope` is `world` (file in `<world>/voxelmind-local/`) or `global` (file in `<gameDir>/voxelmind-local/companions/`, loaded by every world; a world file wins on an id clash). Inventory and position stay in each world's `playerdata`. Entering a different world than last time becomes a timeline fact; [IDENTITY] states the scope.
- **Modes**: `CompanionState.mode` is `normal` or `hardcore`. A hardcore death sets `fallenAt`; instead of the respawn packet the body leaves the world (`CompanionManager.fall`) and `summon` refuses it. The mode is a fact line in [IDENTITY]; nothing in code makes the companion play more carefully.
- **Menu** (`client/`, `net/`): `CompanionsScreen`, `CompanionEditScreen`, `MonitorScreen`, `MemoriesScreen` repaint the original mod's look (`client/ui/Ui`, click regions registered while painting). The client asks for a `snapshot` payload every 2 s while a menu screen is open; the server answers with facts read from the world (`net/Snapshots`). Every change goes through the normal `/vml` commands, so the menu adds no second code path for behaviour.
- **Builder and God** (`hosted/`): the hosted brain's two console-power minds, ported with their frames, tool texts and fact blocks.
  - `HostedMind` runs one model call per turn, which may carry several tool calls. Complete results come back as timeline facts and wake the same mind. Reasoning is on, as it was hosted ("low" for the God).
  - `BuilderMind`:
    - `fill` resolves anchors to boxes and chunks them into fills of at most 32,768 cells.
    - `build` makes a separate geometry call, parsed and placed in front of the owner layer by layer.
    - `undo` uses block-state and block-entity snapshots (`EditHistory`).
    - Also `find_blocks`, `lookup` (Minecraft Wiki) and a NoAI allay-type body (`SpiritBody`) at the owner's shoulder.
  - `GodMind` has `run`, `sequence` (server-clock command chains with per-step receipts), `rule` (standing event → command laws, rate-limited), `rain`, `move_entity`, `summon_entity`, `cleanup`, campaign and chaos settings. Its plain text stays with it, as hosted: only `say` speaks, and the timeline says what became of the text.
  - Commands run through `CommandRunner` as a captured console source. `CommandPolicy` refuses admin verbs and harmful verbs against `@a`; that is a technical safety boundary. `WorldResets` restores changed gamerules and effects when a God leaves, and `WorldBackup` zips the world once a day before a session.
  - Only the singleplayer host or an operator may bring either kind (`CompanionManager.mayHost`).
- **VoxelMind Sparks** (`provider/VoxelMindApi`, `client/VoxelMindSignIn`):
  - Sign-in with the Minecraft account itself: the client joins a server id at Mojang (`sessionService().joinServer`), and the API asks Mojang `hasJoined`. No password or Mojang token leaves the PC.
  - The resulting token (`voxelmindKey`) is only ever sent to the VoxelMind API.
  - The model is fixed (`voxelmind/standard`), so the API routes the provider.
  - `402` means the Sparks are used up; it becomes a chat notice with a link to packs and prices (`VmlCommand.sparksNotice`).
  - `/vml sparks redeem <code>` posts a one-time code to `/v1/redeem` for the signed-in account; `VoxelMindApi.accountUrl` is the private account page (token in the URL fragment, never sent to the website's server).
- **AI label** (`CompanionManager.labelAsAi`, `tellAboutAi`): companion bodies join the scoreboard team `voxelmind_ai` with the prefix `[AI] ` (name tag, tab list, chat); hosted minds' chat lines start with `[AI]`; every human player is told once per session which AI characters are in the world. A transparency duty (Art. 50 AI Act), not behaviour.
- **Player2** (`provider/Player2`): `api.player2.game/v1/chat/completions` is OpenAI-compatible with tools but has no model field (Player2 chooses). Sign-in gets a `p2Key` from the running Player2 app (`127.0.0.1:4315` or `:4316`, `/v1/login/web/{clientId}`) or the OAuth device flow; it needs the Game Client ID (`Player2.CLIENT_ID` or config `player2ClientId`). Requests carry `player2-game-key: <Client ID>` (as Player2's own Minecraft engine does); while a companion is in the world a `/health` heartbeat goes out once a minute, which is how Player2 measures play time for its revenue share. `player2Key` is only ever sent to Player2.
- **Bring your own key** (`provider/`, `client/`): `OpenRouterAccount` runs the OpenRouter OAuth PKCE sign-in (callback on a loopback port for the settings screen, the code-copy variant for `/vml login` on dedicated servers) and reads credit from `/key` and `/credits`. `ModelCatalog` reads `/models` of any endpoint and estimates cost per hour of play from list prices. `SettingsScreen` and `ModelPickerScreen` are vanilla-widget screens, opened through the `open_settings` payload that `/vml setup` sends to the host of a singleplayer or LAN world. The OpenRouter key (`apiKey`) is only ever sent to OpenRouter; other endpoints use `endpointKey`.

## Build and test

Requires JDK 21 and JDK 25 (Gradle toolchains) and the Gradle cache the main mod uses (`GRADLE_USER_HOME=V:\Android\Gradle`).

One source tree builds 16 jars (Minecraft 1.21 to 26.2, Stonecutter); the checked-in source is the 26.2 state. `bash tools/build-all.sh` builds and checks all of them into `dist/`; versions, smoke tests and how to add a version are in [docs/VERSIONS.md](docs/VERSIONS.md). For 26.2:

```sh
./gradlew :26.2:build               # versions/26.2/build/libs/voxelmind-local-<ver>+26.2.jar
./gradlew :26.2:runGameTest         # 37 headless server tests: body, tools, settings, Builder fill/undo, God rain/sequence/rules, Sparks config, AI label
./gradlew :26.2:runClientGameTest   # real client: summon, skin, chat, settings, model list, menu, monitor, memories, create companion/builder/god by clicks, screenshots
./gradlew :26.2:runServer           # dedicated dev server in run/ (port 25599, offline mode)
./gradlew :26.2:runServer -PvoxelmindApi=http://127.0.0.1:8797/v1     # against a local `wrangler dev` VoxelMind API (also runClient, runGameTest)
./gradlew :26.2:runGameTest -PvoxelmindApi=http://127.0.0.1:8797/v1   # with VML_E2E_VOXELMIND_TOKEN: one real call through a local VoxelMind API
```

End-to-end with a real model: start `runServer` with `VOXELMIND_LOCAL_API_KEY` set, then drive an owner with `node tools/e2e/owner-bot.cjs tools/e2e/scenarios/<file>.json Lysa`. For Builder and God the bot must be an operator (`run/ops.json`), as in `hosted-builder-god.json` (uses the patched mineflayer from `../voxelmind-platform`). `tools/e2e/map.cjs` prints built structures; `tools/e2e/provider_bench.py` compares OpenRouter providers.

Config: `config/voxelmind-local.json` (`apiKey`, `endpointKey`, `baseUrl`, `model`, `memoryModel` (empty = same as `model`), `providerOrder`, `language`, `maxRequestsPerMinute`, `requestTimeoutSeconds`, `localRequestTimeoutSeconds`, `logPrompts`, …).
