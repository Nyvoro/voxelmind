package com.voxelmind.local.companion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.body.CompanionConnection;
import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.PathFinder;
import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.hosted.BuilderMind;
import com.voxelmind.local.hosted.GodMind;
import com.voxelmind.local.hosted.GodRules;
import com.voxelmind.local.hosted.HostedMind;
import net.minecraft.server.permissions.Permissions;
import com.voxelmind.local.mind.DecisionLog;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** All companions of one server: spawning, dismissing, saving, hearing chat. */
public final class CompanionManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static @Nullable CompanionManager instance;

    private final MinecraftServer server;
    private final Path dir;
    /** Companions that go with their owner into every world of this game directory. */
    private final Path globalDir;
    private final Map<UUID, Companion> companions = new LinkedHashMap<>();
    private boolean dirty;
    private long lastSave;

    private CompanionManager(MinecraftServer server) {
        this.server = server;
        this.dir = server.getWorldPath(LevelResource.ROOT).resolve("voxelmind-local");
        this.globalDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("voxelmind-local").resolve("companions");
    }

    public String worldName() {
        return server.getWorldData().getLevelName();
    }

    private Path dirFor(CompanionState state) {
        return state.global() ? globalDir : dir;
    }

    public MinecraftServer server() {
        return server;
    }

    public static @Nullable CompanionManager get() {
        return instance;
    }

    public static CompanionManager start(MinecraftServer server) {
        instance = new CompanionManager(server);
        instance.load();
        return instance;
    }

    public static void stop() {
        // The day's minutes per brain for the usage count are written now and then; the stop writes the rest.
        com.voxelmind.local.config.LocalConfig.save();
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }

    public Collection<Companion> all() {
        return companions.values();
    }

    public List<Companion> ownedBy(UUID owner) {
        List<Companion> out = new ArrayList<>();
        for (Companion c : companions.values()) if (c.state.owner.equals(owner)) out.add(c);
        return out;
    }

    public @Nullable Companion byBody(CompanionPlayer body) {
        return companions.get(body.getUUID());
    }

    public @Nullable Companion find(UUID owner, String name) {
        for (Companion c : ownedBy(owner)) if (c.state.name.equalsIgnoreCase(name)) return c;
        return null;
    }

    public void markDirty() {
        dirty = true;
    }

    // ---- persistence ----

    private void load() {
        load(dir, "world");
        load(globalDir, "global");
        VoxelMindLocal.LOG.info("Loaded {} companion(s) from {} and {}", companions.size(), dir, globalDir);
    }

    /** Where a file lies decides its scope; a world companion wins over a global one with the same id. */
    private void load(Path from, String scope) {
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> files = Files.list(from)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    CompanionState state = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), CompanionState.class);
                    if (state == null || state.id == null || state.owner == null) continue;
                    if (companions.containsKey(state.id)) {
                        VoxelMindLocal.LOG.warn("Companion {} exists in this world and for all worlds; using the one of this world", state.name);
                        continue;
                    }
                    if (state.timeline == null) state.timeline = new com.voxelmind.local.mind.Timeline();
                    if (state.memory == null) state.memory = new CompanionState.Memory();
                    state.scope = scope;
                    register(state);
                } catch (Exception e) {
                    VoxelMindLocal.LOG.error("Could not read companion file {}", file, e);
                }
            }
        } catch (IOException e) {
            VoxelMindLocal.LOG.error("Could not list {}", from, e);
        }
    }

    private Companion register(CompanionState state) {
        Companion c = new Companion(this, state);
        DecisionLog log = new DecisionLog(dir.resolve("logs"), state.name);
        c.mind.setLog(log);
        if (c.hosted != null) c.hosted.setLog(log);
        companions.put(state.id, c);
        return c;
    }

    public void save() {
        try {
            for (Companion c : companions.values()) {
                com.voxelmind.local.config.LocalConfig.writeAtomically(dirFor(c.state).resolve(c.state.id + ".json"), GSON.toJson(c.state));
            }
            dirty = false;
            lastSave = System.currentTimeMillis();
        } catch (IOException e) {
            VoxelMindLocal.LOG.error("Could not save companions to {}", dir, e);
        }
        com.voxelmind.local.world.Placed.save(server);
    }

    // ---- lifecycle ----

    public void tick() {
        boolean present = false;
        for (Companion c : companions.values()) present |= c.body() != null || c.hosted != null && c.hosted.active();
        com.voxelmind.local.provider.Player2.heartbeat(present);
        for (Companion c : List.copyOf(companions.values())) {
            try {
                c.tick(server);
            } catch (Exception e) {
                VoxelMindLocal.LOG.error("Companion {} tick failed", c.name(), e);
            }
        }
        if (dirty && System.currentTimeMillis() - lastSave > 30_000) save();
    }

    private void shutdown() {
        for (Companion c : companions.values()) {
            if (c.hosted != null) c.hosted.stop("the world closed");
            CompanionPlayer body = c.body();
            if (body != null) {
                c.detach();
                server.getPlayerList().remove(body);
            }
        }
        save();
    }

    /** Creates a new companion or calls an existing one back to its owner. */
    public String summon(ServerPlayer owner, String name, @Nullable String persona) {
        return summon(owner, name, persona, null);
    }

    /** Whether this player may bring a God or Builder: they get console power over the world. */
    public boolean mayHost(ServerPlayer player) {
        return server.isSingleplayerOwner(new NameAndId(player.getUUID(), player.getGameProfile().name()))
                || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** Who may change the AI settings, as for /vml setup: the singleplayer host or an owner-level operator. */
    public boolean mayConfigure(ServerPlayer player) {
        return server.isSingleplayerOwner(new NameAndId(player.getUUID(), player.getGameProfile().name()))
                || player.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    /** kind: companion, builder or god; null keeps an existing companion's kind (new ones become companions). */
    public String summon(ServerPlayer owner, String name, @Nullable String persona, @Nullable String kind) {
        if (!VALID_NAME.matcher(name).matches()) return "Names must be 3-16 letters, digits or underscores.";
        Companion c = find(owner.getUUID(), name);
        if (c != null && kind != null && !kind.equals(c.state.kind)) {
            return "You already have " + c.name() + " as a " + c.state.kind + ". Pick another name or delete it first with /vml forget " + c.name() + ".";
        }
        String wanted = c != null ? c.state.kind : kind == null ? "companion" : kind;
        if (!wanted.equals("companion") && !mayHost(owner)) {
            return "A " + wanted + " gets console power over the world, so only the host of a singleplayer world or an operator can bring one.";
        }
        if (c != null && c.state.fallen()) {
            return c.name() + " fell in hardcore mode and cannot come back. Its memories stay in the menu; /vml forget "
                    + c.name() + " deletes it.";
        }
        if (wanted.equals("companion") && !mayHost(owner) && (c == null || c.body() == null || c.body().isRemoved())) {
            int max = com.voxelmind.local.config.LocalConfig.get().maxCompanionsPerPlayer;
            long here = ownedBy(owner.getUUID()).stream().filter(o -> o.body() != null && !o.body().isRemoved()).count();
            if (max > 0 && here >= max) {
                return "On this server each player can have " + max + " companions in the world at once. Send one away first with /vml dismiss <name>.";
            }
        }
        if (c == null) {
            ServerPlayer clash = server.getPlayerList().getPlayerByName(name);
            if (clash != null) return "A player named " + name + " is already online.";
            for (Companion other : companions.values()) {
                if (other.state.name.equalsIgnoreCase(name)) return "Another player already has a companion named " + name + ".";
            }
            CompanionState state = new CompanionState();
            state.name = name;
            state.id = CompanionState.idFor(owner.getUUID(), name);
            state.owner = owner.getUUID();
            state.ownerName = owner.getGameProfile().name();
            state.persona = persona == null ? "" : persona;
            state.kind = wanted;
            state.mode = server.isHardcore() ? "hardcore" : "normal";
            state.createdAt = System.currentTimeMillis();
            c = register(state);
            if (c.hosted == null) c.event("presence", "you came into this world for the first time, summoned by your owner " + state.ownerName, false, null);
        } else if (persona != null && !persona.isBlank()) {
            c.state.persona = persona;
        }
        c.state.active = true;
        c.state.ownerName = owner.getGameProfile().name();
        markDirty();
        if (c.hosted != null) {
            if (c.hosted.active()) {
                c.event("presence", "your owner called you again", false, null);
                c.hosted.wake("called by your owner", 0);
                return name + " is here.";
            }
            String error = c.hosted.start(owner);
            save();
            return error;
        }
        if (c.body() != null && !c.body().isRemoved()) {
            CompanionPlayer body = c.body();
            BlockPos spot = spotNear(owner);
            if (spot != null) {
                Compat.teleport(body, Compat.level(owner), spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, body.getYRot());
                c.event("presence", "your owner called you; you appeared next to them at (" + spot.getX() + "," + spot.getY() + "," + spot.getZ() + ")", false, null);
                c.mind.wake("summoned by your owner", 300);
                return name + " is here.";
            }
            return "There is no free spot next to you for " + name + ".";
        }
        String error = spawnBody(c, owner);
        save();
        return error != null ? error : null;
    }

    /** Puts the companion's body into the owner's world next to them. Returns an error text or null. */
    private @Nullable String spawnBody(Companion c, ServerPlayer owner) {
        ServerLevel level = Compat.level(owner);
        BlockPos spot = spotNear(owner);
        if (spot == null) return "There is no free spot next to you for " + c.name() + ".";
        GameProfile profile = Skins.profile(c.state);
        CompanionPlayer body = new CompanionPlayer(server, level, profile);
        boolean hasData = false;
        try {
            hasData = Compat.loadPlayerData(server, body, c.state.id, c.state.name);
        } catch (Exception e) {
            VoxelMindLocal.LOG.error("Could not load player data of {}", c.name(), e);
        }
        body.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, owner.getYRot() + 180f, 0f);
        server.getPlayerList().placeNewPlayer(new CompanionConnection(body), body, CommonListenerCookie.createInitial(profile, false));
        Compat.acceptPlayerLoad(body);
        // Before 1.21.9 placeNewPlayer puts a player where its saved data says (or at the world spawn); the body
        // belongs next to its owner, in the owner's dimension.
        if (Compat.level(body) != level || body.blockPosition().distSqr(spot) > 2) {
            Compat.teleport(body, level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, owner.getYRot() + 180f);
        }
        if (!hasData) body.setGameMode(GameType.SURVIVAL);
        if (body.isDeadOrDying()) body.setHealth(body.getMaxHealth());
        c.attach(body);
        labelAsAi(body);
        String world = worldName();
        if (c.state.global() && !c.state.lastWorld.isBlank() && !c.state.lastWorld.equals(world)) {
            c.event("presence", "you came into the world \"" + world + "\"; last time you were in the world \"" + c.state.lastWorld
                    + "\"; here you carry what you had in this world" + (hasData ? "" : " (nothing yet: you are new here)"), false, null);
        }
        c.state.lastWorld = world;
        c.event("presence", "you entered the world next to your owner at (" + spot.getX() + "," + spot.getY() + "," + spot.getZ() + ")", false, null);
        c.mind.wake("you just arrived in the world", 1500);
        return null;
    }

    /** "[AI]" before the body's name in the name tag, tab list and chat, for everyone on the server (Art. 50 AI Act). */
    private void labelAsAi(CompanionPlayer body) {
        var scoreboard = server.getScoreboard();
        var team = scoreboard.getPlayerTeam("voxelmind_ai");
        if (team == null) {
            team = scoreboard.addPlayerTeam("voxelmind_ai");
            team.setPlayerPrefix(Component.literal("[AI] ").withStyle(ChatFormatting.GRAY));
        }
        scoreboard.addPlayerToTeam(body.getGameProfile().name(), team);
    }

    private final java.util.Set<UUID> toldAboutAi = new java.util.HashSet<>();

    /** Once per player and session: which of the beings here are AI and who brought them. */
    private void tellAboutAi(ServerPlayer player) {
        var beings = Component.empty();
        int count = 0;
        for (Companion c : companions.values()) {
            if (!c.present()) continue;
            String kind = c.state.kind == null ? "companion" : c.state.kind;
            if (count++ > 0) beings.append(", ");
            beings.append(Component.translatableWithFallback("voxelmind_local.notice.ai_being", "%s (%s of %s)", c.name(),
                    Component.translatableWithFallback("voxelmind_local.notice.ai_kind." + kind, kind), c.state.ownerName));
        }
        if (count == 0 || !toldAboutAi.add(player.getUUID())) return;
        player.sendSystemMessage(Component.literal("[VoxelMind] ").append(Component.translatableWithFallback("voxelmind_local.notice.ai_label",
                "AI characters in this world, controlled by an AI model: %s.", beings)).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    /** A hardcore companion that died: its body leaves the world for good; state and memories stay until deleted. */
    void fall(Companion c) {
        CompanionPlayer body = c.body();
        c.state.active = false;
        c.notifyOwner(c.name() + " fell in hardcore mode and will not come back. Its memories stay in the menu.");
        if (body != null) {
            c.detach();
            body.disconnect();
            server.getPlayerList().remove(body);
        }
        save();
    }

    /** Moves a companion between this world and all worlds; its file moves with it. Returns an error text or null. */
    public @Nullable String setScope(Companion c, String scope) {
        if (!scope.equals("world") && !scope.equals("global")) return "Use this or all.";
        if (scope.equals(c.state.scope)) return null;
        Path old = dirFor(c.state).resolve(c.state.id + ".json");
        c.state.scope = scope;
        if (scope.equals("world")) c.state.lastWorld = worldName();
        c.event("note", scope.equals("global")
                ? "your owner made you a companion for all of their worlds: you go with them into every world; your memories and character travel along, while each world keeps its own inventory and position for you"
                : "your owner made you a companion of this world only (\"" + worldName() + "\"); you no longer go with them into other worlds", false, null);
        save();
        try {
            Files.deleteIfExists(old);
        } catch (IOException e) {
            VoxelMindLocal.LOG.warn("Could not remove the old file of {}", c.name(), e);
        }
        return null;
    }

    /** Sets normal or hardcore while the companion lives; a hardcore death is final. Returns an error text or null. */
    public @Nullable String setMode(Companion c, String mode) {
        if (c.state.fallen()) return c.name() + " already fell in hardcore mode; that cannot be undone.";
        if (!mode.equals("normal") && !mode.equals("hardcore")) return "Mode is normal or hardcore.";
        if (mode.equals(c.state.mode)) return null;
        c.state.mode = mode;
        c.event("note", mode.equals("hardcore")
                ? "your owner set your life to hardcore mode: from now on, if you die you are gone for good"
                : "your owner set your life to normal mode: from now on, if you die you respawn", false, null);
        save();
        return null;
    }

    /** God and Builder settings chosen by the owner; returns an error text or null. */
    public @Nullable String setHosted(Companion c, String key, String value) {
        String v = value.toLowerCase(java.util.Locale.ROOT).trim();
        String note;
        switch (key) {
            case "campaign" -> {
                if (!c.state.kind.equals("god")) return c.name() + " is not a god.";
                com.voxelmind.local.hosted.HostedTexts.Campaign campaign = com.voxelmind.local.hosted.HostedTexts.CAMPAIGNS.get(v);
                if (campaign == null) return "Campaigns: " + String.join(", ", new java.util.TreeSet<>(com.voxelmind.local.hosted.HostedTexts.CAMPAIGNS.keySet())) + ".";
                if (v.equals(c.state.campaign)) return null;
                c.state.campaign = v;
                note = "your owner set the campaign frame to " + campaign.label();
            }
            case "chaos" -> {
                if (!c.state.kind.equals("god")) return c.name() + " is not a god.";
                int level;
                try {
                    level = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    return "Chaos is a level from 1 to 5.";
                }
                if (level < 1 || level > 5) return "Chaos is a level from 1 to 5.";
                if (level == c.state.chaos) return null;
                c.state.chaos = level;
                note = "your owner set the chaos level to " + level + "/5 (" + com.voxelmind.local.hosted.HostedTexts.chaosText(level) + ")";
            }
            case "guests" -> {
                if (!c.state.kind.equals("god")) return c.name() + " is not a god.";
                boolean on = v.equals("on") || v.equals("true") || v.equals("visible");
                if (!on && !v.equals("off") && !v.equals("false") && !v.equals("hidden")) return "Guests are on or off.";
                if (on == c.state.guests) return null;
                c.state.guests = on;
                note = on ? "your owner made the other players on this world visible to you" : "your owner hid the other players on this world from you";
            }
            case "body" -> {
                if (!c.state.kind.equals("builder")) return c.name() + " is not a builder.";
                if (!com.voxelmind.local.hosted.SpiritBody.TYPES.contains(v)) return "Bodies: allay, bee, vex, parrot, bat.";
                if (v.equals(c.state.bodyType)) return null;
                c.state.bodyType = v;
                note = "your owner gave you the body of a " + v;
                if (c.hosted instanceof BuilderMind b) b.changeBody(v);
            }
            default -> {
                return "Unknown setting " + key + ".";
            }
        }
        c.event("note", note, true, null);
        if (c.hosted != null && c.hosted.active()) c.hosted.wake("settings", 1000);
        save();
        return null;
    }

    /** Takes the companion's body out of the world; its data stays. */
    public void dismiss(Companion c, boolean announce, boolean deactivate) {
        CompanionPlayer body = c.body();
        if (deactivate) c.state.active = false;
        if (c.hosted != null) {
            c.hosted.stop(deactivate ? "your owner sent you away" : "your owner left the world");
            save();
            return;
        }
        if (body != null) {
            c.event("presence", deactivate ? "your owner sent you away; you left the world" : "you left the world together with your owner", false, null);
            c.detach();
            if (announce) {
                server.getPlayerList().broadcastSystemMessage(
                        Component.translatable("multiplayer.player.left", body.getDisplayName()).withStyle(ChatFormatting.YELLOW), false);
            }
            body.disconnect();
            server.getPlayerList().remove(body);
        }
        save();
    }

    /** Gives the companion a borrowed skin; a present body re-enters so clients load it. */
    public void applySkin(Companion c, Skins.Skin skin) {
        c.state.skinSource = skin.sourceName();
        c.state.skinValue = skin.value();
        c.state.skinSignature = skin.signature() == null ? "" : skin.signature();
        save();
        CompanionPlayer body = c.body();
        ServerPlayer owner = server.getPlayerList().getPlayer(c.state.owner);
        if (body != null && owner != null) {
            c.detach();
            body.disconnect();
            server.getPlayerList().remove(body);
            String error = spawnBody(c, owner);
            if (error != null) c.notifyOwner(error);
        }
    }

    public void delete(Companion c) {
        dismiss(c, true, true);
        companions.remove(c.state.id);
        try {
            Files.deleteIfExists(dirFor(c.state).resolve(c.state.id + ".json"));
        } catch (IOException e) {
            VoxelMindLocal.LOG.warn("Could not delete companion file of {}", c.name(), e);
        }
    }

    // ---- events ----

    private final java.util.Set<UUID> hinted = new java.util.HashSet<>();

    public void onPlayerJoin(ServerPlayer player) {
        if (player instanceof CompanionPlayer) return;
        server.execute(() -> tellAboutAi(player));
        if (ownedBy(player.getUUID()).isEmpty() && hinted.add(player.getUUID())) {
            var message = Component.literal("[VoxelMind] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.translatableWithFallback("voxelmind_local.notice.join",
                            "Press V (or type /vml) to bring an AI companion, a Builder or a God into this world. Talk to them in normal chat.")
                            .withStyle(ChatFormatting.GOLD));
            if (!com.voxelmind.local.config.LocalConfig.get().hasCredentials()) {
                if (server.isSingleplayerOwner(new NameAndId(player.getUUID(), player.getGameProfile().name()))) {
                    message.append(" ").append(Component.translatableWithFallback("voxelmind_local.notice.join.no_ai",
                                    "First choose how they think: VoxelMind Sparks (sign in with your Minecraft account, welcome Sparks included), Player2, your OpenRouter account or a local model:")
                                    .withStyle(ChatFormatting.GOLD))
                            .append(" ").append(com.voxelmind.local.command.VmlCommand.settingsLink());
                } else {
                    message.append(" ").append(Component.translatableWithFallback("voxelmind_local.notice.join.no_ai_server",
                            "This world has no AI connected yet; its host or an operator connects one (/vml setup).").withStyle(ChatFormatting.GOLD));
                }
            }
            player.sendSystemMessage(message);
        }
        com.voxelmind.local.provider.UsageStats.noticeOnce(player, mayConfigure(player));
        for (GodMind god : gods()) if (god.sees(player)) god.worldEvent(player.getGameProfile().name() + " joined the world", true);
        for (Companion c : ownedBy(player.getUUID())) {
            if (c.hosted != null) {
                if (c.state.active && !c.hosted.active() && mayHost(player)) {
                    server.execute(() -> {
                        String error = c.hosted.start(player);
                        if (error != null) c.notifyOwner(error);
                    });
                }
                continue;
            }
            if (c.state.active && c.body() == null) {
                server.execute(() -> {
                    String error = spawnBody(c, player);
                    if (error != null) c.notifyOwner(error);
                });
            }
        }
    }

    public void onPlayerLeave(ServerPlayer player) {
        if (player instanceof CompanionPlayer) return;
        for (Companion c : ownedBy(player.getUUID())) {
            if (c.body() != null || c.hosted != null && c.hosted.active()) dismiss(c, true, false);
        }
        for (GodMind god : gods()) if (god.sees(player)) god.worldEvent(player.getGameProfile().name() + " left the world", true);
    }

    /** Running God sessions, for world events and rules. */
    public List<GodMind> gods() {
        List<GodMind> out = new ArrayList<>();
        for (Companion c : companions.values()) if (c.hosted instanceof GodMind god && god.active()) out.add(god);
        return out;
    }

    /** Running hosted sessions owned by this player. */
    public List<HostedMind> hostedOf(UUID owner) {
        List<HostedMind> out = new ArrayList<>();
        for (Companion c : ownedBy(owner)) if (c.hosted != null && c.hosted.active()) out.add(c.hosted);
        return out;
    }

    /** Whether an entity is the current body of a running Builder. */
    public boolean isBuilderBody(UUID entity) {
        for (Companion c : companions.values()) if (c.hosted instanceof BuilderMind b && entity.equals(b.bodyUuid())) return true;
        return false;
    }

    public void onRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        if (!(newPlayer instanceof CompanionPlayer body)) return;
        Companion c = companions.get(body.getUUID());
        if (c != null) c.onRespawned(body);
    }

    public void onChat(ServerPlayer sender, String text) {
        if (sender instanceof CompanionPlayer) return;
        for (Companion c : companions.values()) {
            if (c.hosted != null) {
                if (!c.hosted.active()) continue;
                boolean owner = sender.getUUID().equals(c.state.owner);
                if (c.hosted instanceof GodMind god) {
                    god.fireRules(new GodRules.Event("chat", sender, Compat.level(sender), sender.position(), "", "", text));
                    if (!god.sees(sender)) continue;
                }
                c.hosted.hear(sender.getGameProfile().name() + (owner ? " (your owner)" : "") + ": \"" + text + "\"");
                continue;
            }
            CompanionPlayer body = c.body();
            if (body == null || body.isRemoved()) continue;
            boolean owner = sender.getUUID().equals(c.state.owner);
            boolean inRange = sender.level() == body.level() && sender.distanceTo(body) <= c.config().hearingRange;
            if (owner || inRange) c.hear(sender.getGameProfile().name(), owner, text, inRange ? "public" : "public, far away");
        }
    }

    private static @Nullable BlockPos spotNear(ServerPlayer owner) {
        ServerLevel level = Compat.level(owner);
        BlockPos base = owner.blockPosition();
        int[][] offsets = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dy : new int[]{0, 1, -1, 2, -2, 3}) {
            for (int[] o : offsets) {
                BlockPos spot = base.offset(o[0], dy, o[1]);
                if (PathFinder.standable(level, spot) && !PathFinder.isWater(level, spot)) return spot;
            }
        }
        return PathFinder.bodyFits(level, base) ? base : null;
    }
}
