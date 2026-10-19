# VoxelMind Local – Changelog

## 0.5.0 (2026-10-19)

**Brains: choose how a companion thinks, or build your own, with your AI agent if you like.**

- **Brains.** A brain decides what a companion's model is told (frame, conversation rules, decision rule, default character, memory guidance), which context blocks and how much of the recent timeline it sees, which body tools it gets, and the temperature. Pick one per companion in the menu (the new "Brain" row when you create or edit a companion) or with `/vml brain <companion> <brain>`. `/vml brain` lists them.
  - **VoxelMind Default** is the brain companions had so far, unchanged to the character.
  - **VoxelMind Lite** is new: shorter and plainer rules, a shorter timeline and a lower temperature, for small local models (about 8B to 20B in Ollama or LM Studio). It also tells the model when to use gather and when mine_block.
- **Build your own.** `/vml brain new <name>` creates a complete copy of the default in `.minecraft/voxelmind-local/brains/<name>/` (a `brain.json` and a few text files). Change it yourself or let an AI agent do it: `/vml brain guide` copies a ready prompt for Claude Code, Codex or Cursor. `/vml brain reload` applies changes while you play. A brain is data only; no code runs from it. The AI label and the safety rule are added by the mod to every brain and cannot be removed. The full format is in `brains/README.md` of the repository (github.com/Nyvoro/voxelmind); share a brain on Discord or as a pull request.
- **Anonymous count, two more steps** (off with `/vml stats off` as before): how long ago the installation started (first day, 1 to 7, 8 to 30 or more than 30 days; the date stays on your PC) with the day's count, and once a day for the previous day which brain companions played with (shipped brains by name, your own as "custom") and a step of minutes played (1 to 15, 15 to 60, 60+). This shows which brain keeps people playing; the best one can become the default.

## 0.4.7 (2026-10-19)

**On Minecraft 1.21 to 1.21.10 you can choose every AI again.**

- **"AI from" no longer sticks at Player2.** On Minecraft 1.21 to 1.21.10 the button in the AI settings went from VoxelMind Sparks to Player2 and then stayed there, so OpenRouter, Ollama, LM Studio and other servers could not be picked in the menu (the `/vml endpoint` command still worked). The same was true for the sort button in the model list. Both now go through all their choices. Minecraft 1.21.11 and 26.x were not affected. The client smoke test now clicks through the button on every version.

## 0.4.6 (2026-10-19)

**Works next to Carpet, and companions show the outer layer of their skin.**

- **Carpet and other mods with fake players:** opening a world with Carpet installed crashed the game ("Mixin transformation of net.minecraft.server.players.PlayerList failed"), because both mods replaced the same step of a respawn. VoxelMind now wraps that step instead of replacing it, so both work together. Tested by starting servers with Carpet on 26.2 and 1.21.1.
- **Skins with hat, jacket, sleeves and trouser legs:** a companion's body now shows every layer of its skin, like a player with all skin parts switched on. Before, only the base layer was drawn, so skins from NameMC looked bare.
- **Long-term memory with LM Studio:** LM Studio refuses the JSON mode the memory rewrite asked for ("'response_format.type' must be 'json_schema'"), so "Who I have become", "Recent developments" and what a companion knows about you never changed there. When an endpoint refuses it, the mod now asks again without it (the prompt asks for JSON anyway) and remembers that for this model.

## 0.4.5 (2026-10-16)

**Your own skins for companions, and an anonymous count of which AI service players use.**

- **Own skins.** A companion can now wear your own skin PNG, not only the skin of another Minecraft account. In the menu under Look, or with `/vml skin <name> <source>`, enter a link to the PNG (`https://…/skin.png`) or the name of a PNG you put in `.minecraft/voxelmind-local/skins/` (the folder is created when the game starts). Slim (Alex-style) arms are detected from the image; add ` slim` or ` classic` after the source if the detection is wrong. Account names work as before.
  - Minecraft only shows skins that Mojang has signed, so the image goes to MineSkin (mineskin.org), which signs it and returns the signed texture. Everyone sees the skin, also players without the mod. It is uploaded as "unlisted" (not in MineSkin's gallery); only the image is sent. A skin is a 64×64 PNG (old 64×32 skins work too). An own MineSkin API key can go into `"mineskinKey"` in the config; without one, MineSkin signs about ten skins a minute for everyone.
  - On a server, links into the server's own network only work for the host or an owner-level operator.
- **Anonymous usage count.** Once a day, after the first answer from the AI, the mod sends one count to api.voxel-mind.com: the kind of AI service (VoxelMind Sparks, Player2, OpenRouter, a local model or another endpoint), the mod version and the game version. No name, no ID, no key, nothing from chats. The host is told in the game with a **[Turn off]** link before the first count is sent; `/vml stats` shows the state, `/vml stats off` (or `"usageStats": false` in the config) turns it off. The totals are public at api.voxel-mind.com/v1/stats.

## 0.4.4 (2026-10-11)

**The first release of VoxelMind Local on CurseForge.** Same code as 0.4.3, which went up as a beta a few hours earlier; everything below under 0.4.3 is in it.

## 0.4.3 (2026-10-11)

**Huts stand on the ground in a forest, a companion no longer stays under water when a search ends early, and it fights like a player.**

- **Sign-in with VoxelMind Sparks works again.** Sign-in with VoxelMind Sparks signs a server id with the player's Mojang-certified chat key; the API checks it offline because Mojang refuses requests from Cloudflare. Your password, your Mojang token and the private key stay on your PC.
- **build_shelter in a forest.** The ground is found per column the way survey finds it, so tree trunks and branches no longer count as ground: a hut in a dark oak forest used to be planned on top of the trunks (8 blocks high, over 120 blocks, refused). Where trunks or branches stand in the hut's cells, nothing is built and the result names them with their positions. A plan over uneven ground that needs more than 128 blocks is built anyway (the limit is for place_blocks calls), and the tool says what it really takes: 55 blocks on level ground, more where the walls reach down.
- **survey** reports the ground under a trunk (marked T) and marks leaves or branches overhead (^), instead of the top of the trunk.
- **place_blocks names every block it skipped**, with the reason and the cell (for example "occupied by dark_oak_log at (22,95,233)"); before, the second pass forgot the reasons of the first.
- **Up to air before reporting.** When an action ends its own walk early while the companion's head is under water (explore spotting an animal mid-swim), the body first swims up: to the nearest ground with air above, or else to the water surface, and the result says where it came up. It used to stop under water and could drown while the next decision was still being made. explore also says how far below or above the find is, how deep it is under water and whether a walking or swimming route leads to it ("axolotl … 24 blocks below you, under water 25 blocks deep; no walking or swimming route to within reach of it was found").
- **Knockback.** A player's hit now knocks the companion back like any player (the server sends that knockback to the player's game, which the companion's body had ignored).
- **Fighting like a player.** attack closes in only while the target is out of reach and holds at reach (it used to creep up to 1.8 blocks); it hits when the attack is fully charged and sprints into that hit for the sprint knockback; while the attack recharges it steps back out of the target's own melee range where the ground behind is safe (no drop of more than two blocks, no water, lava or fire). In the game test against a zombie with an iron sword, 0.4.2 took a hit and stood 2 ticks in the zombie's reach while recharging; 0.4.3 took none.
- **Mobs coming at you are facts.** A hostile mob that starts targeting the companion or its owner is an event ("zombie [#57] is now coming at you (12m north of you)") and wakes the companion like a hit does (not while it is already fighting that mob), so it can act before the first hit. Each line under Threats says whom the mob targets.
- Numbers in facts always use a decimal point ("took 1.5 damage"), also in a German game.
- **Builder and God: a command that changes nothing is not a failure.** A `fill` or `setblock` on blocks that already are what it sets answers "No blocks were filled"; that used to count as an error, so a Builder's cottage was reported as FAILED although it stood, and the model went looking for a fault. Such steps now count as done, and the result says how many changed nothing because the blocks already were so.
- **Builder: why geometry lines were skipped.** When the build's geometry model writes lines the Builder cannot use, the result now shows up to three of them with the reason (for example "fill takes 7 values, this line has 8" for a `hollow` fill, or a block state with a space in it) instead of only their number. Each geometry call is in the decision log too, with its full reply when `logPrompts` is on.
- **The God speaks plain text, like the Builder.** Text the God writes outside its `say` tool used to stay with itself, and it kept greeting players who never saw the greeting; now it reaches the players in chat.

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
