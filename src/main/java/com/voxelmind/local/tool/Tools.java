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
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The model-facing tool catalog. Each description is the tool's contract: what it
 * does mechanically, what it consumes, and what its result reports.
 */
public final class Tools {
    private Tools() {}

    /** What a tool call turns into. */
    public sealed interface Choice permits Body, Stop, Wait, Keep, Say, Silent, Invalid {}

    public record Body(BodyAction action) implements Choice {}

    public record Stop(String call) implements Choice {}

    public record Wait(String call, int seconds, String forWhat) implements Choice {}

    public record Keep() implements Choice {}

    public record Say(String text, String mode, String target) implements Choice {}

    public record Silent() implements Choice {}

    public record Invalid(String call, String reason) implements Choice {}

    private record Spec(String name, String description, JsonObject parameters, BiFunction<JsonObject, String, Choice> factory) {}

    private static final Map<String, Spec> ACTION = new LinkedHashMap<>();
    private static final Map<String, Spec> SPEECH = new LinkedHashMap<>();
    public static final Set<String> SPEECH_NAMES = Set.of("say", "stay_silent");
    /** What walking does at doors, in the description of every tool that walks. */
    private static final String DOORS = " Walking opens wooden and copper doors and fence gates on the way and closes the ones it "
            + "opened behind you; iron doors stay shut.";

    static {
        action("go_to", "Walk to a target: a player name, \"owner\", an entity id like \"#57\", coordinates \"x y z\", a compass direction "
                        + "(about 24 blocks that way), or a creature or block name (the nearest one). One call is one travel leg; the result "
                        + "reports where you arrived or why the route failed. If your owner is more than 64 blocks away you teleport next to them."
                        + DOORS,
                params(req("target", str("Where to go.")), null),
                (a, call) -> new Body(new GoToAction(call, s(a, "target"))));
        action("follow", "Stay close to a moving player or creature. Runs for about a minute, then you decide again. "
                        + "If your owner gets more than 40 blocks ahead you teleport next to them." + DOORS,
                params(req("target", str("Player name, \"owner\" or an entity id like \"#57\".")), null),
                (a, call) -> new Body(new FollowAction(call, s(a, "target"))));
        action("explore", "Walk out in one compass direction to look around: nearby is about 72 blocks, wide about 110. With a "
                        + "target it stops as soon as that comes into view (trees, water, animals, village, ore, or a creature or block "
                        + "name). Walks only, never digs. Without a direction a random one is chosen." + DOORS,
                params(opt("target", str("What to look for.")), opt("direction", enumOf("Compass direction.", "north", "northeast", "east",
                        "southeast", "south", "southwest", "west", "northwest")), opt("scope", enumOf("How far.", "nearby", "wide"))),
                (a, call) -> new Body(new ExploreAction(call, s(a, "target"), s(a, "direction"), s(a, "scope"))));
        action("mine_block", "Mine ONE block — the nearest visible block of a type, or the block at given coordinates. Walks into reach "
                        + "itself, mines blocks in the way first, uses your best carried tool for it (wearing it down), then picks up "
                        + "the drop. Without a suitable tool some blocks (stone, ores) drop nothing. For a quantity use gather." + DOORS,
                params(req("target", str("Block name like \"oak_log\" or coordinates \"x y z\".")), null),
                (a, call) -> new Body(new MineBlockAction(call, s(a, "target"))));
        action("gather", "Collect a quantity of a resource: mines exposed matching blocks you can see nearby (24 blocks), or hunts the "
                        + "animal that drops it, and picks up the drops. Resource words: wood, stone, ore, coal, iron, copper, gold, "
                        + "diamond, redstone, lapis, emerald, any block name (sand, dirt, gravel…), beef, leather, porkchop, mutton, wool, "
                        + "chicken, feather. Uses your best carried tool. Stone and ores drop nothing without a pickaxe of the right tier; "
                        + "it does not craft tools for you. The result reports how many arrived in your inventory." + DOORS,
                params(req("resource", str("What to gather.")), req("amount", intRange("How many items you want (1-64).", 1, 64))),
                (a, call) -> new Body(new GatherAction(call, s(a, "resource"), i(a, "amount", 8))));
        action("dig", "Dig a straight shaft down under your feet, or a two-block-high tunnel forward in the direction you face. "
                        + "Stops before lava, water, big drops and missing floor. Down defaults to 10 blocks (max 64), forward to 8 (max 24).",
                params(req("direction", enumOf("Which way.", "down", "forward")), opt("distance", intRange("Blocks to dig.", 1, 64))),
                (a, call) -> new Body(new DigAction(call, s(a, "direction"), i(a, "distance", 0))));
        action("collect", "Pick up dropped items within 16 blocks — no item picks up everything, an item name just that one. Needs free inventory space."
                        + DOORS,
                params(null, opt("item", str("Optional item name."))),
                (a, call) -> new Body(new CollectAction(call, s(a, "item"))));
        action("craft", "Make an item from materials you carry, using the world's recipes. 2×2 recipes work anywhere; 3×3 recipes need a "
                        + "crafting table within 24 blocks — if there is none and you carry one, it is placed next to you first. Picks the "
                        + "most plentiful matching materials. The item stays in your inventory; use drop to hand it over. Ingots and cooked "
                        + "food come from smelting, not crafting." + DOORS,
                params(req("item", str("Item to make, e.g. \"wooden_pickaxe\".")), opt("amount", intRange("How many items (default 1).", 1, 64))),
                (a, call) -> new Body(new CraftAction(call, s(a, "item"), i(a, "amount", 1))));
        action("smelt", "Smelt or cook in a furnace within 24 blocks (if there is none and you carry one, it is placed next to you): "
                        + "puts the input and the fuel you name into it, waits next to it and takes the results (about 10 s per item). "
                        + "item may be the input (raw_iron, beef, sand…) or the wanted output (iron_ingot, cooked_beef, glass…). "
                        + "Without amount it smelts all you carry of the input, up to 64. If you leave early, the rest stays in the furnace; "
                        + "collect_furnace takes it out later." + DOORS,
                params(req("item", str("Input or output item.")), opt("amount", intRange("How many to smelt.", 1, 64)),
                        opt("fuel", str("Which carried fuel to burn, e.g. coal, charcoal, oak_planks; needed when you carry more than one kind."))),
                (a, call) -> new Body(new SmeltAction(call, s(a, "item"), i(a, "amount", 0), s(a, "fuel"))));
        action("collect_furnace", "Take finished items out of the nearest furnace that has some, or the one at the given coordinates." + DOORS,
                params(null, opt("pos", str("\"x y z\" of a furnace."))),
                (a, call) -> new Body(new CollectFurnaceAction(call, s(a, "pos"))));
        action("use_chest", "Open the nearest chest or barrel within 24 blocks (or one at given coordinates): view its contents, "
                        + "store items from your inventory, or retrieve items from it." + DOORS,
                params(req("action", enumOf("What to do.", "view", "store", "retrieve")), opt("item", str("Item name; needed for store and retrieve.")),
                        opt("count", intRange("How many; -1 for all (default 1).", -1, 2304)), opt("pos", str("\"x y z\" of the chest."))),
                (a, call) -> new Body(new ChestAction(call, s(a, "action"), s(a, "item"), i(a, "count", 1), s(a, "pos"))));
        action("use_block", "Right-click one block with what your main hand holds, as a player does: walks (never digs) to where you "
                        + "see it within reach, looks at it and uses it. Doors, trapdoors and fence gates open or close, levers and buttons "
                        + "switch, note blocks, repeaters and comparators change their setting, bells ring; iron doors and iron trapdoors "
                        + "do not react to a hand. Where the block itself does not react, the held item acts on it (a carried block is "
                        + "placed against it, a hoe tills). A screen the block opens (chest, crafting table, furnace…) is closed again at "
                        + "once and a bed is left again at once; use_chest, craft, smelt and sleep handle those. The result reports the "
                        + "block state before and after, e.g. open false -> true." + DOORS,
                params(req("x", integer("X coordinate of the block.")), req("y", intRange("Y coordinate of the block.", -64, 320)),
                        req("z", integer("Z coordinate of the block."))),
                (a, call) -> new Body(new com.voxelmind.local.action.UseBlockAction(call,
                        new net.minecraft.core.BlockPos(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt()))));
        action("attack", "Fight a creature or player with whatever you hold (equip a weapon first if you want one). Against a hostile mob "
                        + "the fight continues with the next hostile within 5 blocks until none is left; a player or animal stays a single target."
                        + DOORS,
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
                        + "it does not confirm that they picked them up." + DOORS,
                params(req("item", str("Item name.")), opt("count", intRange("How many; -1 for all you carry (default 1).", -1, 2304)),
                        opt("target", str("Player name, \"owner\", or \"here\"."))),
                (a, call) -> new Body(new DropAction(call, s(a, "item"), i(a, "count", 1), s(a, "target"))));
        action("place_block", "Place one carried block at coordinates, in the cell in front of you, or under your feet (you jump and "
                        + "place it beneath you, which builds a pillar step). It needs a solid neighbour to place against and does not "
                        + "clear an occupied cell." + DOORS,
                params(req("block", str("Block item name, e.g. \"cobblestone\".")), req("pos", str("\"x y z\", \"in_front\" or \"under_feet\"."))),
                (a, call) -> new Body(new PlaceBlockAction(call, s(a, "block"), s(a, "pos"))));
        action("place_blocks", "Build: place up to 128 carried blocks at the given coordinates, lowest first, walking into reach as "
                        + "needed (routes may dig through natural terrain but never through the build or its block types). Coordinates are where each block goes: on ground whose "
                        + "top block is at height h, the first layer is y=h+1 (survey shows ground heights). Cells that are occupied, have no "
                        + "solid neighbour yet, or whose block you run out of are skipped; a second pass retries cells that gained support. "
                        + "The result reports how many were placed and which were skipped." + DOORS,
                blocksParams(),
                (a, call) -> new Body(new PlaceBlocksAction(call, entries(a))));
        action("build_shelter", "Build a 5×5 hut: walls two blocks high, a two-block door gap facing your owner, and a flat roof — "
                        + "about 55 blocks of one carried material (planks, cobblestone, dirt…). Walls reach down to the ground where it dips. "
                        + "Without center it is built about 4 blocks in front of you. Takes a while; the result reports what was placed." + DOORS,
                params(opt("material", str("Block to build with; needed when you carry more than one suitable kind.")),
                        opt("center", str("\"x z\" of the hut's middle."))),
                (a, call) -> new Body(new BuildShelterAction(call, s(a, "material"), s(a, "center"))));
        action("survey", "Read the ground around a point without moving: a grid of the top solid block's y for every column "
                        + "(stand on it at y+1; a block built on it goes at y+1), with water, lava and trees marked. Use it before building "
                        + "or when the terrain matters. Radius 1-8, default 5.",
                params(opt("center", str("\"x z\", \"x y z\" or \"here\" (default).")), opt("radius", intRange("Blocks around the center.", 1, 8))),
                (a, call) -> new Body(new SurveyAction(call, s(a, "center"), i(a, "radius", 5))));
        action("sleep", "Walk to the nearest bed within 24 blocks and lie down; you stay in bed until morning or until another action. "
                        + "Only works at night or in a thunderstorm and with no monsters close." + DOORS,
                params(null, null),
                (a, call) -> new Body(new SleepAction(call)));
        action("stop", "Stop the current body action and stand still.",
                params(null, null),
                (a, call) -> new Stop(call));
        action("wait", "Do nothing new for 1-300 seconds. A running body action continues; use stop to halt it. Any new event ends the wait early.",
                params(req("seconds", intRange("How long.", 1, 300)), opt("for_what", str("What you are waiting for, max 60 characters."))),
                (a, call) -> new Wait(call, i(a, "seconds", 10), s(a, "for_what")));
        action("keep_current_action", "Leave the running body action unchanged.",
                params(null, null),
                (a, call) -> new Keep());

        speech("say", "Send one Minecraft chat line: a reply, question, conversation opener, observation, or anything else you choose to say.",
                params(req("text", str("What to say now, normally one short Minecraft-chat sentence (max 256 characters).")),
                        opt("mode", enumOf("say: players within 48 blocks hear it; whisper: only the target; yell: everyone online.", "say", "whisper", "yell")),
                        opt("target", str("Player name; required for whisper."))),
                (a, call) -> new Say(s(a, "text"), s(a, "mode").isEmpty() ? "say" : s(a, "mode"), s(a, "target")));
        speech("stay_silent", "Choose to say nothing this turn.",
                params(null, null),
                (a, call) -> new Silent());
    }

    private static void action(String name, String description, JsonObject parameters, BiFunction<JsonObject, String, Choice> factory) {
        ACTION.put(name, new Spec(name, description, parameters, factory));
    }

    private static void speech(String name, String description, JsonObject parameters, BiFunction<JsonObject, String, Choice> factory) {
        SPEECH.put(name, new Spec(name, description, parameters, factory));
    }

    /**
     * Action-lane tools; keep_current_action only while a body action runs, and last, so both variants share the
     * same tool prefix for the provider's prompt cache.
     */
    public static JsonArray actionTools(boolean bodyActionRunning) {
        JsonArray out = new JsonArray();
        for (Spec spec : ACTION.values()) {
            if (spec.name().equals("keep_current_action")) continue;
            out.add(schema(spec));
        }
        if (bodyActionRunning) out.add(schema(ACTION.get("keep_current_action")));
        return out;
    }

    public static JsonArray speechTools() {
        JsonArray out = new JsonArray();
        for (Spec spec : SPEECH.values()) out.add(schema(spec));
        return out;
    }

    /** Compact one-line-per-tool reference of the body interface, for the speech lane. */
    public static String bodyReference() {
        StringBuilder out = new StringBuilder();
        for (Spec spec : ACTION.values()) out.append(schema(spec).getAsJsonObject("function")).append('\n');
        return out.toString();
    }

    private static JsonObject schema(Spec spec) {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", spec.name());
        fn.addProperty("description", spec.description());
        fn.add("parameters", spec.parameters());
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", fn);
        return tool;
    }

    /** Turns one model tool call into a choice; never throws. */
    public static Choice parse(String name, @Nullable JsonObject args, boolean speechLane) {
        Spec spec = (speechLane ? SPEECH : ACTION).get(name);
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
