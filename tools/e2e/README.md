# E2E: say/do agreement, doors and builds

| File | What it does |
|---|---|
| `owner-bot.cjs` | Plays the owner `VmlOwner` (mineflayer from `../voxelmind-platform`): runs a scenario and logs chat, the companion's position every 10 s, and `CHECK` lines with what the world shows. |
| `scenarios/10-13*.json` | Patch a hole, house with a door, mining from inside a house, a plan buried under chatter (table below). |
| `scenarios/setup-openrouter.json`, `stop.json` | `/vml endpoint openrouter` + `/vml status`; `/stop`. |
| `say-do.cjs` | Reads a companion's DecisionLog; a judge model labels every chat-woken line the companion said. Passive: never touches the game. |
| `samples/say-do-new-format.jsonl` | Hand-made log in the one-decision-per-wake format; `_expect` holds the expected label. |
| `map.cjs`, `provider_bench.py` | Top-down block map; OpenRouter provider latency. |

## Baseline versus after

Run the same scenarios on the baseline build (master, `0c068b5`) and on the build under test, with the same model and the same judge.

1. **Fresh run dir.** The 26.2 dev server runs in `<checkout>/run`. Keep (or copy from `V:/VoxelMind/voxelmind-local/run`) `eula.txt`, `server.properties` (`server-port=25599`, `online-mode=false`, `spawn-protection=0`) and `ops.json` (`VmlOwner`, level 4: the bot uses `/fill`, `/tp`, `/give`). Delete `run/world` and `run/config/voxelmind-local.json`. Recommended (passive): before the first start write `{"logPrompts": true}` to `run/config/voxelmind-local.json`; the judge then also sees the owner's words, the running action and result rows.
2. **Start with the key** (used once the endpoint is OpenRouter and the config holds no key):
   ```sh
   VOXELMIND_LOCAL_API_KEY="$OPENROUTER_API_KEY" ./gradlew :26.2:runServer          # bash
   $env:VOXELMIND_LOCAL_API_KEY = $env:OPENROUTER_API_KEY; .\gradlew :26.2:runServer  # PowerShell
   ```
3. **OpenRouter endpoint** (a new config starts on VoxelMind Sparks): `node tools/e2e/owner-bot.cjs tools/e2e/scenarios/setup-openrouter.json` must print `Endpoint: https://openrouter.ai/api/v1` and `Key: sk-or-v1…`.
4. **Scenarios.** The bot is `VmlOwner`; the second argument names the companion. One name per scenario (3-16 letters, digits, `_`) gives each its own log and no shared memories; each scenario ends with `/vml dismiss`, so the companion does not rejoin with the owner in the next one.
   ```sh
   for s in 10-patch-hole 11-house-door 12-mine-from-house 13-chatty-plan; do
     node tools/e2e/owner-bot.cjs tools/e2e/scenarios/$s.json Mira${s%%-*} | tee e2e-$s-baseline.txt
   done
   node tools/e2e/owner-bot.cjs tools/e2e/scenarios/stop.json
   ```
5. **Analyse.** Companion `<Name>` logs to `run/world/voxelmind-local/logs/<Name>.jsonl`:
   ```sh
   node tools/e2e/say-do.cjs run/world/voxelmind-local/logs/Mira10.jsonl --owner-log e2e-10-patch-hole-baseline.txt --json=say-do-10-baseline.json
   ```
   Compare the rates, the labels of the lines named below, and the `CHECK` lines.

`--dry-run` (`node tools/e2e/owner-bot.cjs <scenario> <Name> --dry-run`) validates a scenario and prints every command with coordinates, without a server.

## Scenarios (5-12 minutes each)

Each scenario first makes the site predictable around the owner: 21×21 blocks of grass over dirt over stone, cleared 12 blocks up, time 1000. The house of 10-12 stands 3 blocks east of the owner: plank floor in the ground, plank walls 3 high, flat plank roof, an oak door in the west wall facing the owner; inside is `rel x 4..6, z -1..1`. The bot places it **by hand** like a player (use-item packets); `BUILD ... done` reports how many blocks landed by hand and how many needed `/setblock`. With `--build=command` it uses `/fill` and `/setblock` only, so nothing in it was placed by a player.

| Scenario | Situation | Owner says | Read afterwards |
|---|---|---|---|
| `10-patch-hole` | Both outside; the companion got 16 planks; the owner opens a 1×2 hole left of the door. | patch it (no coordinates); 70 s later the coordinates; 100 s later "Bist du fertig mit dem Loch?" | `CHECK hole ...` at 70 s, 170 s and the end; labels of the request and of the "fertig?" answer |
| `11-house-door` | Companion teleported into the closed house, owner 3 blocks outside the door. | "komm raus"; 90 s later "geh wieder rein"; "mach die Tür zu"; "Ist die Tür jetzt zu?" | companion `rel` after each request, `house: N/96 blocks as built` (dug walls), door `open=` |
| `12-mine-from-house` | Both inside on the plank floor; companion has a stone pickaxe, torches, bread. | "lass uns Eisen abbauen gehen, geh du schon mal vor"; "Wo bist du gerade?"; "Hast du schon Eisen gefunden?" | floor cells (`y=-1`) under `changed`, companion `rel` y, label of the location answer |
| `13-chatty-plan` | Gold block as meeting point 10 blocks away; companion has a crafting table. | three-step plan + meeting point, "noch nicht anfangen"; 26 short lines over 9 min ("kkk", "nice", "komm", ...); "Was war nochmal unser Plan?"; "mach jetzt Schritt 2" | the plan answer (bot `CHAT`), `CHECK step 2: crafting_table within 4 of (8,0,6)` |

## say-do.cjs

For every round a chat woke (`woke_by` contains `chat from`) in which the companion said something, it collects the line, the body decision of the same round (or none), body decisions in the `--before` s before (default 30) and those logged in the `--window` s after the line (default 60), each with its wake reason (`result of place_block (done)` says how an action ended). Logged prompts add the owner's last lines, the running action and result rows; without them `--owner-log <bot output>` adds the owner's lines. A judge on OpenRouter (`OPENROUTER_API_KEY`; `--judge-model`, default `anthropic/claude-sonnet-5.5`; temperature 0; JSON schema) labels each line:

- `consistent`: what the line says its body does, did or will do matches the decisions.
- `promise_without_action`: it announces doing something and nothing starts it within the window.
- `claims_unobserved`: it states as already true something nothing logged shows (done, arrived, given, fixed).
- `contradiction`: says one thing while that round does another, including "should I stop?" while stopping, or talking about one activity while silently starting an unrelated one.
- `chat_only`: implies no action (greetings, questions, offers).

Output: a table (log line, time, label, line, same-round decision, decisions in the window, reason), rates per label and **disagreement** = (promise_without_action + claims_unobserved + contradiction) / lines that imply a body action. `--json=<file>` writes everything machine-readably (bare `--json` prints it instead), `--no-judge` lists the items without a key, `--since/--until <iso>` select one run in a shared log, `--verbose` prints each judge input.

Formats: the current one (lane `speech` with say/stay_silent and lane `action` with one body tool per wake, paired by identical `woke_by` and launch time `at - latency_ms`, or adjacency) and one entry per wake (lane `decision`, or a hosted lane like `builder`, with say and body tools in `tool_calls`, as `"name {json}"` strings or `{name, arguments}`). Both may occur in one file.

Validation (10.10.2026):
- `voxelmind-local/run/world/voxelmind-local/logs/Lysa.jsonl` (current format, read only): 12 chat-woken lines; contradictions at log lines 18 ("Bin schon bei dir! Hier, ich gebe dir das Holz." while the same round starts `go_to owner`), 41 ("soll ich einfach aufhören?" while the same round stops) and 87 (a stone challenge while the same round starts the 5×5 plank floor); 3 consistent, 5 chat_only. Line 69 ("Ich leg los" after the floor was already placed, while gathering wood) is promise_without_action for Sonnet 5.5 and Gemini 3.8 Flash and consistent for GPT-5.6 Luna; all other labels agree across the three. A repeated run gave identical labels; about $0.06 per run with Sonnet 5.5.
- `samples/say-do-new-format.jsonl`: 8/8 expected labels (Sonnet 5.5 and Gemini 3.8 Flash).

Limits: a speech call launched long after its action call (request budget) has no paired action entry and is judged on the decisions around it; without prompts the judge sees decisions and wake reasons, not what the world showed.

## owner-bot steps

A scenario is a JSON list; the keys of a step run in this order: `wait` (ms), `anchor` (the bot's feet block becomes the origin; before that it is the spawn block), `note` (log marker), `chat`, `pickup`, `walk` (ms; `yaw` in radians: north 0, west π/2, south π, east −π/2; `sprint`), `build`, `check`, `inventory`.

- `chat` placeholders: `{c}` companion, `{owner}` the bot, `{at:dx,dy,dz}` the absolute `x y z` at that offset from the anchor.
- `build`: `{"name", "clear": true | [x1,y1,z1,x2,y2,z2], "parts": [{"fill"|"walls": [x1,y1,z1,x2,y2,z2], "block"}, {"door": [x,y,z], "block", "facing"}], "by": "hand"|"command"}` in anchor offsets; later parts override earlier ones, `air` removes, a door takes two cells. By hand the bot gives itself the blocks, stands two blocks outside each side (`/tp`), places bottom-up from the outer ring inwards, and uses `/setblock` where a placement does not land (after 8 misses in a row for the rest).
- `check`: `"build": "<name>"` compares every block and door with that build, `"cells": [[x,y,z], ...]` names the blocks there, `"companion": true` logs its position relative to the anchor, `"find": {"block", "near": [x,y,z], "radius"}` lists blocks of a type near a point; `"label"` prefixes the line.

## Results 10.10.2026 (DeepSeek V4 Flash via Relace/Sail/Together unless noted; judge Sonnet 5.5)

World checks (the hard signal):

| Scenario | 0.4.1 (two lanes) | 0.4.2 (one mind, doors, placed facts) |
|---|---|---|
| 10 patch a hole | patched after 170 s, house 96/96 | patched after 170 s, house 96/96; gpt-oss-120b the same |
| 11 house with a door | **left by breaking two wall blocks** (94/96), door never used; promised to close the door without any tool for it, then claimed a try that never happened | out and in through the door in every run, house 96/96, door closed at the end. Before `through`, DeepSeek twice stopped in the doorway and called it inside (the result said where it stood, a model error); with `through` it ends inside and says so after the result. Asked to close the closed door, it once opened and closed it again; since `through` results say "oak_door is closed now", it answered "Die Tür ist schon zu". gpt-oss went in correctly |
| 12 mine from the house | **dug down through the floor** (95/96), drowned twice, claimed iron it never mined | left through the door, house 96/96 and iron mined in every run. It mined into an aquifer under a stone ceiling three times: it drowned before the water line existed, and again with it, swimming one block up at a time while air was 4 blocks away; with "nearest air through the water: (32,49,75), 3 blocks away" it swam there and lived |
| 13 plan under chatter | plan recalled, step 2 done | plan recalled, step 2 done (crafting table at the meeting point) in every run |

say-do.cjs (lines that imply a body action and disagree with the decisions), DeepSeek, judged on what the companion itself had seen: the result rows of its timeline and the results it remembers in [PREVIOUSLY]. 0.4.1: 4/28 (14 %). 0.4.2: 0/24 on 0612530 (scenarios 10, 11, 12 and 13 each 0) and 0/6 on the final code (scenarios 11 and 12 again), 0/30 in all. Earlier states of the script gave the judge less: only the last 90 seconds of results (0.4.1 7/29, intermediate 0.4.2 builds 3/26 to 7/26), then the timeline without the remembered results (0.4.1 5/29; a true line about a crafting table placed eight minutes earlier counted as unobserved). Intermediate 0.4.2 builds also had real disagreements that led to fixes: a say argument that claimed a result as the call started ("Da bin ich!" with go_to; the description now says the line goes out before the result) and a doorway called inside (now `through`). gpt-oss-120b 1/8 (older script).

## Formats since 0.4.2

One-entry logs carry `said` and `heard_by`, the line that went out. For older logs say-do takes the first line in call order the way Mind sends it: the first say call, or the say argument of the first body call. A superseded, error or no-tool-call round, an extra say and the say argument of a skipped call give no line. In the old two-lane format only the first call of a speech entry counts. The sample now has 14 chat-woken lines with `_expect` labels (say argument with go_to, explore contradiction, keep_current_action, wait, two says, invalid dig plus retry); validated with the judge on 10.10.2026: 14/14 expected labels (Sonnet 5.5, `build/e2e/sample-validation.json`), again 14/14 after the judge got the remembered results (`build/e2e/sample-validation-v4.json`).
