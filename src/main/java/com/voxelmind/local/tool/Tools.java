package com.voxelmind.local.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.voxelmind.local.action.AttackAction;
import com.voxelmind.local.action.BodyAction;
import com.voxelmind.local.action.BuildShelterAction;
import com.voxelmind.local.action.ChestAction;
import com.voxelmind.local.action.CollectAction;
import com.voxelmind.local.action.CollectFurnaceAction;
import com.voxelmind.local.action.CraftAction;
import com.voxelmind.local.action.DigAction;
import com.voxelmind.local.action.DropAction;
import com.voxelmind.local.action.EatAction;
import com.voxelmind.local.action.EquipAction;
import com.voxelmind.local.action.ExploreAction;
import com.voxelmind.local.action.FollowAction;
import com.voxelmind.local.action.GatherAction;
import com.voxelmind.local.action.GoToAction;
import com.voxelmind.local.action.MineBlockAction;
import com.voxelmind.local.action.PlaceBlockAction;
import com.voxelmind.local.action.PlaceBlocksAction;
import com.voxelmind.local.action.SmeltAction;
import com.voxelmind.local.action.SurveyAction;
import com.voxelmind.local.action.SleepAction;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The model-facing tool catalog. Each description is the tool's contract: what it
 * does mechanically, what it consumes, and what its result reports.
 */
public final class Tools {
    private Tools() {}

    /** What a tool call turns into. */
    public sealed interface Choice permits Body, Stop, Wait, Keep, Say, Invalid {}

    public record Body(BodyAction action) implements Choice {}

    public record Stop(String call) implements Choice {}

    public record Wait(String call, int seconds, String forWhat) implements Choice {}

    public record Keep() implements Choice {}

    public record Say(String text, String mode, String target) implements Choice {}

    public record Invalid(String call, String reason) implements Choice {}

    private record Spec(String name, String description, JsonObject parameters, BiFunction<JsonObject, String, Choice> factory) {}

    private static final Map<String, Spec> ACTION = new LinkedHashMap<>();
    private static final Map<String, Spec> SPEECH = new LinkedHashMap<>();

    /**
     * Every body-type tool takes this optional argument: a chat line said as the call is made. Models that return a
     * single tool call per response (gpt-oss, Player2's free model) can so speak and act in one decision.
     */
    public static final String SAY_ARGUMENT = "say";
    /** Who hears the say argument: players within 48 blocks when left out, "everyone", or one player (a whisper). */
    public static final String SAY_TO_ARGUMENT = "say_to";
    /** What routes may dig, in the description of every tool whose walk may dig. */
    private static final String ROUTES = " Routes may dig through natural terrain and their own pillar steps, never through other "
            + "blocks a player placed.";
    /** What walking does at doors, in the description of every tool that walks. */
    private static final String DOORS = " Walking opens doors and fence gates on the way (iron doors only through a button or lever "
            + "beside them on your side) and closes the ones it opened once past them; the result names any it left open (someone "
            + "stood in it, you stopped in it, or you were moved away).";
    /** Both, for the tools whose walk may dig. */
    private static final String WALKS = ROUTES + DOORS;

    static {
        action("go_to", "Walk to a target: a player name, \"owner\", an entity id like \"#57\", coordinates \"x y z\", a compass direction "
                        + "(about 24 blocks that way), or a creature or block name (the nearest one). One call is one travel leg; the result "
                        + "reports where you arrived or why the route failed. If your owner is more than 64 blocks away you teleport next to them. "
                        + "A door or fence gate as target: you end in its doorway; with through true you walk through it and end in the cell "
                        + "just past it on the far side from where you stand (from inside the doorway: the side you face)." + WALKS,
                params(req("target", str("Where to go.")),
                        opt("through", bool("With a door or fence gate as target: true walks through it to its far side; default false."))),
                (a, call) -> new Body(new GoToAction(call, s(a, "target"), b(a, "through"))));
        action("follow", "Stay close to a moving player or creature. Runs for about a minute, then you decide again. "
                        + "If your owner gets more than 40 blocks ahead you teleport next to them." + WALKS,
                params(req("target", str("Player name, \"owner\" or an entity id like \"#57\".")), null),
                (a, call) -> new Body(new FollowAction(call, s(a, "target"))));
        action("explore", "Walk out in one compass direction to look around: nearby is about 72 blocks, wide about 110. With a "
                        + "target it stops as soon as that comes into view (trees, water, animals, village, ore, or a creature or block "
                        + "name; blocks a player placed do not count). Walks only, never digs. Without a direction a random one is chosen."
                        + DOORS,
                params(opt("target", str("What to look for.")), opt("direction", enumOf("Compass direction.", "north", "northeast", "east",
                        "southeast", "south", "southwest", "west", "northwest")), opt("scope", enumOf("How far.", "nearby", "wide"))),
                (a, call) -> new Body(new ExploreAction(call, s(a, "target"), s(a, "direction"), s(a, "scope"))));
        action("mine_block", "Mine ONE block: the nearest visible block of a type that no player placed (include_placed true takes "
                        + "placed ones too), or exactly the block at given coordinates (the result names who placed it, if a player did). "
                        + "Walks into reach itself and mines blocks in the way first, but stops and names the block when one of those was "
                        + "placed by a player or is a door, gate or trapdoor. Uses your best carried tool for it (wearing it down), then "
                        + "picks up the drop. Without a suitable tool some blocks (stone, ores) drop nothing. For a quantity use gather."
                        + WALKS,
                params(req("target", str("Block name like \"oak_log\" or coordinates \"x y z\".")),
                        opt("include_placed", bool("true: by type, also blocks a player placed or planted; default false."))),
                (a, call) -> new Body(new MineBlockAction(call, s(a, "target"), b(a, "include_placed"))));
        action("gather", "Collect a quantity of a resource: mines exposed matching blocks you can see nearby (24 blocks) that no "
                        + "player placed (include_placed true takes placed and planted ones too, e.g. a farm's crops), or hunts the animal "
                        + "that drops it, and picks up the drops. Resource words: wood, stone, ore, coal, iron, copper, gold, diamond, "
                        + "redstone, lapis, emerald, any block name (sand, dirt, gravel, carrots…), beef, leather, porkchop, mutton, wool, "
                        + "chicken, feather. Uses your best carried tool. Blocks in the way are mined first unless a player placed them or "
                        + "they are doors, gates or trapdoors. Stone and ores drop nothing without a pickaxe of the right tier; it does not "
                        + "craft tools for you. The result reports how many arrived in your inventory and which broken blocks a player had "
                        + "placed." + WALKS,
                params(req("resource", str("What to gather.")), req("amount", intRange("How many items you want (1-64).", 1, 64)),
                        opt("include_placed", bool("true: also blocks a player placed or planted; default false."))),
                (a, call) -> new Body(new GatherAction(call, s(a, "resource"), i(a, "amount", 8), b(a, "include_placed"))));
        action("dig", "Dig a straight shaft down under your feet, or a two-block-high tunnel forward in the direction you face. "
                        + "It breaks what is there, blocks a player placed included; the result names those and who placed them. "
                        + "Stops before lava, water, big drops and missing floor. Down defaults to 10 blocks (max 64), forward to 8 (max 24).",
                params(req("direction", enumOf("Which way.", "down", "forward")), opt("distance", intRange("Blocks to dig.", 1, 64))),
                (a, call) -> new Body(new DigAction(call, s(a, "direction"), i(a, "distance", 0))));
        action("collect", "Pick up dropped items within 16 blocks — no item picks up everything, an item name just that one. Needs free inventory space."
                        + WALKS,
                params(null, opt("item", str("Optional item name."))),
                (a, call) -> new Body(new CollectAction(call, s(a, "item"))));
        action("craft", "Make an item from materials you carry, using the world's recipes. 2×2 recipes work anywhere; 3×3 recipes need a "
                        + "crafting table within 24 blocks — if there is none and you carry one, it is placed next to you first. Picks the "
                        + "most plentiful matching materials. The item stays in your inventory; use drop to hand it over. Ingots and cooked "
                        + "food come from smelting, not crafting." + WALKS,
                params(req("item", str("Item to make, e.g. \"wooden_pickaxe\".")), opt("amount", intRange("How many items (default 1).", 1, 64))),
                (a, call) -> new Body(new CraftAction(call, s(a, "item"), i(a, "amount", 1))));
        action("smelt", "Smelt or cook in a furnace within 24 blocks (if there is none and you carry one, it is placed next to you): "
                        + "puts the input and the fuel you name into it, waits next to it and takes the results (about 10 s per item). "
                        + "item may be the input (raw_iron, beef, sand…) or the wanted output (iron_ingot, cooked_beef, glass…). "
                        + "Without amount it smelts all you carry of the input, up to 64. If you leave early, the rest stays in the furnace; "
                        + "collect_furnace takes it out later." + WALKS,
                params(req("item", str("Input or output item.")), opt("amount", intRange("How many to smelt.", 1, 64)),
                        opt("fuel", str("Which carried fuel to burn, e.g. coal, charcoal, oak_planks; needed when you carry more than one kind."))),
                (a, call) -> new Body(new SmeltAction(call, s(a, "item"), i(a, "amount", 0), s(a, "fuel"))));
        action("collect_furnace", "Take finished items out of the nearest furnace that has some, or the one at the given coordinates." + WALKS,
                params(null, opt("pos", str("\"x y z\" of a furnace."))),
                (a, call) -> new Body(new CollectFurnaceAction(call, s(a, "pos"))));
        action("use_chest", "Open the nearest chest or barrel within 24 blocks (or one at given coordinates): view its contents, "
                        + "store items from your inventory, or retrieve items from it." + WALKS,
                params(req("action", enumOf("What to do.", "view", "store", "retrieve")), opt("item", str("Item name; needed for store and retrieve.")),
                        opt("count", intRange("How many; -1 for all (default 1).", -1, 2304)), opt("pos", str("\"x y z\" of the chest."))),
                (a, call) -> new Body(new ChestAction(call, s(a, "action"), s(a, "item"), i(a, "count", 1), s(a, "pos"))));
        action("use_block", "Right-click one block with what your main hand holds, as a player does: walks (never digs) to where you "
                        + "see it within reach, looks at it and uses it. Doors, trapdoors and fence gates open or close, levers and buttons "
                        + "switch, note blocks, repeaters and comparators change their setting, bells ring; iron doors and iron trapdoors "
                        + "do not react to a hand. Where the block itself does not react, the held item acts on it (a carried block is "
                        + "placed against it, a hoe tills). A screen the block opens (chest, crafting table, furnace…) is closed again at "
                        + "once and a bed is left again at once; use_chest, craft, smelt and sleep handle those. Using a bed sets your "
                        + "respawn point to it, even by day; in the Nether and the End a bed explodes, as for any player. The result reports "
                        + "the block state before and after, e.g. open false -> true, and a changed respawn point." + DOORS,
                params(req("x", integer("X coordinate of the block.")), req("y", intRange("Y coordinate of the block.", -64, 320)),
                        req("z", integer("Z coordinate of the block."))),
                (a, call) -> new Body(new com.voxelmind.local.action.UseBlockAction(call,
                        new net.minecraft.core.BlockPos(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt()))));
        action("attack", "Fight a creature or player with whatever you hold (equip a weapon first if you want one). Against a hostile mob "
                        + "the fight continues with the next hostile within 5 blocks until none is left; a player or animal stays a single target."
                        + WALKS,
                params(req("target", str("Entity id like \"#120\", a creature name like \"zombie\" (nearest), or a player name.")), null),
                (a, call) -> new Body(new AttackAction(call, s(a, "target"))));
        action("eat", "Eat one piece of a food you carry. Most food can only be eaten when not full. The result reports food and HP before and after.",
                params(null, opt("item", str("Which food to eat; needed when you carry more than one kind."))),
                (a, call) -> new Body(new EatAction(call, s(a, "item"))));
        action("equip", "Put on armor, hold a tool or weapon in your main hand, or put a shield in your off hand — the slot is chosen from the item.",
                params(req("item", str("Item name.")), null),
                (a, call) -> new Body(new EquipAction(call, s(a, "item"))));
        action("drop", "Throw items out of your inventory — toward a player when you name one (walking close first), otherwise onto the "
                        + "ground. Items thrown to a player can only be picked up by that player. The result reports how many left your inventory; "
                        + "it does not confirm that they picked them up." + WALKS,
                params(req("item", str("Item name.")), opt("count", intRange("How many; -1 for all you carry (default 1).", -1, 2304)),
                        opt("target", str("Player name, \"owner\", or \"here\"."))),
                (a, call) -> new Body(new DropAction(call, s(a, "item"), i(a, "count", 1), s(a, "target"))));
        action("place_block", "Place one carried block at coordinates, in the cell in front of you, or under your feet (you jump and "
                        + "place it beneath you, which builds a pillar step). It needs a solid neighbour to place against and does not "
                        + "clear an occupied cell." + WALKS,
                params(req("block", str("Block item name, e.g. \"cobblestone\".")), req("pos", str("\"x y z\", \"in_front\" or \"under_feet\"."))),
                (a, call) -> new Body(new PlaceBlockAction(call, s(a, "block"), s(a, "pos"))));
        action("place_blocks", "Build: place up to 128 carried blocks at the given coordinates, lowest first, walking into reach as "
                        + "needed (routes never dig through the build or its block types either). Coordinates are where each block goes: on ground whose "
                        + "top block is at height h, the first layer is y=h+1 (survey shows ground heights). Cells that are occupied, have no "
                        + "solid neighbour yet, or whose block you run out of are skipped; a second pass retries cells that gained support. "
                        + "The result reports how many were placed and which were skipped." + WALKS,
                blocksParams(),
                (a, call) -> new Body(new PlaceBlocksAction(call, entries(a))));
        action("build_shelter", "Build a 5×5 hut: walls two blocks high, a two-block door gap facing your owner, and a flat roof — "
                        + "55 blocks of one carried material (planks, cobblestone, dirt…) on level ground; walls reach down to the ground where "
                        + "it dips, which takes more. Trees are not ground: where tree trunks or branches stand in the hut's cells nothing is "
                        + "built and the result names them. Without center it is built about 4 blocks in front of you. Takes a while; the "
                        + "result reports what was placed." + WALKS,
                params(opt("material", str("Block to build with; needed when you carry more than one suitable kind.")),
                        opt("center", str("\"x z\" of the hut's middle."))),
                (a, call) -> new Body(new BuildShelterAction(call, s(a, "material"), s(a, "center"))));
        action("survey", "Read the ground around a point without moving: a grid of the top solid block's y for every column, trees "
                        + "not counted as ground (stand on it at y+1; a block built on it goes at y+1), with water, lava, tree trunks and "
                        + "leaves overhead marked. Use it before building "
                        + "or when the terrain matters. Radius 1-8, default 5.",
                params(opt("center", str("\"x z\", \"x y z\" or \"here\" (default).")), opt("radius", intRange("Blocks around the center.", 1, 8))),
                (a, call) -> new Body(new SurveyAction(call, s(a, "center"), i(a, "radius", 5))));
        action("sleep", "Walk to the nearest bed within 24 blocks and lie down; you stay in bed until morning or until another action. "
                        + "Only works at night or in a thunderstorm and with no monsters close. Lying down sets your respawn point to that "
                        + "bed, even when sleep is refused by day." + WALKS,
                params(null, null),
                (a, call) -> new Body(new SleepAction(call)));
        action("stop", "Stop the current body action and stand still.",
                params(null, null),
                (a, call) -> new Stop(call));
        action("wait", "Do nothing new for 1-300 seconds. A running body action continues; use stop to halt it. Any new event ends the wait early.",
                params(req("seconds", intRange("How long.", 1, 300)), opt("for_what", str("What you are waiting for, max 60 characters."))),
                (a, call) -> new Wait(call, i(a, "seconds", 10), s(a, "for_what")));
        action("keep_current_action", "Change nothing: a running body action and a running wait continue.",
                params(null, null),
                (a, call) -> new Keep());

        speech("say", "Send one Minecraft chat line: a reply, question, conversation opener, observation, or anything else you choose to say.",
                params(req("text", str("What to say now, normally one short Minecraft-chat sentence (max 256 characters).")),
                        opt("mode", enumOf("say: players within 48 blocks hear it; whisper: only the target; yell: everyone online.", "say", "whisper", "yell")),
                        opt("target", str("Player name or \"owner\"; required for whisper."))),
                (a, call) -> new Say(s(a, "text"), s(a, "mode").isEmpty() ? "say" : s(a, "mode"), s(a, "target")));
    }

    private static void action(String name, String description, JsonObject parameters, BiFunction<JsonObject, String, Choice> factory) {
        ACTION.put(name, new Spec(name, description, parameters, factory));
    }

    private static void speech(String name, String description, JsonObject parameters, BiFunction<JsonObject, String, Choice> factory) {
        SPEECH.put(name, new Spec(name, description, parameters, factory));
    }

    /**
     * The tools of one decision: the body tools, say, and keep_current_action only while a body action or a wait runs
     * ({@code keepable}). It comes last, so both variants share the same tool prefix for the provider's prompt cache.
     */
    public static JsonArray decisionTools(boolean keepable) {
        JsonArray out = new JsonArray();
        for (Spec spec : ACTION.values()) {
            if (spec.name().equals("keep_current_action")) continue;
            out.add(schema(spec, true));
        }
        for (Spec spec : SPEECH.values()) out.add(schema(spec, false));
        if (keepable) out.add(schema(ACTION.get("keep_current_action"), true));
        return out;
    }

    private static JsonObject schema(Spec spec, boolean sayArgument) {
        JsonObject parameters = spec.parameters();
        if (sayArgument) {
            parameters = parameters.deepCopy();
            JsonObject properties = parameters.getAsJsonObject("properties");
            properties.add(SAY_ARGUMENT, str("Optional: one chat line, sent the moment this call starts, before the call has any result; its "
                    + "result comes back later as a fact (max 256 characters)."));
            properties.add(SAY_TO_ARGUMENT, str("Optional: who hears the say line: left out, the players within 48 blocks; \"everyone\": "
                    + "all players online; a player's name or \"owner\": only that player (a whisper)."));
        }
        JsonObject fn = new JsonObject();
        fn.addProperty("name", spec.name());
        fn.addProperty("description", spec.description());
        fn.add("parameters", parameters);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", fn);
        return tool;
    }

    /** A line to say: its text, the say tool's mode (say, whisper or yell) and, for a whisper, the player. */
    public record Line(String text, String mode, String target) {}

    /**
     * The say argument of a body-type call with who hears it ({@link #SAY_TO_ARGUMENT}), or null without a line; it is
     * spoken, not part of the action.
     */
    public static @Nullable Line sayArgument(String name, @Nullable JsonObject args) {
        if (args == null || !ACTION.containsKey(name) || !args.has(SAY_ARGUMENT) || !args.get(SAY_ARGUMENT).isJsonPrimitive()) return null;
        String text = args.get(SAY_ARGUMENT).getAsString().trim();
        if (text.isEmpty()) return null;
        String to = args.has(SAY_TO_ARGUMENT) && args.get(SAY_TO_ARGUMENT).isJsonPrimitive() ? args.get(SAY_TO_ARGUMENT).getAsString().trim() : "";
        String key = to.toLowerCase(java.util.Locale.ROOT);
        if (to.isEmpty() || key.equals("near") || key.equals("nearby")) return new Line(text, "say", "");
        if (key.equals("everyone") || key.equals("all")) return new Line(text, "yell", "");
        return new Line(text, "whisper", to);
    }

    /**
     * Turns one model tool call into a choice; never throws. A body-type call's say argument and its say_to are taken
     * out first, so the action and its call text stay the same whatever is said with it.
     */
    public static Choice parse(String name, @Nullable JsonObject args) {
        if (args != null && ACTION.containsKey(name) && (args.has(SAY_ARGUMENT) || args.has(SAY_TO_ARGUMENT))) {
            args = args.deepCopy();
            args.remove(SAY_ARGUMENT);
            args.remove(SAY_TO_ARGUMENT);
        }
        Spec spec = ACTION.containsKey(name) ? ACTION.get(name) : SPEECH.get(name);
        String call = callText(name, args);
        if (spec == null) return new Invalid(call, "unknown tool '" + name + "' for this decision");
        if (args == null) return new Invalid(call, "arguments were not valid JSON");
        JsonArray required = spec.parameters().getAsJsonArray("required");
        if (required != null) {
            for (JsonElement r : required) {
                String key = r.getAsString();
                if (!args.has(key) || args.get(key).isJsonNull() || (args.get(key).isJsonPrimitive() && args.get(key).getAsString().isBlank())) {
                    return new Invalid(call, "missing required argument '" + key + "'");
                }
            }
        }
        try {
            return spec.factory().apply(args, call);
        } catch (Exception e) {
            return new Invalid(call, "cannot execute: " + e.getMessage());
        }
    }

    public static String callText(String name, @Nullable JsonObject args) {
        StringBuilder out = new StringBuilder(name).append('(');
        if (args != null) {
            int n = 0;
            for (Map.Entry<String, JsonElement> e : args.entrySet()) {
                if (n++ > 0) out.append(", ");
                out.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return out.append(')').toString();
    }

    // --- schema helpers ---

    private static JsonObject blocksParams() {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "object");
        JsonObject props = new JsonObject();
        // No world-border bounds on x/z here: Gemini rejects range bounds nested inside a list with maxItems.
        props.add("x", integer("X coordinate."));
        props.add("y", intRange("Y coordinate.", -64, 320));
        props.add("z", integer("Z coordinate."));
        props.add("block", str("Block item name, e.g. \"oak_planks\"."));
        entry.add("properties", props);
        JsonArray req = new JsonArray();
        for (String k : new String[]{"x", "y", "z", "block"}) req.add(k);
        entry.add("required", req);
        JsonObject list = new JsonObject();
        list.addProperty("type", "array");
        list.addProperty("description", "Blocks to place.");
        list.add("items", entry);
        list.addProperty("maxItems", PlaceBlocksAction.MAX);
        return params(req("blocks", list));
    }

    private static java.util.List<PlaceBlocksAction.Entry> entries(JsonObject a) {
        java.util.List<PlaceBlocksAction.Entry> out = new java.util.ArrayList<>();
        if (!a.has("blocks") || !a.get("blocks").isJsonArray()) throw new IllegalArgumentException("blocks must be a list of {x,y,z,block}");
        for (JsonElement e : a.getAsJsonArray("blocks")) {
            JsonObject o = e.getAsJsonObject();
            out.add(new PlaceBlocksAction.Entry(new net.minecraft.core.BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()),
                    o.get("block").getAsString()));
        }
        return out;
    }

    private record Param(String name, JsonObject schema, boolean required) {}

    private static Param req(String name, JsonObject schema) {
        return new Param(name, schema, true);
    }

    private static Param opt(String name, JsonObject schema) {
        return new Param(name, schema, false);
    }

    private static JsonObject params(@Nullable Param... params) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        if (params != null) {
            for (Param p : params) {
                if (p == null) continue;
                props.add(p.name(), p.schema());
                if (p.required()) required.add(p.name());
            }
        }
        obj.add("properties", props);
        obj.add("required", required);
        return obj;
    }

    private static JsonObject str(String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", description);
        return o;
    }

    private static JsonObject intRange(String description, int min, int max) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "integer");
        o.addProperty("description", description);
        o.addProperty("minimum", min);
        o.addProperty("maximum", max);
        return o;
    }

    private static JsonObject integer(String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "integer");
        o.addProperty("description", description);
        return o;
    }

    private static JsonObject bool(String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "boolean");
        o.addProperty("description", description);
        return o;
    }

    private static JsonObject enumOf(String description, String... values) {
        JsonObject o = str(description);
        JsonArray e = new JsonArray();
        for (String v : values) e.add(v);
        o.add("enum", e);
        return o;
    }

    private static String s(JsonObject a, String key) {
        if (!a.has(key) || a.get(key).isJsonNull()) return "";
        JsonElement e = a.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    /** A boolean argument; models also send "true" as a string. */
    private static boolean b(JsonObject a, String key) {
        if (!a.has(key) || a.get(key).isJsonNull() || !a.get(key).isJsonPrimitive()) return false;
        return a.get(key).getAsJsonPrimitive().isBoolean() ? a.get(key).getAsBoolean() : a.get(key).getAsString().trim().equalsIgnoreCase("true");
    }

    private static int i(JsonObject a, String key, int fallback) {
        if (!a.has(key) || a.get(key).isJsonNull()) return fallback;
        try {
            return (int) Math.round(a.get(key).getAsDouble());
        } catch (Exception e) {
            return fallback;
        }
    }

    public static List<String> actionNames() {
        return new ArrayList<>(ACTION.keySet());
    }
}
