package com.voxelmind.local.mind;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * A brain: what a companion's model is told and given. It replaces parts of the prompt (frame, conversation rules,
 * decision rule, default character, memory guidance), picks which context blocks and how much timeline go in, which body
 * tools are offered, and the temperature. It is data only: no code runs from a brain. What it cannot change is the core:
 * the body and how tools are carried out, the AI label, the safety rule (appended to every brain's frame), permissions and
 * the request budget. See brains/README.md for the format.
 */
public final class Brain {
    public static final String DEFAULT = "default";
    /** Body tools a brain cannot take away: speaking and controlling the running action. */
    public static final Set<String> CORE_TOOLS = Set.of("say", "stop", "wait", "keep_current_action");

    public final String id;
    public final String name;
    public final String version;
    public final String author;
    public final String description;
    public final List<String> models;
    public final String minMod;
    /** Shipped inside the mod; only these are counted by name in the anonymous usage numbers. */
    public final boolean builtin;
    public final @Nullable Double temperature;
    public final @Nullable Integer maxTokens;
    /** Body tools offered: all when {@code include} is empty, minus {@code exclude}; the core tools always stay. */
    public final Set<String> include;
    public final Set<String> exclude;
    public final int timelineEvents;
    public final int timelineChats;
    public final int chatMinutes;
    public final int chatChars;
    public final boolean sensory;
    public final boolean bodyInterface;
    public final boolean clock;
    public final boolean lastAction;
    public final boolean previously;
    public final boolean aboutOwner;
    public final boolean memory;
    public final @Nullable String frame;
    public final @Nullable String conversation;
    public final @Nullable String decision;
    public final @Nullable String identity;
    public final @Nullable String memoryGuide;

    Brain(String id, boolean builtin, JsonObject json, @Nullable String frame, @Nullable String conversation, @Nullable String decision,
          @Nullable String identity, @Nullable String memoryGuide) {
        this.id = id;
        this.builtin = builtin;
        this.name = str(json, "name", id);
        this.version = str(json, "version", "1.0.0");
        this.author = str(json, "author", "");
        this.description = str(json, "description", "");
        this.models = list(json, "models");
        this.minMod = str(json, "minMod", "");
        this.temperature = json.has("temperature") ? clamp(json.get("temperature").getAsDouble(), 0, 2) : null;
        this.maxTokens = json.has("maxTokens") ? (int) clamp(json.get("maxTokens").getAsInt(), 256, 16384) : null;
        JsonObject tools = json.has("tools") && json.get("tools").isJsonObject() ? json.getAsJsonObject("tools") : new JsonObject();
        this.include = new TreeSet<>(list(tools, "include"));
        this.exclude = new TreeSet<>(list(tools, "exclude"));
        JsonObject timeline = json.has("timeline") && json.get("timeline").isJsonObject() ? json.getAsJsonObject("timeline") : new JsonObject();
        this.timelineEvents = (int) clamp(num(timeline, "events", 30), 5, 60);
        this.timelineChats = (int) clamp(num(timeline, "chats", 24), 4, 60);
        this.chatMinutes = (int) clamp(num(timeline, "chatMinutes", 30), 1, 240);
        this.chatChars = (int) clamp(num(timeline, "chatChars", 6000), 500, 20000);
        JsonObject blocks = json.has("blocks") && json.get("blocks").isJsonObject() ? json.getAsJsonObject("blocks") : new JsonObject();
        this.sensory = flag(blocks, "sensory");
        this.bodyInterface = flag(blocks, "bodyInterface");
        this.clock = flag(blocks, "clock");
        this.lastAction = flag(blocks, "lastAction");
        this.previously = flag(blocks, "previously");
        this.aboutOwner = flag(blocks, "aboutOwner");
        this.memory = !json.has("memory") || json.get("memory").getAsBoolean();
        this.frame = frame;
        this.conversation = conversation;
        this.decision = decision;
        this.identity = identity;
        this.memoryGuide = memoryGuide;
    }

    /** Whether a body tool is offered and may be carried out under this brain. */
    public boolean allows(String tool) {
        if (CORE_TOOLS.contains(tool)) return true;
        if (!include.isEmpty() && !include.contains(tool)) return false;
        return !exclude.contains(tool);
    }

    /** The name the anonymous usage count uses: a brain shipped in the mod by its id, any other as "custom". */
    public String statsId() {
        return builtin ? id : "custom";
    }

    /** "VoxelMind Lite 1.0.0 by VoxelMind". */
    public String label() {
        return name + " " + version + (author.isBlank() ? "" : " by " + author);
    }

    /** {name} and {owner} in a brain's texts. */
    static String fill(String text, String name, String owner) {
        return text.replace("{name}", name).replace("{owner}", owner);
    }

    private static String str(JsonObject o, String k, String fallback) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : fallback;
    }

    private static double num(JsonObject o, String k, double fallback) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : fallback;
    }

    private static boolean flag(JsonObject o, String k) {
        return !o.has(k) || o.get(k).getAsBoolean();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static List<String> list(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(k);
            for (JsonElement e : a) if (e.isJsonPrimitive()) out.add(e.getAsString().trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
