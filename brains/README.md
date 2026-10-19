# Building a VoxelMind brain

A **brain** decides how a VoxelMind companion thinks: what its model is told, how much of the world and the recent past it
sees, which body tools it is offered and how it remembers. A brain is **data only** (one JSON file and a few text files).
No code runs from a brain, so a brain cannot break the game or harm a player's PC.

This guide is written so that an AI coding agent (Claude Code, Codex, Cursor, ...) can follow it exactly. People can too.

## What a brain can and cannot change

| A brain changes | The core keeps (not changeable by a brain) |
|---|---|
| The frame: who the companion is and how it plays (`frame.md`) | The AI label and the safety rule, appended to every frame |
| The conversation rules (`conversation.md`) | Which AI service and model the player chose |
| The decision rule (`decision.md`) | How each tool is carried out by the body |
| The character when the owner wrote none (`identity.md`) | The tools `say`, `stop`, `wait`, `keep_current_action` |
| How the long-term memory is condensed (`memory.md`) | The JSON format of the memory answer |
| Which context blocks go into each decision, and how much timeline | Permissions, the request budget, everything Builder and God do |
| Which body tools are offered, temperature, answer length | |

New tools, new perception or new AI services are changes to the core: open an issue or a pull request against the Java code
(see `CONTRIBUTING.md`).

## Where brains live

- Shipped with the mod: `default` (VoxelMind Default) and `lite` (VoxelMind Lite, for small local models). Their files are in
  `src/main/resources/assets/voxelmind_local/brains/<id>/` in this repository.
- Your own: `.minecraft/voxelmind-local/brains/<id>/` (for a dedicated server: the server's folder). The folder name is the
  brain's id: 1 to 32 characters, lowercase letters, digits, `-` and `_`. Folders starting with `_` or `.` are ignored.

## The workflow

1. In Minecraft: `/vml brain new mybrain`. This creates `.minecraft/voxelmind-local/brains/mybrain/` as a **complete copy of
   VoxelMind Default**: `brain.json` with every setting written out, and every text file holding the text the default uses.
   Change what you want; delete a text file to fall back to the default text again.
2. In Minecraft: `/vml brain reload` (reads all brains again, reports problems in the chat), then
   `/vml brain <companion> mybrain`. The companion thinks with it from its next decision on. You can also pick the brain in
   the companion menu (press V, open a companion, the "Brain" row).
3. Watch the companion play. Every decision is logged in the world folder (`voxelmind-local/logs/`), with the brain as
   `"brain": "mybrain@0.1.0"`; set `"logPrompts": true` in `config/voxelmind-local.json` to see the full prompts there.
4. Change, `/vml brain reload`, try again.

## `brain.json`

All fields are optional except that the file must be a JSON object. Unknown fields are ignored.

```json
{
  "name": "My Brain",
  "version": "0.1.0",
  "author": "your name",
  "description": "One short line (about 60 characters): what this brain does differently. Shown in the menu.",
  "models": ["qwen3:8b", "gpt-oss:20b"],
  "minMod": "0.5.0",
  "temperature": 0.6,
  "maxTokens": 2048,
  "tools": { "include": [], "exclude": ["use_block"] },
  "timeline": { "events": 30, "chats": 24, "chatMinutes": 30, "chatChars": 6000 },
  "blocks": { "sensory": true, "bodyInterface": true, "clock": true, "lastAction": true, "previously": true, "aboutOwner": true },
  "memory": true
}
```

| Field | Meaning | Default and limits |
|---|---|---|
| `name`, `version`, `author`, `description` | Shown in the menu and in `/vml brain` | id, `1.0.0`, empty, empty |
| `models` | Models this brain is made for (information for players) | empty |
| `minMod` | The oldest mod version it works with | empty |
| `temperature` | Sampling temperature of each decision | the player's setting; 0 to 2 |
| `maxTokens` | Longest answer of a decision | the player's setting; 256 to 16384 |
| `tools.include` | If not empty: only these body tools are offered | empty (all) |
| `tools.exclude` | These body tools are not offered | empty |
| `timeline.events` | How many non-chat rows of the recent past (results, events) | 30; 5 to 60 |
| `timeline.chats` | At least this many newest chat lines | 24; 4 to 60 |
| `timeline.chatMinutes` | Chat lines of this many minutes are kept as well | 30; 1 to 240 |
| `timeline.chatChars` | At most this many characters of chat | 6000; 500 to 20000 |
| `blocks.*` | Which context blocks go into each decision (see below) | all `true` |
| `memory` | Whether the long-term memory is rewritten as the companion plays | `true` |

`say`, `stop`, `wait` and `keep_current_action` are always offered. A tool that is not offered is not carried out either,
even if a model calls it. The body tools are listed with their descriptions in `src/main/java/com/voxelmind/local/tool/Tools.java`.

## The text files

Each file replaces one part of the prompt. A missing or empty file means "use the default text". Each file has at most
16,000 characters. `{name}` becomes the companion's name and `{owner}` its owner's name.

| File | Replaces | Notes |
|---|---|---|
| `frame.md` | The start of the system prompt: what the companion is, how to treat facts, how to play | The core appends the AI label and safety rule after it |
| `conversation.md` | The rules for talking (who to answer, not repeating, honesty) | Second part of the system prompt |
| `decision.md` | The decision rule at the end of each decision prompt | Put `{continuity}` where the sentence about the running action goes; without it the sentence is appended |
| `identity.md` | The character when the owner wrote none in the menu | A character the owner wrote always wins |
| `memory.md` | The guidance for condensing the long-term memory | Also `{maxPreviously}` and `{maxKnowing}`; the JSON format around it is fixed |

## What a decision prompt looks like

The **system** message is `frame` + core rules, then `conversation`, then a `[LANGUAGE]` block from the core.
The **user** message is the context, then the decision rule:

- `[IDENTITY]`: name, the owner's character text (or `identity.md`), life mode, worlds.
- `[SENSORY]` (`blocks.sensory`): position, health, hunger, inventory, what is around, threats, the running action.
- `BODY INTERFACE` (`blocks.bodyInterface`): one paragraph on how the body and tools work.
- `CLOCK` (`blocks.clock`): when someone last spoke, when the companion last spoke.
- `LAST ACTION` (`blocks.lastAction`): the last action's result if it is recent.
- `[PREVIOUSLY]` and `[ABOUT OWNER]` (`blocks.previously`, `blocks.aboutOwner`): the long-term memory.
- `[TIMELINE]`: the recent past, sized by `timeline.*`.
- The decision rule (`decision.md`).

The texts of VoxelMind Default are in `src/main/java/com/voxelmind/local/mind/PromptTexts.java`, and how the context is
assembled is in `PromptBuilder.java`, if you want to read the exact code.

## Tips that matter

- Small local models (8B to 20B) do better with fewer and shorter rules, a shorter timeline and a lower temperature: see
  VoxelMind Lite.
- Name the tools a model mixes up: for example "To collect a kind of block nearby, use gather; use mine_block only for one
  block at known coordinates."
- A companion only speaks through `say` or a tool's `say` argument. Plain text is never heard; keep that in `decision.md`.
- Keep `{continuity}` in `decision.md`: it tells the model what keeps running when it only speaks.
- Write the prompt texts in English. The core's `[LANGUAGE]` block makes the companion answer in the player's language.

## Sharing a brain

- **Quick:** zip the folder and post it in the VoxelMind Discord. Others unzip it into their brains folder and run
  `/vml brain reload`.
- **Official:** open a pull request to [github.com/Nyvoro/voxelmind](https://github.com/Nyvoro/voxelmind) that adds
  `brains/<id>/` with your `brain.json` and text files, and in the description what it does better and with which models you
  tested it. We review it (it must keep companions safe for a young audience and actually play well) and ship accepted brains
  with the next mod version.
- **Becoming the default:** the anonymous usage count shows how long companions play with each shipped brain. A brain that
  keeps players playing longer than VoxelMind Default can become the new default, with credit to its author.

## For AI agents: checklist

1. Work only inside the brain's folder. Do not edit Java code for a brain.
2. Keep `brain.json` valid JSON. Keep each text file under 16,000 characters.
3. Keep `{continuity}` in `decision.md` and the `{maxPreviously}` and `{maxKnowing}` limits in `memory.md`.
4. Do not try to remove the AI label or the safety rule; the core appends them anyway.
5. Raise `version` in `brain.json` with every change you hand over.
6. When done, tell the player to run `/vml brain reload` and `/vml brain <companion> <id>` in Minecraft.
