package com.voxelmind.local.hosted;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tool schemas of the hosted God and Builder minds, from the hosted brain (origin/master 9ddaddc5).
 * Changes for the local mod: registry reads the running game's registries instead of reference data,
 * rules have no advancement event, and version notes about older mods are gone.
 */
public final class HostedTools {
    private HostedTools() {}

    private static JsonObject tool(String name, String description, String parameters) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", JsonParser.parseString(parameters));
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    private static final String NAME_SCHEMA = "{\"type\":\"string\",\"description\":\"The actual visible name as text, including any emoji. The tool encodes the text for this Minecraft version.\",\"maxLength\":128}";
    private static final String COLOR_SCHEMA = "{\"type\":\"string\",\"description\":\"Optional Minecraft color name such as aqua, or #RRGGBB.\"}";

    private static JsonObject registry() {
        return tool("registry", "Read the IDs this running game knows for blocks, items, entities or enchantments. Search is a literal substring of the identifier. Includes block state properties and enchantment maximum levels. Does not change the world.",
                "{\"type\":\"object\",\"properties\":{\"kind\":{\"type\":\"string\",\"enum\":[\"blocks\",\"items\",\"entities\",\"enchantments\"]},\"query\":{\"type\":\"string\",\"maxLength\":100},\"offset\":{\"type\":\"integer\",\"minimum\":0}},\"required\":[\"kind\",\"query\"]}");
    }

    private static JsonObject summonEntity() {
        return tool("summon_entity", "Spawn one entity at the chosen position, with an optional visible name and color. Name encoding follows the observed Minecraft version. The entity receives tag voxelmind_god so cleanup can remove it. Other NBT attributes remain available through run.",
                "{\"type\":\"object\",\"properties\":{\"entity\":{\"type\":\"string\",\"description\":\"Minecraft entity identifier, e.g. horse or minecraft:wolf.\"},\"position\":{\"type\":\"string\",\"description\":\"Three Minecraft coordinates, e.g. \\\"~ ~ ~2\\\" relative to the live host when executed, or \\\"100 64 -20\\\" for a fixed location.\"},\"name\":" + NAME_SCHEMA + ",\"color\":" + COLOR_SCHEMA + "},\"required\":[\"entity\",\"position\"]}");
    }

    private static JsonObject nameEntity() {
        return tool("name_entity", "Set the visible name and optional color of one existing entity identified by its observed UUID. The tool encodes the name for this Minecraft version; other entity data remains unchanged.",
                "{\"type\":\"object\",\"properties\":{\"entity_uuid\":{\"type\":\"string\",\"description\":\"The Minecraft UUID from entity observations.\"},\"name\":" + NAME_SCHEMA + ",\"color\":" + COLOR_SCHEMA + "},\"required\":[\"entity_uuid\",\"name\"]}");
    }

    private static JsonObject say(boolean god) {
        String description = "Speak to the players. channel \"chat\" (default) prints a chat line from you, \"title\" shows big text on screen (optional subtitle), \"actionbar\" a short line above the hotbar."
                + (god ? " Humans only; companions do not read it. The delivery receipt is recorded in the timeline without waking you; a failed delivery wakes you." : "");
        return tool("say", description,
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"description\":\"What you say.\",\"maxLength\":400},\"channel\":{\"type\":\"string\",\"enum\":[\"chat\",\"title\",\"actionbar\"]},\"subtitle\":{\"type\":\"string\",\"description\":\"Only with channel title.\",\"maxLength\":120}},\"required\":[\"text\"]}");
    }

    private static JsonObject run(boolean god) {
        String description = god
                ? "Execute one Minecraft command with console authority on the players' world (no leading slash needed). Admin verbs (op, kick, stop, reload, whitelist, ban…) are refused; harmful verbs may not target @a. The server feedback returns as a fact. Read queries (data get, locate, scoreboard players get) use this same tool. ~ and @p resolve at the host player at execution time; explicit coordinates stay at that location. ~ offsets use world axes, not the player's facing; ^ coordinates use the host's rotation. A summon receipt confirms creation at that instant; subsequent existence and properties appear in [YOUR ENTITIES]. CustomName and Glowing change presentation, not a mob's AI or taming. NBT can be inspected with data get entity <UUID> <path>. An execute selector matching zero entities executes no inner command; a current attack target of none is only a snapshot."
                : "Execute one Minecraft command with console authority on the owner's world (no leading slash needed). Admin verbs (op, kick, stop, reload, whitelist, ban…) are refused; harmful verbs may not target @a. The server feedback returns as a fact. ~ and @p resolve at the owner. Available command verbs: summon, fill, setblock, clone, place, fillbiome, give, clear, item, effect, attribute, tp, teleport, spreadplayers, time, weather, tick, gamerule, difficulty, title, tellraw, bossbar, scoreboard, team, playsound, stopsound, particle, ride, damage, experience, xp, gamemode, spectate, locate, kill, schedule, function, data, enchant, spawnpoint, tag, trigger, advancement, recipe, loot, worldborder, random, execute.";
        return tool("run", description,
                "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"The command, e.g. summon lightning_bolt ~ ~ ~3\",\"maxLength\":1000}},\"required\":[\"command\"]}");
    }

    private static JsonObject observe(boolean god) {
        String description = god
                ? "Do nothing now and look again after the given seconds (60-600). Player events and command results can wake you earlier. Successful say delivery receipts and periodic world snapshots do not."
                : "Do nothing now and look again after the given seconds (60-600). Chat and the results of your own commands still wake you earlier.";
        return tool("observe", description,
                "{\"type\":\"object\",\"properties\":{\"seconds\":{\"type\":\"number\",\"minimum\":60,\"maximum\":600},\"note\":{\"type\":\"string\",\"description\":\"A private note to yourself, shown in the next [TURN].\",\"maxLength\":200}},\"required\":[\"seconds\"]}");
    }

    private static JsonObject note(boolean god) {
        String description = god
                ? "Write a private entry in your timeline and the source for compressed memory: a name, location, observation or idea to revisit. Exact retention across sessions is not guaranteed."
                : "Write a private memory line that survives sessions (grudges, gifts, promises, running gags).";
        return tool("note", description, "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"maxLength\":300}},\"required\":[\"text\"]}");
    }

    public static JsonArray god() {
        JsonArray tools = new JsonArray();
        tools.add(registry());
        tools.add(summonEntity());
        tools.add(nameEntity());
        tools.add(say(true));
        tools.add(run(true));
        tools.add(tool("move_entity", "Move one loaded creature to coordinates in its current dimension using Minecraft navigation. Use its UUID from [YOUR ENTITIES]; names and tags may match several creatures. The creature physically follows a path, with normal collisions; this does not teleport it. Maximum target distance 64 blocks, 8 simultaneous moves. Speed is the native movement multiplier (default 1). timeout_seconds defaults to 20, at most 30. The receipt reports its actual end position and distance; within 1.5 blocks counts as reached. Missing routes, NoAI, riding, unloads and timeouts return a failure fact. A new move for the same creature replaces the old movement command. Its ordinary AI resumes afterward.",
                "{\"type\":\"object\",\"properties\":{\"entity_uuid\":{\"type\":\"string\",\"description\":\"The exact observed Minecraft entity UUID.\"},\"target\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}},\"required\":[\"x\",\"y\",\"z\"]},\"speed\":{\"type\":\"number\",\"minimum\":0.1,\"maximum\":2},\"timeout_seconds\":{\"type\":\"number\",\"minimum\":1,\"maximum\":30}},\"required\":[\"entity_uuid\",\"target\"]}"));
        tools.add(tool("sequence", "A timed chain of commands (max 40 steps, 2400 ticks total; 20 ticks = 1 s). delay is counted from the previous step. Runs on the server clock without you. The result returns each step's command and actual server result after execution, including failures. Later steps still execute if an earlier one fails.",
                "{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\",\"maxLength\":40},\"steps\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":40,\"items\":{\"type\":\"object\",\"properties\":{\"delay\":{\"type\":\"number\",\"description\":\"Ticks after the previous step.\",\"minimum\":0,\"maximum\":2400},\"command\":{\"type\":\"string\",\"maxLength\":1000}},\"required\":[\"delay\",\"command\"]}}},\"required\":[\"steps\"]}"));
        tools.add(tool("rule", "A standing law: when an event happens, the mod runs your commands at once, every time, with no decision from you. Placeholders in commands: {player} {x} {y} {z} {block} {entity} {text}. Events: block_break, entity_death, player_death, damage, chat, sleep, dimension_change. filter narrows by block/entity id or chat word. Max 6 commands, ttl up to 1800 s, 8 rules at once. Fire counts return as facts.",
                "{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\",\"description\":\"Short unique name, used to clear it later.\",\"maxLength\":40},\"when\":{\"type\":\"string\",\"enum\":[\"block_break\",\"entity_death\",\"player_death\",\"damage\",\"chat\",\"sleep\",\"dimension_change\"]},\"filter\":{\"type\":\"string\",\"description\":\"Optional: block id, entity id or chat word that must match.\",\"maxLength\":64},\"then\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":6,\"items\":{\"type\":\"string\",\"maxLength\":1000}},\"ttl_seconds\":{\"type\":\"number\",\"minimum\":5,\"maximum\":1800}},\"required\":[\"label\",\"when\",\"then\"]}"));
        tools.add(tool("clear_rule", "Remove one rule by label, or all of them with \"all\".",
                "{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\",\"maxLength\":40}},\"required\":[\"label\"]}"));
        tools.add(tool("rain", "Let creatures fall from the sky around the host (max 64, radius up to 24 blocks). They spawn 18–26 blocks above the host. gentle=true (default) applies slow_falling for 600 ticks; the result reports summons, not landings. They carry the tag voxelmind_god so cleanup can remove them. Bosses do not rain — summon them with a command.",
                "{\"type\":\"object\",\"properties\":{\"entity\":{\"type\":\"string\",\"description\":\"Vanilla entity id, e.g. sheep, cow, chicken, zombie, allay.\",\"maxLength\":40},\"count\":{\"type\":\"number\",\"minimum\":1,\"maximum\":64},\"radius\":{\"type\":\"number\",\"minimum\":2,\"maximum\":24},\"gentle\":{\"type\":\"boolean\"},\"name\":{\"type\":\"string\",\"description\":\"Optional custom name shown above every creature.\",\"maxLength\":32}},\"required\":[\"entity\"]}"));
        tools.add(tool("cleanup", "Remove every creature tagged voxelmind_god, cancel running sequences and clear all rules. Gamerules and effects stay.",
                "{\"type\":\"object\",\"properties\":{}}"));
        tools.add(observe(true));
        tools.add(note(true));
        return tools;
    }

    public static JsonArray builder() {
        JsonArray tools = new JsonArray();
        tools.add(registry());
        tools.add(summonEntity());
        tools.add(nameEntity());
        tools.add(say(false));
        tools.add(run(false));
        tools.add(tool("fill", "Fill a box with one block, or air to clear it. The anchor and size determine the world coordinates. Anchors: owner_front (the area ahead of the owner along their facing direction, centred sideways on them), owner (centred on the owner's own position, i.e. around/under them), owner_look (centred on the exact block the owner is currently looking at — the coordinates in [OWNER]'s \"Looking at block (x, y, z)\"), fairy (centred on your own current body position), coords (an explicit x/y/z you name). Uses the observed owner's dimension, or the body's dimension for fairy; the edit stays there if the owner moves. Unloaded target chunks can fail. Height extends upward from the anchor Y plus y_offset. [EDITS] gives inclusive block bounds of recorded edits. Each side is capped at 128 blocks and width × depth × height must not exceed 200000 blocks per call (including air). Within that limit, the box is chunked into server fills automatically. Undo exists.",
                "{\"type\":\"object\",\"properties\":{\"anchor\":{\"type\":\"string\",\"enum\":[\"owner_front\",\"owner\",\"owner_look\",\"fairy\",\"coords\"]},\"distance\":{\"type\":\"number\",\"description\":\"Blocks in front of the owner — owner_front only.\",\"minimum\":0,\"maximum\":32},\"width\":{\"type\":\"number\",\"description\":\"Sideways size (owner_front) or X size (other anchors).\",\"minimum\":1,\"maximum\":128},\"depth\":{\"type\":\"number\",\"description\":\"Forward size (owner_front) or Z size (other anchors).\",\"minimum\":1,\"maximum\":128},\"height\":{\"type\":\"number\",\"minimum\":1,\"maximum\":128},\"y_offset\":{\"type\":\"number\",\"description\":\"Shift the box up (+) or down (-) from its anchor's ground level.\",\"minimum\":-64,\"maximum\":64},\"block\":{\"type\":\"string\",\"description\":\"A vanilla block id, or air to clear.\",\"maxLength\":64},\"x\":{\"type\":\"number\",\"description\":\"coords anchor only.\"},\"y\":{\"type\":\"number\",\"description\":\"coords anchor only.\"},\"z\":{\"type\":\"number\",\"description\":\"coords anchor only.\"}},\"required\":[\"width\",\"depth\",\"height\",\"block\"]}"));
        tools.add(tool("build", "Design and build a whole structure from a description (\"a small stone watchtower with a lookout platform\") — a separate model writes the actual geometry within a 32x32x24 envelope, terrain in the way is cleared automatically, and it grows in front of the owner in generated command order. The design keeps its world X/Z orientation and is centred sideways on the owner. The coordinates and dimension are captured before geometry generation; moving afterwards does not move this build. Unloaded target chunks can fail. Optional width/depth/height let you choose the available design space; without them the full envelope is available, even for a small description. The geometry response has 8192 output tokens. Generation and command-format failures return as tool results. Undo exists.",
                "{\"type\":\"object\",\"properties\":{\"what\":{\"type\":\"string\",\"description\":\"What to build.\",\"maxLength\":300},\"width\":{\"type\":\"integer\",\"description\":\"Available design width along world X, in blocks.\",\"minimum\":1,\"maximum\":32},\"depth\":{\"type\":\"integer\",\"description\":\"Available design depth along world Z, in blocks.\",\"minimum\":1,\"maximum\":32},\"height\":{\"type\":\"integer\",\"description\":\"Available design height in blocks.\",\"minimum\":1,\"maximum\":24},\"distance\":{\"type\":\"number\",\"description\":\"Blocks in front of the owner the design starts at.\",\"minimum\":1,\"maximum\":16}},\"required\":[\"what\"]}"));
        tools.add(tool("undo", "Revert one past fill/build by its id, or \"last\" for the most recent one still on the undo stack (see [EDITS]).",
                "{\"type\":\"object\",\"properties\":{\"target\":{\"type\":\"string\",\"maxLength\":64}},\"required\":[\"target\"]}"));
        tools.add(tool("find_blocks", "Scan around the owner for a block or tag (e.g. diamond_ore or #logs) and list the nearest matches by distance. Radius up to 48 blocks, at most 12 results.",
                "{\"type\":\"object\",\"properties\":{\"block\":{\"type\":\"string\",\"description\":\"A block id or #tag.\",\"maxLength\":64},\"radius\":{\"type\":\"number\",\"minimum\":1,\"maximum\":48},\"max\":{\"type\":\"number\",\"minimum\":1,\"maximum\":12}},\"required\":[\"block\"]}"));
        tools.add(tool("body", "Control your own visible creature. follow resumes shadowing the owner, stay holds your current spot, come teleports you to the owner. type changes what creature you appear as.",
                "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"follow\",\"stay\",\"come\",\"type\"]},\"type\":{\"type\":\"string\",\"enum\":[\"allay\",\"bee\",\"vex\",\"parrot\",\"bat\"],\"description\":\"Only with action=type.\"}},\"required\":[\"action\"]}"));
        tools.add(tool("lookup", "Look up one Minecraft topic (a block, mob, mechanic…) on the wiki and read a short summary back as a fact.",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":200}},\"required\":[\"query\"]}"));
        tools.add(note(false));
        tools.add(observe(false));
        return tools;
    }
}
