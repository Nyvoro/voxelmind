# VoxelMind Local – Changelog

## 0.4.2 (2026-10-10)

**Companions speak and act in one decision, use doors, and know what you built.**

- **One mind instead of two.** A companion used to answer you with one model call and choose its action with a second one, and neither knew what the other did. So it could say "I'll patch the hole" or "I'm eating" while its body did something else or nothing. Now one decision holds both what it says and what it does. Every body tool takes an optional `say` line (and `say_to`: a player or `owner` whispers, `everyone` yells), so models that answer with a single tool call (Player2's free model) also speak and act at once. One call instead of two per chat line also makes conversation cheaper.
- **Talking to a thinking companion.** A chat that arrives while it is still deciding no longer throws that decision away: it is carried out, and your new line gets the next decision right after. Text a model writes outside `say` reaches no one; when a decision said nothing, the companion's timeline notes that its text was not heard.
- **Doors and gates.** Walking opens wooden and copper doors and fence gates on the way and closes the ones it opened behind it; iron doors with a button or lever beside them are opened that way. A house with a door is no longer a box the companion can only leave by breaking a wall, and `go_to` a door or gate with `through: true` walks through it to its far side, closes it behind and says whether it is open or closed now. Results name doors a walk had to leave open, and why. Placing a block against a door, trapdoor, gate, chest or another block that reacts to a click sneaks like a player, so the block lands and the door stays shut (it used to open the door and place nothing). New body tool `use_block`: right-click a door, trapdoor, gate, lever, button or similar, with the block state before and after (and a moved respawn point) in the result.
- **Builds are facts.** Every block a player places (you or a companion) is remembered per world (`voxelmind-local/placed.dat`, saved with the world), including the Builder's fills and builds, blocks that change form in place (stripped logs, weathered copper, hardened concrete), and blocks moved by pistons or falling. The companion sees what is above its head (open sky, or which block and who placed it) instead of "below the surface" under every roof, what it stands on, the placed blocks around it and whose, nearby doors, gates and trapdoors with their open state and what is above each side of the closest door or gate (a doorway result names them too), and placed resources counted apart from natural ones. Walking never digs through a placed block (dirt and stone huts included); it only digs back down its own pillar steps. Mining never breaks a placed block, a door, gate or trapdoor, or a block that water or lava touches, because it is in the way (the result says which and why). gather and mine_block by block type take placed or planted blocks only with `include_placed` (your farm), and no block that water or lava touches (mine_block takes one by its coordinates); dig and mine_block say whose blocks they broke.
- **Honest walking results.** go_to says "arrived at" only in the target cell, otherwise how far from it the walk stopped; a walk to a doorway ends in the doorway. A step straight up out of water is swum instead of built up, so a walk to the air above water ends instead of running on without a result, and no route dives deeper than half a breath (at most 6 steps in a row with the head under water), so the way back still fits.
- **More facts for hard moments.** CLOCK says when the companion itself last spoke; with its head under water, [SENSORY] says where the water above it ends and, when stone or another block closes the water above, where the nearest air is through the water; a `stop` that ended a running action leads to the next decision like every other action end; and a companion whose body has nothing to do gets its next quiet moment within a minute instead of up to four (it used to stand still after a failure until you spoke).
- **Longer conversation memory.** The prompt keeps the chat of the last 30 minutes (at least 24 lines, up to about 6000 characters) instead of the last 24 lines, and the same result repeated many times in a row shows up once with a count.
- **Measured.** `tools/e2e` gained scenarios built from a customer's session (patch a hole, come out of and go back into a house with a door, go mining from inside the house, a plan buried under chatter) and `say-do.cjs`, which lets a judge model label every line the companion said against what its body did and what it had seen. On 0.4.1 the companion broke two wall blocks to leave the house and dug through its floor to go mining. On 0.4.2 it went out and back in through the door in every run and walking never broke the house; what the runs still turned up (a block placed against a door opened it, swimming up could hang, mining could let lava in, routes dived too deep, the nearest air under water was not named) is fixed above. The model still makes its own choices: once it dug straight down through the floor on purpose, and the result named whose plank it broke.

## 0.4.1 (2026-10-10)

**Fixes for launch weekend: companions keep their memory, come to you on every version, survive portals, and the AI settings start where most players want to begin.**

- **Long-term memory no longer freezes.** Once a companion's memory reached its size limit, new experiences were cut off and never stored. Now the memory is kept as the AI writes it; if it grows far too long, the AI is asked once more to condense it itself.
- **Summoning on Minecraft 1.21 to 1.21.8:** a companion called after you travelled far now appears next to you (it used to appear where it was last, or at the world spawn), also across dimensions.
- **Portals:** after a trip through a nether or end portal, a companion can be hurt again and can use portals again.
- **AI settings start with VoxelMind Sparks** in a new installation: sign in with your Minecraft account, welcome Sparks included. Player2, OpenRouter and local models are one click further, as before.
- **Chat in your language:** the hint when you join, the "no AI connected yet" notice, the AI label and the Sparks lines are now German in a German game (English for players without the mod).
- **Sparks on servers and in LAN worlds:** links and notices name the account the world actually spends; redeeming a code says which account got the Sparks; `/vml key <token>` on a server says whose Sparks it uses.
- **Safety:** keys and sign-ins only go to the exact service they belong to (a look-alike address never gets them); AI settings commands can no longer be run by command blocks or datapack functions; God and Builder cannot place command blocks, lift the block-change limit, or set extreme tick speeds; a God's rules can no longer crash the server by triggering each other.
- **Performance and cost:** companions no longer search for a path every tick when a target is unreachable (lag on servers); the perception is built once per decision for both thinking lanes; quiet-time thinking pauses while the owner is away from the keyboard for 5 minutes (`idleOwnerAwayMinutes` in the config); a little less repetition in each prompt.
- **With your own OpenRouter key**, the default model now goes to the same US providers with zero data retention that VoxelMind Sparks uses (Relace, Sail Research, Together).
- **Files:** companion files and the config are written whole or not at all; a config the game cannot read is kept aside instead of being overwritten; world backups before a God or Builder session keep the newest three per world.
- **Servers:** each player who is not the host or an operator can have 3 companions in the world at once (`maxCompanionsPerPlayer`); memory rewrites count against the shared request budget; a God's rule that fires every tick shows up as one counted line every 5 seconds.
- **AI settings:** when VoxelMind, Player2 or OpenRouter does not answer, the settings say "could not check right now" instead of asking you to sign in again.
- **Feature requests and support:** the menu, `/vml help` and Mod Menu link to the feature-request channel on Discord and to VoxelMind on Ko-fi.
- The mod shows as **VoxelMind** with its icon in Mod Menu and launchers. If the old hosted VoxelMind mod is installed too, the pause-menu button of this one reads "♦ VoxelMind (new)".

## 0.4.0 (2026-10-09)

**Builder and God are back, VoxelMind has its own AI service, and it runs on every Minecraft version from 1.21 to 26.2.**

- **16 Minecraft versions:** 1.21, 1.21.1 to 1.21.11, 26.1, 26.1.1, 26.1.2 and 26.2 (Fabric). Pick the file whose name ends with your exact game version. 1.21.x runs on Java 21, 26.x on Java 25; Fabric API is needed on clients and servers.
- **Builder:** a fairy (allay, bee, vex, parrot or bat) floating at your shoulder, with console power over your world.
  - It fills areas in front of you, at your feet, where you look, or at coordinates.
  - It designs and builds whole structures from a description ("a small stone watchtower with a lookout platform"), growing them in front of you layer by layer.
  - It undoes any of its last edits, finds blocks around you, and looks things up on the Minecraft Wiki.
  - Bring one with `/vml builder <name> [character]`, or choose **Builder** when you create a new companion.
- **God:** a bodiless game master for your world. It improvises adventures with commands, timed sequences, standing rules ("when a player breaks a block…"), creatures falling from the sky and moving its own creatures.
  - Choose a campaign: Sandbox, Trials of the gods, Living world or Chaos arena.
  - Choose a chaos level from 1 (only blessings) to 5 (nothing is sacred), and whether it sees the other players of the world.
  - When you send it away, its rules, effects and creatures are cleared and changed gamerules are restored.
  - Bring one with `/vml god <name> [character]`, or choose **God** in the menu.
- Builder and God get console power, so only the host of a singleplayer world or an operator can bring them. Once a day, before one arrives, a backup of the world is written to `backups/`.
- **VoxelMind Sparks**, our own prepaid AI service (`api.voxel-mind.com`). One Spark is about one minute of play with the standard model.
  - Sign in with your Minecraft account in the AI settings, the same way you join a server: no password, no email.
  - New accounts get welcome Sparks, and a few free Sparks come every day.
  - Packs and prices are at voxel-mind.com/sparks, for any Minecraft name.
  - `/vml sparks` shows your balance and a link to packs and prices; in singleplayer also **[Account]**, your private page with balance, spending per day and credits (the **Your Sparks** button in the settings opens it too).
  - `/vml sparks redeem <code>` redeems a Sparks code, for example the one from our email to former subscribers, for the account you are signed in with. Without the mod: voxel-mind.com/sparks/redeem. On a dedicated server, use `/vml endpoint voxelmind` and `/vml key <token>`; the token is under **Copy token** in the settings.
  - When the Sparks run out, the chat says so and links to packs and prices and to the other AI choices.
- **AI label for everyone:** companions carry **[AI]** before their name in the name tag, the tab list and the chat; Builder and God lines start with **[AI]**. Players who join a world with AI characters are told once who they are and whose they are.
- Companion menu: tabs for **Companion / Builder / God** when creating; settings per kind (body, campaign, chaos, guests); cards and monitor show the kind.
- New commands: `/vml campaign|chaos|guests <god> <value>` and `/vml body <builder> allay|bee|vex|parrot|bat`.

## 0.3.3 (2026-10-08)

**VoxelMind now runs entirely in your game.** The companion no longer needs the VoxelMind servers or a VoxelMind account. You connect your own AI, and nothing about your world or your chats reaches us.

- **Companion menu** in the familiar VoxelMind look. Open it with **V**, the **♦ VoxelMind** button in the pause menu, or `/vml`.
  - Cards with face, status (in the world, busy, away, fallen) and current activity.
  - Summon, dismiss, edit, monitor and delete (with confirmation).
  - **New companion:** name, the skin of any Minecraft account, and a character from six starting points (Loyal Buddy, Jester, Explorer, Builder, Guardian, Quiet Soul) or your own words.
  - **Normal or Hardcore** per companion. Normal: it respawns after a death. Hardcore: one life; if it dies, it leaves your world for good, and its memories stay readable in the menu. The companion knows its mode; how careful it plays is its own choice. New companions in a hardcore world start in hardcore.
  - **This world or all worlds** per companion. A companion for all worlds goes with you into every world of this game: memories, character, look and mode travel along; each world keeps its own inventory and position for it (marked ∞ in the menu).
  - **Monitor:** health, hunger, position, what it is doing right now and the last result, updated every 2 seconds.
  - **Memories:** what it wrote about itself and about you, plus its newest events.
- **AI settings** (from the menu, `/vml setup`, the **[Settings]** link in chat, or a key you can bind):
  - **Player2 (free):** connect a Player2 account (player2.game) with one click when the Player2 app runs, otherwise with a code in your browser. Player2 picks the model; free to play with daily AI power. `/vml player2` does the same on servers.
  - **Sign in with OpenRouter:** opens your browser, OpenRouter creates a key for VoxelMind in your account, and you pay OpenRouter directly. You can also paste an existing key.
  - **Local AI:** Ollama, LM Studio or any OpenAI-compatible server.
  - **Model list:** search, only models that can use tools, sorted by estimated cost per hour of play. Five models are marked as tested, with measured cost: DeepSeek V4 Flash (default, about $0.20/h), GLM 5.3 Flash, Claude Haiku 5.5, GPT-5.6 Luna, Gemini 3.5 Flash Lite.
  - **Test connection:** checks key, model and tool calls with one tiny request, and shows your remaining OpenRouter credit.
- `/vml mode <name> normal|hardcore` (while it lives; a hardcore death is final), `/vml worlds <name> this|all`.
- **Commands for servers:** `/vml login` (sign in with a code), `/vml key`, `/vml models [search]`, `/vml model <id>`, `/vml endpoint openrouter|ollama|lmstudio|<url>`, `/vml test`, `/vml status` with credit.
- Clear messages when a key is rejected, credit runs out, a model is unknown or a local server isn't running, each with a link to the settings.
- Minecraft 26.2, Fabric. Works in singleplayer, LAN and on servers that run the mod; players joining a server don't need it (with it they get the menu).

Older VoxelMind files (0.4–0.8) needed the hosted service, which ends on November 8, 2026.
