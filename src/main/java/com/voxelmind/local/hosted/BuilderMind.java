package com.voxelmind.local.hosted;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.config.LocalConfig;
import com.voxelmind.local.mind.LlmClient;
import com.voxelmind.local.world.Names;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** The Builder: a fairy at the owner's shoulder that edits terrain with fills, generated builds and undo. */
public final class BuilderMind extends HostedMind {
    private static final Set<String> ANCHORS = Set.of("owner_front", "owner", "owner_look", "fairy", "coords");
    private static final int FILL_CHUNK_DELAY = 2;
    private static final int LAYER_DELAY = 8;
    private static final int CLEARANCE_ABOVE = 2;
    private static final int BUILD_WIDTH = 32;
    private static final int BUILD_DEPTH = 32;
    private static final int BUILD_HEIGHT = 24;

    private final SpiritBody body = new SpiritBody();
    private final EditHistory edits = new EditHistory();
    private int builds;

    public BuilderMind(Companion c) {
        super(c);
        body.type = SpiritBody.TYPES.contains(c.state.bodyType) ? c.state.bodyType : "allay";
    }

    /** The owner chose another creature in the menu; a present body changes at once. */
    public void changeBody(String type) {
        body.type = type;
        ServerPlayer owner = host();
        if (active() && owner != null) body.spawn(owner, c.name());
    }

    public @Nullable Entity bodyEntity() {
        return body.entity();
    }

    public @Nullable UUID bodyUuid() {
        return body.uuid();
    }

    @Override
    protected String frame() {
        return HostedTexts.BUILDER_FRAME;
    }

    @Override
    protected String firstRow() {
        return "You were called to your owner's side just now.";
    }

    @Override
    protected ChatFormatting nameColor() {
        return ChatFormatting.AQUA;
    }

    @Override
    protected long minGapMs() {
        return 2000;
    }

    @Override
    protected String reasoning() {
        return "on";
    }

    @Override
    protected String listeners() {
        return "the owner";
    }

    @Override
    protected List<ServerPlayer> audience() {
        ServerPlayer host = host();
        return host == null ? List.of() : List.of(host);
    }

    @Override
    protected @Nullable String onStart(ServerPlayer owner) {
        String error = body.spawn(owner, c.name());
        if (error != null) c.event("note", "your body could not appear: " + error, false, null);
        return null;
    }

    @Override
    protected void onStop(String reason) {
        body.despawn(server());
    }

    @Override
    protected void tickWorld(MinecraftServer server) {
        body.tick(host());
    }

    // ---- facts ----

    @Override
    protected String facts(String wokeBy, long now) {
        MinecraftServer server = server();
        ServerPlayer owner = host();
        ServerLevel level = owner != null ? Compat.level(owner) : server.overworld();
        List<String> blocks = new ArrayList<>();
        blocks.add("[IDENTITY]\nYour name is " + c.name() + ". " + persona(HostedTexts.BUILDER_PERSONA));
        blocks.add(HostedFacts.world(server, level));
        blocks.add(HostedTexts.commandReference(HostedFacts.version()));
        blocks.add(ownerBlock(owner));
        blocks.add(fairyBlock());
        blocks.add(editsBlock(owner, now));
        blocks.add(HostedFacts.timeline(c.state.timeline.last(200), now));
        blocks.add(previously());
        blocks.add(about("ABOUT OWNER"));
        blocks.add(execution());
        blocks.add(turn(wokeBy, now));
        return String.join("\n\n", blocks);
    }

    private static String ownerBlock(@Nullable ServerPlayer owner) {
        if (owner == null) return "[OWNER]\nNobody is in the world right now.";
        BlockPos look = HostedFacts.lookingAtPos(owner);
        String looking = look == null ? "Not looking at a particular block."
                : "Looking at block (" + look.getX() + ", " + look.getY() + ", " + look.getZ() + ").";
        BlockState feet = owner.level().getBlockState(owner.blockPosition().below());
        return "[OWNER]\n" + HostedFacts.player(owner, true) + "\n  Facing " + BuilderWorld.facing(owner.getYRot()) + " (yaw " + Math.round(owner.getYRot())
                + ", pitch " + Math.round(owner.getXRot()) + "). " + looking + " Standing on " + Names.id(feet.getBlock()) + ".";
    }

    private String fairyBlock() {
        Entity e = body.entity();
        if (e == null) return "[FAIRY]\nYour body is not in the world right now.";
        return "[FAIRY]\nYou are a " + body.type + " at (" + Math.round(e.getX()) + ", " + Math.round(e.getY()) + ", " + Math.round(e.getZ()) + "), mode: " + body.mode + ".";
    }

    private String editsBlock(@Nullable ServerPlayer owner, long now) {
        if (edits.isEmpty()) return "[EDITS]\nNo server-confirmed undo entries recorded this session.";
        StringBuilder out = new StringBuilder("[EDITS] (undo stack, most recent last)");
        for (EditHistory.Entry e : edits.entries()) {
            BlockPos centre = new BlockPos((e.min.getX() + e.max.getX()) / 2, e.min.getY(), (e.min.getZ() + e.max.getZ()) / 2);
            String where = owner == null || owner.level() != e.level ? "" : ", " + HostedFacts.direction(owner.blockPosition(), centre) + " of you";
            out.append("\n- \"").append(e.label).append("\" (").append(e.max.getX() - e.min.getX() + 1).append('x').append(e.max.getY() - e.min.getY() + 1)
                    .append('x').append(e.max.getZ() - e.min.getZ() + 1).append(", bounds (").append(e.min.getX()).append(", ").append(e.min.getY()).append(", ")
                    .append(e.min.getZ()).append(") to (").append(e.max.getX()).append(", ").append(e.max.getY()).append(", ").append(e.max.getZ()).append(')')
                    .append(where).append(", ").append(e.changed >= 0 ? e.changed + " blocks changed" : "still running")
                    .append(e.errors ? ", server reported command errors" : "").append(", ").append(Math.max(0, (now - e.at) / 1000))
                    .append("s ago) — undo target \"").append(e.id).append('"');
        }
        return out.toString();
    }

    // ---- tools ----

    @Override
    protected JsonArray tools() {
        return HostedTools.builder();
    }

    @Override
    protected @Nullable Outcome tool(String name, JsonObject args) {
        return switch (name) {
            case "registry" -> registry(args);
            case "summon_entity" -> summonEntity(args, false);
            case "name_entity" -> nameEntity(args);
            case "say" -> speak(str(args, "text"), str(args, "channel"), str(args, "subtitle"));
            case "run" -> run(str(args, "command"));
            case "fill" -> fill(args);
            case "build" -> build(args);
            case "undo" -> undo(str(args, "target"));
            case "find_blocks" -> findBlocks(args);
            case "body" -> body(args);
            case "lookup" -> lookup(str(args, "query"));
            case "observe" -> observe(args);
            case "note" -> note(args);
            default -> new Outcome("refused " + name + ": no such tool", false, false);
        };
    }

    private static int axis(JsonObject args, String key, int max) {
        return (int) Math.max(1, Math.min(max, Math.round(num(args, key, 1))));
    }

    private Outcome fill(JsonObject args) {
        ServerPlayer owner = host();
        if (owner == null) return new Outcome("FAILED \"fill\": the owner is not in the world", false, true);
        String anchor = str(args, "anchor").isBlank() ? "owner_front" : str(args, "anchor");
        if (!ANCHORS.contains(anchor)) anchor = "owner_front";
        String block = BuilderWorld.cleanBlock(str(args, "block"));
        if (block.isEmpty()) return new Outcome("refused fill: block is required", false, false);
        String why = BuilderWorld.refuseBlock(block);
        if (why != null) return new Outcome("refused fill: " + why, false, false);
        int width = axis(args, "width", 128);
        int depth = axis(args, "depth", 128);
        int height = axis(args, "height", 128);
        int distance = (int) Math.max(0, Math.min(32, Math.round(num(args, "distance", 0))));
        int yOffset = (int) Math.max(-64, Math.min(64, Math.round(num(args, "y_offset", 0))));
        ServerLevel level = Compat.level(owner);
        BlockPos ref = owner.blockPosition();
        BuilderWorld.Box box;
        switch (anchor) {
            case "owner_front" -> box = BuilderWorld.ownerFront(ref, BuilderWorld.facing(owner.getYRot()), width, depth, height, distance, yOffset);
            case "owner_look" -> {
                BlockPos look = HostedFacts.lookingAtPos(owner);
                box = BuilderWorld.centred(look != null ? look : ref, width, depth, height, yOffset);
            }
            case "fairy" -> {
                Entity e = body.entity();
                if (e != null) {
                    ref = e.blockPosition();
                    level = (ServerLevel) e.level();
                }
                box = BuilderWorld.centred(ref, width, depth, height, yOffset);
            }
            case "coords" -> box = BuilderWorld.centred(new BlockPos((int) Math.round(num(args, "x", ref.getX())),
                    (int) Math.round(num(args, "y", ref.getY())), (int) Math.round(num(args, "z", ref.getZ()))), width, depth, height, yOffset);
            default -> box = BuilderWorld.centred(ref, width, depth, height, yOffset);
        }
        if (box.volume() > BuilderWorld.MAX_FILL_VOLUME) {
            return new Outcome("refused fill: the fill is too big (" + box.volume() + " blocks, max " + BuilderWorld.MAX_FILL_VOLUME + ")", false, false);
        }
        List<Scheduler.Step> steps = new ArrayList<>();
        for (String command : BuilderWorld.chunkedFill(box, block)) steps.add(new Scheduler.Step(steps.isEmpty() ? 0 : FILL_CHUNK_DELAY, command));
        return edit("fill " + anchor + " with " + block, level, box.min(), box.max(), steps);
    }

    /** Snapshots the region, runs the commands on the server clock and reports the changed-block count. */
    private @Nullable Outcome edit(String label, ServerLevel level, BlockPos a, BlockPos b, List<Scheduler.Step> steps) {
        EditHistory.Entry entry = edits.snapshot(label, level, a, b);
        String id = entry != null ? entry.id : "edit" + (++builds);
        Scheduler.Run run = scheduler.start(id, label, steps, level, done -> {
            boolean ok = done.results().stream().allMatch(Scheduler.StepResult::ok) && !done.cancelled();
            int changed = entry == null ? -1 : EditHistory.countChanged(entry);
            // The blocks the edit set are placed by the Builder, the cells it cleared hold no placed block any more.
            if (entry != null) EditHistory.recordPlaced(entry, c.state.id, c.name());
            if (entry != null) {
                entry.changed = changed;
                entry.errors = !ok;
            }
            StringBuilder failures = new StringBuilder();
            int shown = 0;
            for (Scheduler.StepResult r : done.results()) {
                if (r.ok() || shown >= 6) continue;
                shown++;
                failures.append("\n  step ").append(r.index()).append(": /").append(Scheduler.truncate(r.command(), 160)).append(" → FAILED: ")
                        .append(Scheduler.truncate(r.text(), 200));
            }
            report((ok ? "result of " : "FAILED ") + Scheduler.summary(done) + (changed >= 0 ? "; " + changed + " blocks changed" : "")
                    + (entry != null ? " — undo target \"" + entry.id + "\"" : "; too large to undo") + failures, ok);
        });
        if (run == null) {
            if (entry != null) edits.drop(entry);
            return new Outcome("refused " + label + ": " + Scheduler.MAX_RUNNING + " command chains are already running", false, false);
        }
        c.event("note", "you did: " + label + " (" + steps.size() + " commands; pending server feedback)", false, null);
        return null;
    }

    private Outcome build(JsonObject args) {
        String what = str(args, "what").trim();
        if (what.isEmpty()) return new Outcome("refused build: what is required", false, false);
        if (what.length() > 300) what = what.substring(0, 300);
        ServerPlayer owner = host();
        if (owner == null) return new Outcome("FAILED \"build\": the owner is not in the world", false, true);
        if (!LlmClient.tryAcquireBudget()) return new Outcome("FAILED \"build\": the per-minute model request budget is used up; try again shortly", false, true);
        int width = axis(args, "width", BUILD_WIDTH);
        int depth = axis(args, "depth", BUILD_DEPTH);
        int height = axis(args, "height", BUILD_HEIGHT);
        if (!args.has("width")) width = BUILD_WIDTH;
        if (!args.has("depth")) depth = BUILD_DEPTH;
        if (!args.has("height")) height = BUILD_HEIGHT;
        int distance = (int) Math.max(1, Math.min(16, Math.round(num(args, "distance", 3))));
        // Captured before the geometry call: moving afterwards does not move this build.
        BlockPos at = owner.blockPosition();
        String facing = BuilderWorld.facing(owner.getYRot());
        ServerLevel level = Compat.level(owner);
        String label = "build \"" + (what.length() > 40 ? what.substring(0, 40) : what) + "\"";
        c.event("note", "you started: " + label + " (a separate model is writing the geometry)", false, null);
        geometry(what, width, depth, height, null, 1, label, at, facing, level, distance);
        return null;
    }

    private void geometry(String what, int w, int d, int h, @Nullable String retry, int attempt, String label, BlockPos at, String facing,
                          ServerLevel level, int distance) {
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", HostedTexts.geometryPrompt(what, w, d, h)
                + (retry == null ? "" : "\n\nYour previous answer could not be used: " + retry + ". Write the list again."));
        messages.add(user);
        LlmClient.chat(LocalConfig.get().model, messages, null, false, 0.4, 8192, null).whenComplete((response, error) -> server().execute(() -> {
            if (!active()) return;
            String reason;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                reason = "the geometry request failed: " + LlmClient.explain(cause);
                report("FAILED " + label + ": " + reason, false);
                return;
            }
            BuilderWorld.Geometry g = BuilderWorld.parse(response.content() == null ? "" : response.content());
            if (g.ops().isEmpty()) {
                reason = response.content() == null || response.content().isBlank() ? "the reply was empty"
                        : "no valid fill/setblock command was found (" + g.rejected().size() + " line(s) could not be used"
                        + (g.rejected().isEmpty() ? "" : ", e.g. " + Scheduler.truncate(g.rejected().getFirst(), 120)) + ")";
                if (attempt < 2) {
                    geometry(what, w, d, h, reason, attempt + 1, label, at, facing, level, distance);
                    return;
                }
                report("FAILED " + label + ": " + reason, false);
                return;
            }
            BuilderWorld.Placement placement = BuilderWorld.place(g.ops(), at, facing, distance);
            List<Scheduler.Step> steps = new ArrayList<>();
            for (int layer = 0; layer < placement.layers().size(); layer++) {
                List<String> commands = placement.layers().get(layer);
                for (int i = 0; i < commands.size(); i++) steps.add(new Scheduler.Step(i == 0 && layer > 0 ? LAYER_DELAY : 0, commands.get(i)));
            }
            BuilderWorld.Box box = placement.target();
            String note = g.rejected().isEmpty() ? "" : " (" + g.rejected().size() + " unusable line(s) skipped)";
            Outcome refused = edit(label + note, level, box.min().below(), box.max().above(CLEARANCE_ABOVE), steps);
            if (refused != null) report(refused.text(), false);
        }));
    }

    private Outcome undo(String target) {
        if (target.isBlank()) return new Outcome("refused undo: target is required", false, false);
        EditHistory.Entry e = edits.find(target);
        if (e == null) return new Outcome("FAILED \"undo " + target + "\": no such entry on the undo stack", false, true);
        if (e.changed < 0) return new Outcome("refused undo " + e.id + ": that edit is still running", false, false);
        int restored = edits.undo(e);
        return new Outcome("result of \"undo " + e.id + "\" (" + e.label + "): " + restored + " blocks restored", true, true);
    }

    private Outcome findBlocks(JsonObject args) {
        ServerPlayer owner = host();
        if (owner == null) return new Outcome("FAILED \"find_blocks\": the owner is not in the world", false, true);
        String block = str(args, "block").trim();
        if (block.isEmpty()) return new Outcome("refused find_blocks: block is required", false, false);
        Predicate<BlockState> match = BuilderWorld.matcher(block);
        if (match == null) return new Outcome("refused find_blocks: \"" + block + "\" is not a block id or tag", false, false);
        int radius = (int) Math.max(1, Math.min(48, Math.round(num(args, "radius", 48))));
        int max = (int) Math.max(1, Math.min(12, Math.round(num(args, "max", 12))));
        BlockPos from = owner.blockPosition();
        List<BuilderWorld.Hit> hits = BuilderWorld.scan(Compat.level(owner), from, radius, match);
        if (hits.isEmpty()) return new Outcome("result of \"find_blocks " + block + "\" (r" + radius + "): none found in loaded chunks", true, true);
        StringBuilder out = new StringBuilder("result of \"find_blocks " + block + "\" (r" + radius + "): " + hits.size() + " found, nearest " + Math.min(max, hits.size()) + ":");
        for (BuilderWorld.Hit hit : hits.subList(0, Math.min(max, hits.size()))) {
            BlockPos p = hit.pos();
            out.append("\n  ").append(Names.id(owner.level().getBlockState(p).getBlock())).append(" at (").append(p.getX()).append(", ").append(p.getY())
                    .append(", ").append(p.getZ()).append("), ").append(String.format(Locale.ROOT, "%.0f", hit.distance())).append("m ")
                    .append(BuilderWorld.compass(p.getX() - from.getX(), p.getZ() - from.getZ()))
                    .append(p.getY() == from.getY() ? "" : p.getY() > from.getY() ? ", " + (p.getY() - from.getY()) + " up" : ", " + (from.getY() - p.getY()) + " down");
        }
        return new Outcome(out.toString(), true, true);
    }

    private Outcome body(JsonObject args) {
        ServerPlayer owner = host();
        String action = str(args, "action");
        switch (action) {
            case "follow", "stay" -> {
                body.mode = action;
                return new Outcome("result of \"body " + action + "\": done", true, false);
            }
            case "come" -> {
                if (owner == null) return new Outcome("FAILED \"body come\": the owner is not in the world", false, true);
                if (!body.present()) {
                    String error = body.spawn(owner, c.name());
                    if (error != null) return new Outcome("FAILED \"body come\": " + error, false, true);
                } else body.come(owner);
                return new Outcome("result of \"body come\": you are next to the owner", true, false);
            }
            case "type" -> {
                String type = str(args, "type");
                if (!SpiritBody.TYPES.contains(type)) return new Outcome("refused body type: type must be one of allay, bee, vex, parrot, bat", false, false);
                if (owner == null) return new Outcome("FAILED \"body type\": the owner is not in the world", false, true);
                body.type = type;
                c.state.bodyType = type;
                c.manager().save();
                String error = body.spawn(owner, c.name());
                return error == null ? new Outcome("result of \"body type " + type + "\": you now appear as a " + type, true, false)
                        : new Outcome("FAILED \"body type " + type + "\": " + error, false, true);
            }
            default -> {
                return new Outcome("refused body: action must be follow, stay, come or type", false, false);
            }
        }
    }

    private @Nullable Outcome lookup(String query) {
        if (query.isBlank()) return new Outcome("refused lookup: query is required", false, false);
        String q = query.length() > 200 ? query.substring(0, 200) : query;
        Wiki.lookup(q).whenComplete((text, error) -> server().execute(() -> {
            if (error != null) report("wiki lookup for \"" + q + "\" failed: " + (error.getCause() != null ? error.getCause() : error).getMessage(), false);
            else report(text, true);
        }));
        return null;
    }
}
