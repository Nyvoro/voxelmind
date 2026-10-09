# VoxelMind Local – Changelog

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
