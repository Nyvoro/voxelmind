package com.voxelmind.local.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.companion.CompanionManager;
import com.voxelmind.local.config.LocalConfig;
import com.voxelmind.local.mind.Timeline;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** What the companion menu shows: the owner's companions as they are right now, read from the world. */
public final class Snapshots {
    /** Newest timeline rows sent per companion; the stored timeline keeps more. */
    private static final int TIMELINE_ROWS = 60;

    private Snapshots() {}

    public static JsonObject of(CompanionManager manager, ServerPlayer viewer) {
        long now = System.currentTimeMillis();
        LocalConfig config = LocalConfig.get();
        JsonObject root = new JsonObject();
        root.addProperty("host", viewer.level().getServer().isSingleplayerOwner(viewer.nameAndId()));
        root.addProperty("model", config.modelLabel());
        root.addProperty("endpoint", config.baseUrl);
        root.addProperty("connected", config.hasCredentials());
        com.voxelmind.local.mind.LlmClient.Failure failure = com.voxelmind.local.mind.LlmClient.lastFailure();
        if (failure != null && System.currentTimeMillis() - failure.at() < 15 * 60_000) {
            root.addProperty("aiProblem", failure.explained());
            root.addProperty("aiOutOfJoules", failure.player2() && failure.status() == 402);
        }
        root.addProperty("owner", viewer.getGameProfile().name());
        root.addProperty("worldHardcore", viewer.level().getServer().isHardcore());
        root.addProperty("mayHost", manager.mayHost(viewer));
        JsonArray list = new JsonArray();
        for (Companion c : manager.ownedBy(viewer.getUUID())) list.add(companion(c, now));
        root.add("companions", list);
        JsonArray brains = new JsonArray();
        for (com.voxelmind.local.mind.Brain b : com.voxelmind.local.mind.Brains.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", b.id);
            o.addProperty("name", b.name);
            o.addProperty("version", b.version);
            o.addProperty("author", b.author);
            o.addProperty("description", b.description);
            o.addProperty("models", String.join(", ", b.models));
            o.addProperty("builtin", b.builtin);
            brains.add(o);
        }
        root.add("brains", brains);
        return root;
    }

    /** A God or Builder session: no player body, so presence, doing and the last result come from the session. */
    private static void hosted(Companion c, JsonObject o, long now) {
        o.addProperty("present", c.hosted.active());
        o.addProperty("turns", c.hosted.turns());
        String doing = c.hosted.doing();
        if (doing != null) {
            JsonObject a = new JsonObject();
            a.addProperty("call", doing);
            a.addProperty("seconds", c.hosted.sessionSeconds());
            a.addProperty("progress", "");
            o.add("activity", a);
        }
        if (c.hosted instanceof com.voxelmind.local.hosted.BuilderMind b && b.bodyEntity() != null) {
            var e = b.bodyEntity();
            o.addProperty("x", e.blockPosition().getX());
            o.addProperty("y", e.blockPosition().getY());
            o.addProperty("z", e.blockPosition().getZ());
            o.addProperty("dimension", e.level().dimension().identifier().getPath());
        }
        List<Timeline.Event> events = c.state.timeline.last(TIMELINE_ROWS);
        for (int i = events.size() - 1; i >= 0; i--) {
            Timeline.Event e = events.get(i);
            if (!e.kind.equals("result")) continue;
            JsonObject l = new JsonObject();
            l.addProperty("tool", "");
            l.addProperty("outcome", e.outcome == null ? "done" : e.outcome);
            l.addProperty("result", e.text);
            l.addProperty("agoMs", now - e.epochMs);
            o.add("last", l);
            break;
        }
    }

    private static JsonObject companion(Companion c, long now) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.state.id.toString());
        o.addProperty("name", c.name());
        o.addProperty("persona", c.state.persona == null ? "" : c.state.persona);
        o.addProperty("active", c.state.active);
        o.addProperty("kind", c.state.kind == null ? "companion" : c.state.kind);
        o.addProperty("campaign", c.state.campaign);
        o.addProperty("chaos", c.state.chaos);
        o.addProperty("guests", c.state.guests);
        o.addProperty("bodyType", c.state.bodyType);
        o.addProperty("mode", c.state.mode);
        o.addProperty("scope", c.state.scope);
        o.addProperty("fallen", c.state.fallen());
        if (c.state.fallen()) {
            o.addProperty("fallenAgoMs", now - c.state.fallenAt);
            o.addProperty("fallenBy", c.state.fallenBy == null ? "" : c.state.fallenBy);
        }
        o.addProperty("skinSource", c.state.skinSource == null ? "" : c.state.skinSource);
        o.addProperty("brain", c.state.brain == null ? com.voxelmind.local.mind.Brain.DEFAULT : c.state.brain);
        o.addProperty("skinValue", c.state.skinValue == null ? "" : c.state.skinValue);
        o.addProperty("skinSignature", c.state.skinSignature == null ? "" : c.state.skinSignature);
        if (c.hosted != null) hosted(c, o, now);
        CompanionPlayer body = c.body();
        if (c.hosted == null) o.addProperty("present", body != null && !body.isRemoved());
        if (body != null && !body.isRemoved()) {
            o.addProperty("dead", body.isDeadOrDying());
            o.addProperty("hp", body.getHealth());
            o.addProperty("maxHp", body.getMaxHealth());
            o.addProperty("food", body.getFoodData().getFoodLevel());
            o.addProperty("x", body.blockPosition().getX());
            o.addProperty("y", body.blockPosition().getY());
            o.addProperty("z", body.blockPosition().getZ());
            o.addProperty("dimension", body.level().dimension().identifier().getPath());
        }
        Companion.Activity activity = c.activity();
        if (activity != null) {
            JsonObject a = new JsonObject();
            a.addProperty("call", activity.call());
            a.addProperty("seconds", activity.seconds());
            a.addProperty("progress", activity.progress());
            o.add("activity", a);
        }
        Companion.LastAction last = c.lastAction();
        if (last != null) {
            JsonObject l = new JsonObject();
            l.addProperty("tool", last.tool());
            l.addProperty("outcome", last.outcome());
            l.addProperty("result", last.result());
            l.addProperty("agoMs", now - last.at());
            o.add("last", l);
        }
        o.addProperty("previously", c.state.memory.previously == null ? "" : c.state.memory.previously);
        o.addProperty("knowingOwner", c.state.memory.knowingOwner == null ? "" : c.state.memory.knowingOwner);
        o.addProperty("memoryAgoMs", c.state.memory.updatedAt > 0 ? now - c.state.memory.updatedAt : -1);
        JsonArray rows = new JsonArray();
        List<Timeline.Event> events = c.state.timeline.last(TIMELINE_ROWS);
        for (int i = events.size() - 1; i >= 0; i--) {
            Timeline.Event e = events.get(i);
            JsonObject r = new JsonObject();
            r.addProperty("agoMs", now - e.epochMs);
            r.addProperty("kind", e.kind);
            r.addProperty("line", Timeline.line(e));
            r.addProperty("fromOwner", e.fromOwner);
            rows.add(r);
        }
        o.add("timeline", rows);
        return o;
    }
}
