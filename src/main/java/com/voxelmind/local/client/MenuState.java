package com.voxelmind.local.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.net.MenuPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The last snapshot the server sent about the player's companions, refreshed while a menu screen is open. */
public final class MenuState {
    private static final long REFRESH_MS = 2000;

    public record Activity(String call, long seconds, String progress) {}

    public record Last(String tool, String outcome, String result, long agoMs) {}

    public record Row(long agoMs, String kind, String line, boolean fromOwner) {}

    public record Mate(UUID id, String name, String persona, boolean active, String mode, String scope, boolean fallen, long fallenAgoMs,
                       String fallenBy, boolean present, boolean dead,
                       float hp, float maxHp, int food, int x, int y, int z, String dimension,
                       @Nullable Activity activity, @Nullable Last last,
                       String skinSource, String skinValue, String skinSignature,
                       String previously, String knowingOwner, long memoryAgoMs, List<Row> timeline,
                       String kind, String campaign, int chaos, boolean guests, String bodyType, int turns) {
        public boolean hardcore() {
            return !hosted() && "hardcore".equals(mode);
        }

        /** A God or Builder: no player body, console power over the world. */
        public boolean hosted() {
            return "builder".equals(kind) || "god".equals(kind);
        }

        public boolean global() {
            return "global".equals(scope);
        }
    }

    public record View(boolean host, String model, String endpoint, boolean connected, String owner, boolean worldHardcore,
                       String aiProblem, boolean aiOutOfJoules, List<Mate> mates, long receivedAt, boolean mayHost, boolean aiOutOfSparks) {
        public @Nullable Mate find(UUID id) {
            for (Mate m : mates) if (m.id().equals(id)) return m;
            return null;
        }
    }

    private static volatile @Nullable View view;
    private static long requestedAt;
    private static long firstRequestAt;

    private MenuState() {}

    public static @Nullable View view() {
        return view;
    }

    /** True when the server runs this mod and can answer; false on servers without it. */
    public static boolean serverHasMod() {
        return ClientPlayNetworking.canSend(MenuPayloads.RequestSnapshot.TYPE);
    }

    /** Asked at least a few seconds ago and still no answer. */
    public static boolean noAnswer() {
        return view == null && firstRequestAt > 0 && System.currentTimeMillis() - firstRequestAt > 4000;
    }

    public static void request() {
        if (!serverHasMod()) return;
        long now = System.currentTimeMillis();
        requestedAt = now;
        if (firstRequestAt == 0) firstRequestAt = now;
        ClientPlayNetworking.send(MenuPayloads.RequestSnapshot.INSTANCE);
    }

    /** Keeps the snapshot fresh while one of the menu screens is showing. */
    static void tick(Minecraft client) {
        if (client.gui.screen() instanceof PanelScreen && System.currentTimeMillis() - requestedAt > REFRESH_MS) request();
    }

    static void clear() {
        view = null;
        requestedAt = 0;
        firstRequestAt = 0;
    }

    static void receive(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            List<Mate> mates = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("companions")) mates.add(mate(e.getAsJsonObject()));
            view = new View(bool(root, "host"), str(root, "model"), str(root, "endpoint"), bool(root, "connected"),
                    str(root, "owner"), bool(root, "worldHardcore"), str(root, "aiProblem"), bool(root, "aiOutOfJoules"),
                    List.copyOf(mates), System.currentTimeMillis(), bool(root, "mayHost"), bool(root, "aiOutOfSparks"));
        } catch (Exception e) {
            VoxelMindLocal.LOG.warn("Could not read the companion snapshot", e);
        }
    }

    private static Mate mate(JsonObject o) {
        Activity activity = null;
        if (o.has("activity")) {
            JsonObject a = o.getAsJsonObject("activity");
            activity = new Activity(str(a, "call"), num(a, "seconds"), str(a, "progress"));
        }
        Last last = null;
        if (o.has("last")) {
            JsonObject l = o.getAsJsonObject("last");
            last = new Last(str(l, "tool"), str(l, "outcome"), str(l, "result"), num(l, "agoMs"));
        }
        List<Row> rows = new ArrayList<>();
        JsonArray timeline = o.has("timeline") ? o.getAsJsonArray("timeline") : new JsonArray();
        for (JsonElement e : timeline) {
            JsonObject r = e.getAsJsonObject();
            rows.add(new Row(num(r, "agoMs"), str(r, "kind"), str(r, "line"), bool(r, "fromOwner")));
        }
        return new Mate(UUID.fromString(str(o, "id")), str(o, "name"), str(o, "persona"), bool(o, "active"),
                str(o, "mode").isEmpty() ? "normal" : str(o, "mode"), str(o, "scope").isEmpty() ? "world" : str(o, "scope"), bool(o, "fallen"), num(o, "fallenAgoMs"), str(o, "fallenBy"),
                bool(o, "present"), bool(o, "dead"), (float) dbl(o, "hp"), (float) dbl(o, "maxHp"), (int) num(o, "food"),
                (int) num(o, "x"), (int) num(o, "y"), (int) num(o, "z"), str(o, "dimension"), activity, last,
                str(o, "skinSource"), str(o, "skinValue"), str(o, "skinSignature"),
                str(o, "previously"), str(o, "knowingOwner"), o.has("memoryAgoMs") ? num(o, "memoryAgoMs") : -1, List.copyOf(rows),
                str(o, "kind").isEmpty() ? "companion" : str(o, "kind"), str(o, "campaign").isEmpty() ? "sandbox" : str(o, "campaign"),
                o.has("chaos") ? (int) num(o, "chaos") : 3, !o.has("guests") || bool(o, "guests"),
                str(o, "bodyType").isEmpty() ? "allay" : str(o, "bodyType"), (int) num(o, "turns"));
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).getAsBoolean();
    }

    private static long num(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsLong() : 0;
    }

    private static double dbl(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsDouble() : 0;
    }
}
