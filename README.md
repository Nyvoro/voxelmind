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
- **Navigation**: `PathFinder` is A* over standing positions (walk, jump, drop, swim, and straight through wooden and copper doors and fence gates). When no walking route exists it plans through natural terrain with the real break time as cost, never through a block a player placed (`world/Placed`) except its own pillar steps, and builds pillar steps with carried blocks (a step straight up out of water is swum instead); no route takes more than 6 steps in a row with the head under water (half a breath); builds in progress are protected. `Navigator` executes paths, opens a closed door or gate on the next step like a player's right-click (`Hands.use`), places blocks with a sneak when the clicked block would react to a plain click (`Hands.placeClick`), closes the doors it opened once the body is past them (`go_to` with `through` ends just past a door on its far side and says whether the door is open or closed now), mines natural blocks in the way and re-plans when stuck. Iron doors are walked through only when a button or lever beside them on the body's side opens them (a lever is flipped back once past); results name doors a walk had to leave open.
- **Actions** (`action/`): one `BodyAction` per body tool (`use_block` right-clicks doors, levers, buttons and similar and reports the block state before and after), ticked until it ends as done, failed or preempted, with a result text written from what the world shows (inventory deltas, positions, reasons). Tool contracts state effects and consumption; choices of what to consume (food, fuel, building material) stay with the model.
- **Mind** (`mind/`): every event wakes **one** decision call, as Builder and God do. It offers the body tools and `say`; every body tool also takes an optional `say` argument (and `say_to`: a name or `owner` whispers, `everyone` yells), because some models (gpt-oss, Player2's free model) return exactly one tool call per response. The calls run in the order they came: the first line and the first body choice are carried out, extras become timeline notes; when a decision said nothing, its plain text is noted as unheard. A chat that arrives while a decision is in flight queues behind it. Silence is no `say`. Until 0.4.1 a speech lane and an action lane ran in parallel without knowing each other, so the companion could promise what its body never did. The prompt keeps the hosted brain's blocks ([IDENTITY], [SENSORY], [PREVIOUSLY], [ABOUT OWNER], [TIMELINE]) and the conversational part of its speech frame and ends in one [DECISION] block, which says when a say argument goes out (as the call starts, before its result) and, with nothing running, that say alone leaves the body idle until the next decision. [TIMELINE] holds the chat of the last 30 minutes (at least 24 lines, at most about 6000 characters) and the last 30 other rows, identical consecutive rows condensed with a count. Long-term memory is rewritten by the model every 24 events with the hosted brain's memory prompt. `DecisionLog` is passive: one `decision` entry per call, with `said` and `heard_by` for the line that went out. CLOCK says when someone last spoke to the companion and when it last spoke; a `stop` that ended a running action wakes the next decision like every action end. Quiet moments (timer decisions while the owner is near and active) come every 45-240 s (`idleWakeMinSeconds`/`idleWakeMaxSeconds`), and within 20-60 s while the body has nothing running and no wait.
- **Placed blocks** (`world/Placed`, `mixin/BlockItemMixin`, `mixin/ServerPlayerGameModeMixin`, `mixin/PistonBaseBlockMixin`): a mixin on `BlockItem.place` records every block a player places by hand, human or companion body, in the cell it really fills (also the other half of doors, tall plants and beds), per dimension with placer and time; the Builder's fills and builds are recorded under its name and its undo restores them. Entries follow blocks that change form in place (stripped logs, weathered or waxed copper, hardened concrete, carved pumpkins), blocks moved by pistons and falling blocks, and are forgotten when the block is broken or, lazily, when the world no longer holds it. Only blocks placed since 0.4.2 are known; God commands are not recorded. Saved as `<world>/voxelmind-local/placed.dat` (version 2) with every world save and at stop, written in the background (GZIP, palettes, temp file plus move; an unreadable file is kept aside). [SENSORY] states it literally: what is above the head (open sky, or the first block, who placed it, and the highest block of the column), with the head in water where the water above it ends, what the body stands on, the placed blocks within 8 by placer and type, placed resources apart from natural ones, and doors, gates and trapdoors with their open state. Walking never digs a placed block; mining never breaks a placed block, door, gate or trapdoor that is in the way; gather and mine_block by type take placed or planted blocks only with `include_placed`; dig and mine_block name the placers of what they broke.
- **Companion/Manager** (`companion/`): per-world state in `<world>/voxelmind-local/`, summon/dismiss, rejoin with the owner, chat hearing within 48 blocks, whispers, skins (borrowed from an account, or an own PNG from a link or `<gameDir>/voxelmind-local/skins/`, signed through MineSkin in `companion/Skins`).
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
  - Sign-in with the Minecraft account itself: the client signs a server id with the player key Mojang certifies for chat signing (`getProfileKeyPairManager()`), and the API checks Mojang's certificate offline (Mojang refuses requests from Cloudflare). No password, Mojang token or private key leaves the PC.
  - The resulting token (`voxelmindKey`) is only ever sent to the VoxelMind API.
  - The model is fixed (`voxelmind/standard`), so the API routes the provider.
  - `402` means the Sparks are used up; it becomes a chat notice with a link to packs and prices (`VmlCommand.sparksNotice`).
  - `/vml sparks redeem <code>` posts a one-time code to `/v1/redeem` for the signed-in account; `VoxelMindApi.accountUrl` is the private account page (token in the URL fragment, never sent to the website's server).
- **AI label** (`CompanionManager.labelAsAi`, `tellAboutAi`): companion bodies join the scoreboard team `voxelmind_ai` with the prefix `[AI] ` (name tag, tab list, chat); hosted minds' chat lines start with `[AI]`; every human player is told once per session which AI characters are in the world. A transparency duty (Art. 50 AI Act), not behaviour.
- **Player2** (`provider/Player2`): `api.player2.game/v1/chat/completions` is OpenAI-compatible with tools but has no model field (Player2 chooses). Sign-in gets a `p2Key` from the running Player2 app (`127.0.0.1:4315` or `:4316`, `/v1/login/web/{clientId}`) or the OAuth device flow; it needs the Game Client ID (`Player2.CLIENT_ID` or config `player2ClientId`). Requests carry `player2-game-key: <Client ID>` (as Player2's own Minecraft engine does); while a companion is in the world a `/health` heartbeat goes out once a minute, which is how Player2 measures play time for its revenue share. `player2Key` is only ever sent to Player2.
- **Bring your own key** (`provider/`, `client/`): `OpenRouterAccount` runs the OpenRouter OAuth PKCE sign-in (callback on a loopback port for the settings screen, the code-copy variant for `/vml login` on dedicated servers) and reads credit from `/key` and `/credits`. `ModelCatalog` reads `/models` of any endpoint and estimates cost per hour of play from list prices. `SettingsScreen` and `ModelPickerScreen` are vanilla-widget screens, opened through the `open_settings` payload that `/vml setup` sends to the host of a singleplayer or LAN world. The OpenRouter key (`apiKey`) is only ever sent to OpenRouter; other endpoints use `endpointKey`.

## Data the mod sends

Only to the AI service the player picks, to Mojang when a companion borrows an account's skin, to MineSkin when it wears an own PNG (the image only), to the Minecraft Wiki when a Builder looks something up, and one anonymous count a day to `api.voxel-mind.com/v1/ping` (`provider/UsageStats`: kind of AI service, mod and game version, nothing else; told to the host before the first count, off with `/vml stats off` or `"usageStats": false`; dev runs never send it). The totals are public at `api.voxel-mind.com/v1/stats`.

## Contributing

See `CONTRIBUTING.md`: the core is maintained by Nyvoro UG; changes come in as pull requests.

## Build and test

Requires JDK 21 and JDK 25 (Gradle toolchains) and the Gradle cache the main mod uses (`GRADLE_USER_HOME=V:\Android\Gradle`).

One source tree builds 16 jars (Minecraft 1.21 to 26.2, Stonecutter); the checked-in source is the 26.2 state. `bash tools/build-all.sh` builds and checks all of them into `dist/`; versions, smoke tests and how to add a version are in [docs/VERSIONS.md](docs/VERSIONS.md). For 26.2:

```sh
./gradlew :26.2:build               # versions/26.2/build/libs/voxelmind-local-<ver>+26.2.jar
./gradlew :26.2:runGameTest         # 132 headless server tests: body, tools, settings, Builder fill/undo and no-op fills, geometry rejections, God rain/sequence/rules and plain text, command boundary, Sparks config, AI label, one mind against a stub endpoint, doors, placed blocks, building among trees, surfacing, knockback, melee technique, threat events
./gradlew :26.2:runClientGameTest   # real client: summon, skin, chat, settings, model list, menu, monitor, memories, create companion/builder/god by clicks, screenshots
./gradlew :26.2:runServer           # dedicated dev server in run/ (port 25599, offline mode)
./gradlew :26.2:runServer -PvoxelmindApi=http://127.0.0.1:8797/v1     # against a local `wrangler dev` VoxelMind API (also runClient, runGameTest)
./gradlew :26.2:runGameTest -PvoxelmindApi=http://127.0.0.1:8797/v1   # with VML_E2E_VOXELMIND_TOKEN: one real call through a local VoxelMind API
```

End-to-end with a real model: start `runServer` with `VOXELMIND_LOCAL_API_KEY` set (a new config starts on VoxelMind Sparks; the key is used once the endpoint is OpenRouter: `/vml endpoint openrouter` in a fresh `run/`), then drive an owner with `node tools/e2e/owner-bot.cjs tools/e2e/scenarios/<file>.json Lysa`. For Builder and God the bot must be an operator (`run/ops.json`), as in `hosted-builder-god.json` (uses the patched mineflayer from `../voxelmind-platform`). `tools/e2e/map.cjs` prints built structures; `tools/e2e/provider_bench.py` compares OpenRouter providers.

Config: `config/voxelmind-local.json` (`apiKey`, `endpointKey`, `baseUrl` (new configs: VoxelMind Sparks), `model`, `memoryModel` (empty = same as `model`), `providerOrder`, `language`, `maxRequestsPerMinute`, `idleOwnerAwayMinutes`, `requestTimeoutSeconds`, `localRequestTimeoutSeconds`, `logPrompts`, …). Written whole or not at all; a file that cannot be read is kept as `voxelmind-local.json.broken-<time>`.
