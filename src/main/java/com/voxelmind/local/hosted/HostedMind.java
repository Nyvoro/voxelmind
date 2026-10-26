package com.voxelmind.local.hosted;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.config.LocalConfig;
import com.voxelmind.local.mind.DecisionLog;
import com.voxelmind.local.mind.LlmClient;
import com.voxelmind.local.mind.MemoryWriter;
import com.voxelmind.local.mind.Timeline;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A hosted mind (God or Builder) running inside the mod: one model call per turn that may carry several
 * tool calls; complete results come back as timeline facts and wake the same mind. Code observes,
 * executes the chosen tools and reports what the server answered; it decides nothing.
 */
public abstract class HostedMind {
    private static final Pattern ENTITY_ID = Pattern.compile("^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$");
    private static final Pattern COORD = Pattern.compile("^(?:[~^](?:[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))?|[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))$");
    private static final Pattern UUID_TEXT = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Set<String> COLORS = Set.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");
    private static final int CONVERSATION_ROWS = 30;

    protected final Companion c;
    protected final Scheduler scheduler;
    protected final WorldResets resets = new WorldResets();
    protected @Nullable DecisionLog log;
    private final MemoryWriter memory = new MemoryWriter();
    private final Set<String> reasons = new LinkedHashSet<>();
    private long dueAt;
    private boolean inFlight;
    private long lastTurnAt;
    private long lastActivityAt;
    private long retryAt;
    private int failures;
    private long observeUntil;
    private boolean observedThisTurn;
    private String note = "";
    private int turns;
    private long lastNoticeAt;
    protected long sessionStart;
    private boolean active;

    /** A tool's immediate outcome for the timeline; wake says whether it should wake the mind. */
    protected record Outcome(String text, boolean ok, boolean wake) {}

    protected HostedMind(Companion c) {
        this.c = c;
        this.scheduler = new Scheduler(this::host, c.name());
    }

    public void setLog(DecisionLog log) {
        this.log = log;
    }

    protected abstract String frame();

    protected abstract String facts(String wokeBy, long now);

    protected abstract JsonArray tools();

    /** Carries out one tool call; null when nothing is reported now (the result comes later or not at all). */
    protected abstract @Nullable Outcome tool(String name, JsonObject args);

    protected abstract long minGapMs();

    /** The hosted brain let God and Builder think before acting (reasoning on); "low" was the God's effort. */
    protected String reasoning() {
        return "low";
    }

    protected long idleMs() {
        return 0;
    }

    protected abstract @Nullable String onStart(ServerPlayer owner);

    protected abstract void onStop(String reason);

    protected void tickWorld(MinecraftServer server) {}

    protected abstract String firstRow();

    protected abstract ChatFormatting nameColor();

    public boolean active() {
        return active;
    }

    public MinecraftServer server() {
        return c.manager().server();
    }

    public @Nullable ServerPlayer host() {
        return server().getPlayerList().getPlayer(c.state.owner);
    }

    /** Humans the mind may see and speak to. */
    protected List<ServerPlayer> humans() {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server().getPlayerList().getPlayers()) if (!(p instanceof CompanionPlayer)) out.add(p);
        return out;
    }

    // ---- session ----

    /** Starts the session next to the owner; returns an error text or null. */
    public @Nullable String start(ServerPlayer owner) {
        if (active) return null;
        java.nio.file.Path backup = WorldBackup.ensureToday(server());
        String error = onStart(owner);
        if (error != null) return error;
        active = true;
        sessionStart = System.currentTimeMillis();
        turns = 0;
        failures = 0;
        observeUntil = 0;
        note = "";
        c.event("presence", firstRow(), false, null);
        owner.sendSystemMessage(Component.literal("[VoxelMind] " + c.name() + " is an AI " + (this instanceof GodMind ? "god of this world" : "builder companion")
                + " with console power over this world. /vml dismiss " + c.name() + " ends it."
                + (backup != null ? " A world backup is being written to " + backup.getFileName() + "." : ""))
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        wake("called", 1000);
        return null;
    }

    public void stop(String reason) {
        if (!active) return;
        active = false;
        inFlight = false;
        reasons.clear();
        dueAt = 0;
        scheduler.cancelAll();
        onStop(reason);
        c.event("presence", "your session ended: " + reason, false, null);
    }

    // ---- waking ----

    public void wake(String reason, long delayMs) {
        if (!active) return;
        long now = System.currentTimeMillis();
        reasons.add(reason);
        long due = now + Math.max(0, delayMs);
        dueAt = dueAt == 0 ? due : Math.min(dueAt, due);
        lastActivityAt = now;
    }

    /** Something happened in the world this mind observes; wake says whether it starts a turn. */
    public void worldEvent(String text, boolean wake) {
        if (!active) return;
        c.event("event", text, false, null);
        if (wake) wake("event", 500);
    }

    public void hear(String speakerLine) {
        c.event("chat_in", speakerLine, false, null);
        wake("chat", 200);
    }

    public void tick(MinecraftServer server) {
        if (!active) return;
        scheduler.tick(server);
        tickWorld(server);
        long now = System.currentTimeMillis();
        if (observeUntil > 0 && now >= observeUntil) {
            observeUntil = 0;
            wake("observe", 0);
        }
        if (idleMs() > 0 && observeUntil == 0 && !inFlight && dueAt == 0 && now - lastActivityAt >= idleMs()
                && com.voxelmind.local.mind.Mind.ownerActive(host())) wake("idle", 0);
        if (dueAt > 0 && now >= dueAt && !inFlight && now >= retryAt && now - lastTurnAt >= minGapMs()) launch(server, now);
        memory.tick(c, server, log);
    }

    private void launch(MinecraftServer server, long now) {
        if (!LocalConfig.get().hasCredentials()) {
            if (now - lastNoticeAt > 120_000) {
                lastNoticeAt = now;
                c.notifyOwner(com.voxelmind.local.command.VmlCommand.noAiNotice(c.name()));
            }
            reasons.clear();
            dueAt = 0;
            return;
        }
        if (!LlmClient.tryAcquireBudget()) {
            dueAt = now + 1500;
            return;
        }
        String wokeBy = String.join(", ", reasons);
        reasons.clear();
        dueAt = 0;
        inFlight = true;
        lastTurnAt = now;
        turns++;
        JsonArray messages = messages(facts(wokeBy, now));
        LocalConfig config = LocalConfig.get();
        int turn = turns;
        LlmClient.chat(config.model, messages, tools(), false, 0.8, 8192, null, reasoning())
                .whenComplete((response, error) -> server.execute(() -> onReply(turn, wokeBy, messages, response, error)));
    }

    private JsonArray messages(String facts) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        String language = LocalConfig.get().language;
        system.addProperty("content", frame() + (language == null || language.equals("auto") ? ""
                : "\n\n[LANGUAGE]\nThe owner set " + language + " as the preferred language; the players' current language still wins."));
        messages.add(system);
        List<Timeline.Event> speech = new ArrayList<>();
        for (Timeline.Event e : c.state.timeline.last(200)) if (e.kind.equals("chat_in") || e.kind.equals("said")) speech.add(e);
        for (int i = Math.max(0, speech.size() - CONVERSATION_ROWS); i < speech.size(); i++) {
            Timeline.Event e = speech.get(i);
            JsonObject m = new JsonObject();
            m.addProperty("role", e.kind.equals("said") ? "assistant" : "user");
            m.addProperty("content", e.text);
            messages.add(m);
        }
        // The current facts are always last, so an old request never looks newer than its receipt.
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", facts);
        messages.add(user);
        return messages;
    }

    private void onReply(int turn, String wokeBy, JsonArray messages, LlmClient.Response response, Throwable error) {
        inFlight = false;
        long now = System.currentTimeMillis();
        lastActivityAt = now;
        if (!active) return;
        if (error != null) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            failures = Math.min(6, failures + 1);
            long backoff = Math.min(30_000, 1000L << failures);
            retryAt = now + backoff;
            VoxelMindLocal.LOG.warn("[{}] hosted turn failed: {}", c.name(), cause.toString());
            if (LlmClient.outOfJoules(cause)) {
                if (now - lastNoticeAt > 600_000) {
                    lastNoticeAt = now;
                    c.notifyOwner(com.voxelmind.local.command.VmlCommand.joulesNotice(c.name()));
                }
            } else if (now - lastNoticeAt > 120_000) {
                lastNoticeAt = now;
                c.notifyOwner(c.name() + " could not think: " + LlmClient.explain(cause), true);
            }
            wake("retry", backoff);
            log(turn, wokeBy, messages, null, cause, "error");
            return;
        }
        failures = 0;
        observedThisTurn = false;
        List<String> applied = new ArrayList<>();
        boolean wakeAfter = false;
        String content = response.content() == null ? "" : response.content().trim();
        if (!content.isEmpty()) {
            for (String part : split(content, 400)) speak(part, "chat", null);
            applied.add("reply text");
        }
        for (LlmClient.ToolCall call : response.toolCalls()) {
            Outcome outcome;
            if (call.arguments() == null) {
                outcome = new Outcome("refused " + call.name() + ": its arguments were not valid JSON", false, false);
            } else {
                try {
                    outcome = tool(call.name(), call.arguments());
                } catch (Exception e) {
                    VoxelMindLocal.LOG.warn("[{}] tool {} failed", c.name(), call.name(), e);
                    outcome = new Outcome("FAILED \"" + call.name() + "\": " + e.getMessage(), false, true);
                }
            }
            applied.add(call.name());
            if (outcome != null) {
                c.event("result", outcome.text(), false, outcome.ok() ? "done" : "failed");
                wakeAfter |= outcome.wake();
            }
        }
        if (wakeAfter) wake("results", 500);
        log(turn, wokeBy, messages, response, null, String.join(", ", applied));
    }

    /** A later result of an asynchronous tool (sequence, edit, build, lookup, move). */
    protected void report(String text, boolean ok) {
        if (!active) return;
        c.event("result", text, false, ok ? "done" : "failed");
        wake("results", 500);
    }

    private static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        String rest = text;
        while (rest.length() > max) {
            int cut = rest.lastIndexOf(' ', max);
            if (cut < max / 2) cut = max;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    private void log(int turn, String wokeBy, JsonArray messages, LlmClient.@Nullable Response response, @Nullable Throwable error, String applied) {
        if (log == null) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("lane", c.state.kind);
        entry.addProperty("companion", c.name());
        entry.addProperty("turn", turn);
        entry.addProperty("woke_by", wokeBy);
        entry.addProperty("applied", applied);
        if (LocalConfig.get().logPrompts) entry.addProperty("prompt", messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString());
        if (response != null) {
            JsonArray calls = new JsonArray();
            for (LlmClient.ToolCall call : response.toolCalls()) calls.add(call.name() + " " + call.rawArguments());
            entry.add("tool_calls", calls);
            if (!response.content().isBlank()) entry.addProperty("content", response.content());
            entry.addProperty("latency_ms", response.latencyMs());
            entry.addProperty("prompt_tokens", response.promptTokens());
            entry.addProperty("completion_tokens", response.completionTokens());
            entry.addProperty("cost_usd", response.cost());
            entry.addProperty("cached_tokens", response.cachedTokens());
            if (response.provider() != null) entry.addProperty("provider", response.provider());
        }
        if (error != null) entry.addProperty("error", error.toString());
        log.write(entry);
    }

    // ---- shared tools ----

    /** say: chat line, title or action bar to the humans this mind speaks to. */
    protected Outcome speak(String text, String channel, @Nullable String subtitle) {
        String clean = text.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (clean.isEmpty()) return null;
        if (clean.length() > 400) return new Outcome("refused say: longer than 400 characters", false, true);
        List<ServerPlayer> audience = audience();
        String ch = channel == null ? "chat" : channel;
        for (ServerPlayer p : audience) {
            switch (ch) {
                case "title" -> {
                    String name = p.getGameProfile().name();
                    CommandRunner.runAtHost(p, "VoxelMind", "title " + name + " times 10 70 20");
                    if (subtitle != null && !subtitle.isBlank()) CommandRunner.runAtHost(p, "VoxelMind", "title " + name + " subtitle " + quote(subtitle));
                    CommandRunner.runAtHost(p, "VoxelMind", "title " + name + " title " + quote(clean));
                }
                case "actionbar" -> CommandRunner.runAtHost(p, "VoxelMind", "title " + p.getGameProfile().name() + " actionbar " + quote(clean));
                default -> p.sendSystemMessage(Component.literal("[AI] ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("<" + c.name() + "> ").withStyle(nameColor(), ChatFormatting.BOLD))
                        .append(Component.literal(clean).withStyle(ChatFormatting.YELLOW)));
            }
        }
        c.event("said", clean, false, null);
        com.voxelmind.local.net.Speech.say(c, clean, audience);
        // A successful delivery is recorded without waking; only a failed one wakes.
        return audience.isEmpty() ? new Outcome("say: nobody could hear it", false, true) : null;
    }

    protected List<ServerPlayer> audience() {
        return humans();
    }

    /** Who plain reply text reaches, as the turn block names it. */
    protected String listeners() {
        return "the players";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    protected Outcome run(String command) {
        ServerPlayer host = host();
        if (host == null) return new Outcome("FAILED \"run\": the host is not in the world", false, true);
        String refused = CommandPolicy.refuse(command);
        String shown = Scheduler.truncate(CommandPolicy.normalize(command), 200);
        if (refused != null) return new Outcome("refused run " + shown + ": " + refused, false, false);
        resets.before(server(), command);
        CommandRunner.Result r = CommandRunner.runAtHost(host, c.name(), command);
        return new Outcome((r.ok() ? "result of \"" : "FAILED \"") + shown + "\": " + r.text()
                + (r.noOp() ? " (changed nothing: the blocks already were so)" : ""), r.ok(), true);
    }

    protected Outcome observe(JsonObject args) {
        double seconds = num(args, "seconds", 60);
        seconds = Math.max(60, Math.min(600, seconds));
        observeUntil = System.currentTimeMillis() + (long) (seconds * 1000);
        observedThisTurn = true;
        if (args.has("note")) {
            String n = str(args, "note");
            note = n.length() > 200 ? n.substring(0, 200) : n;
        }
        c.event("note", "you chose to observe for " + Math.round(seconds) + "s", false, null);
        return null;
    }

    protected Outcome note(JsonObject args) {
        String text = str(args, "text");
        if (text.isBlank()) return null;
        c.event("note", "you noted: " + (text.length() > 300 ? text.substring(0, 300) : text), false, null);
        return null;
    }

    /** Live registries of the running game, 30 entries per page. */
    protected Outcome registry(JsonObject args) {
        String kind = str(args, "kind");
        String query = str(args, "query").toLowerCase(Locale.ROOT).replace("minecraft:", "");
        int offset = (int) Math.max(0, num(args, "offset", 0));
        List<String> entries = new ArrayList<>();
        switch (kind) {
            case "blocks" -> {
                for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
                    if (!id.getPath().contains(query)) continue;
                    Block block = BuiltInRegistries.BLOCK.getValue(id);
                    List<String> props = new ArrayList<>();
                    for (Property<?> p : block.defaultBlockState().getProperties()) {
                        List<String> values = new ArrayList<>();
                        for (Object v : p.getPossibleValues()) values.add(String.valueOf(v).toLowerCase(Locale.ROOT));
                        props.add(p.getName() + "=" + String.join("|", values));
                    }
                    entries.add(id + (props.isEmpty() ? "" : " [" + String.join(", ", props) + "]"));
                }
            }
            case "items" -> {
                for (Identifier id : BuiltInRegistries.ITEM.keySet()) if (id.getPath().contains(query)) entries.add(id.toString());
            }
            case "entities" -> {
                for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) if (id.getPath().contains(query)) entries.add(id.toString());
            }
            case "enchantments" -> {
                var lookup = server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
                lookup.listElements().forEach(h -> {
                    Identifier id = h.key().identifier();
                    if (id.getPath().contains(query)) entries.add(id + " (max level " + h.value().getMaxLevel() + ")");
                });
            }
            default -> {
                return new Outcome("refused registry: kind must be blocks, items, entities or enchantments", false, true);
            }
        }
        entries.sort(String::compareTo);
        int total = entries.size();
        List<String> page = entries.subList(Math.min(offset, total), Math.min(total, offset + 30));
        String next = offset + 30 < total ? "; next offset " + (offset + 30) : "";
        return new Outcome("registry " + kind + " \"" + query + "\": " + total + " match(es)" + next + (page.isEmpty() ? "" : "\n" + String.join("\n", page)),
                true, true);
    }

    protected Outcome summonEntity(JsonObject args, boolean tagged) {
        String entity = str(args, "entity").toLowerCase(Locale.ROOT);
        String position = str(args, "position").trim();
        if (!ENTITY_ID.matcher(entity).matches()) return new Outcome("refused summon_entity: \"" + entity + "\" is not an entity id", false, false);
        String[] coords = position.split("\\s+");
        if (coords.length != 3) return new Outcome("refused summon_entity: position needs three coordinates", false, false);
        boolean caret = false;
        boolean other = false;
        for (String coord : coords) {
            if (!COORD.matcher(coord).matches()) return new Outcome("refused summon_entity: \"" + coord + "\" is not a coordinate", false, false);
            if (coord.startsWith("^")) caret = true;
            else other = true;
        }
        if (caret && other) return new Outcome("refused summon_entity: ^ coordinates cannot be mixed with others", false, false);
        String name = component(args);
        if (name == null && args.has("name")) return new Outcome("refused summon_entity: color must be a Minecraft color name or #RRGGBB", false, false);
        String nbt = "{" + (tagged ? "Tags:[\"voxelmind_god\"]" : "") + (name != null ? (tagged ? "," : "") + "CustomName:" + name + ",CustomNameVisible:1b" : "") + "}";
        return run("summon " + entity + " " + position + (nbt.equals("{}") ? "" : " " + nbt));
    }

    protected Outcome nameEntity(JsonObject args) {
        String uuid = str(args, "entity_uuid");
        if (!UUID_TEXT.matcher(uuid).matches()) return new Outcome("refused name_entity: entity_uuid must be a full UUID", false, false);
        String name = component(args);
        if (name == null) return new Outcome("refused name_entity: a name and a valid color are needed", false, false);
        return run("data merge entity " + uuid + " {CustomName:" + name + ",CustomNameVisible:1b}");
    }

    /** {text:"…",color:"…"} as native SNBT (26.x); null when the color is invalid or there is no name. */
    private static @Nullable String component(JsonObject args) {
        if (!args.has("name") || str(args, "name").isBlank()) return null;
        String text = str(args, "name").replace("\\", "\\\\").replace("\"", "\\\"");
        String color = str(args, "color").toLowerCase(Locale.ROOT).trim();
        if (!color.isEmpty() && !COLORS.contains(color) && !color.matches("^#[0-9a-f]{6}$")) return null;
        return "{text:\"" + text + "\"" + (color.isEmpty() ? "" : ",color:\"" + color + "\"") + "}";
    }

    // ---- facts shared by both minds ----

    protected String execution() {
        return "[EXECUTION]\n" + (inFlight ? "A world-tool model request is running." : "No world-tool model request is running.")
                + (retryAt > System.currentTimeMillis() ? " A failed request will be retried in " + ((retryAt - System.currentTimeMillis()) / 1000) + "s." : "")
                + " " + scheduler.running() + " timed command chain(s) running.";
    }

    protected String turn(String wokeBy, long now) {
        long minutes = (now - sessionStart) / 60_000;
        return "[TURN]\nWoken by: " + (wokeBy.isBlank() ? "a quiet moment" : wokeBy) + ". Facts are 0s old; positions and health are observations from now, not live values during generation.\n"
                + "This session: " + minutes + " min in, your turn #" + turns + ".\n"
                + (note.isBlank() ? "" : "Your private note from last time: \"" + note + "\"\n")
                + "Decide now: reply in text, call any tools you want (several are fine), or observe to wait. Plain reply text reaches " + listeners() + " in chat; say can also use title or actionbar.";
    }

    protected String previously() {
        String p = c.state.memory.previously;
        return "[PREVIOUSLY]\n" + (p == null || p.isBlank() ? "Nothing recorded yet." : p.trim());
    }

    protected String about(String label) {
        String k = c.state.memory.knowingOwner;
        return "[" + label + "]\nName: " + c.state.ownerName + (k == null || k.isBlank() ? "" : "\n" + k.trim());
    }

    protected String persona(String fallback) {
        String p = c.state.persona;
        return p == null || p.isBlank() ? fallback : p.trim();
    }

    public int turns() {
        return turns;
    }

    /** What the session is doing right now, for the menu; null when it waits. */
    public @Nullable String doing() {
        if (!active) return null;
        long now = System.currentTimeMillis();
        if (inFlight) return "thinking";
        if (scheduler.running() > 0) return scheduler.running() + " command chain(s) running";
        if (observeUntil > now) return "observing · " + ((observeUntil - now) / 1000) + "s left";
        return null;
    }

    public long sessionSeconds() {
        return active ? (System.currentTimeMillis() - sessionStart) / 1000 : 0;
    }

    // ---- arguments ----

    protected static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    protected static double num(JsonObject o, String key, double fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
