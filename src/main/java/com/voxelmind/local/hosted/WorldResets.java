package com.voxelmind.local.hosted;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.phys.Vec2;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Remembers which world settings the God's commands touched, so stopping it puts them back:
 * gamerules, difficulty, the world border, the tick rate, effects and game modes of the players.
 */
public final class WorldResets {
    private final Map<String, String> gamerules = new LinkedHashMap<>();
    private Difficulty difficulty;
    private double border = Double.NaN;
    private boolean tickTouched;
    private boolean effectsTouched;
    private boolean gamemodeTouched;
    private final Set<String> players = new LinkedHashSet<>();

    /** Call before a command runs; records the original value of what it may change. */
    public void before(MinecraftServer server, String command) {
        List<String> verbs = CommandPolicy.verbs(CommandPolicy.normalize(command));
        String[] tokens = CommandPolicy.normalize(command).split(" ");
        for (String verb : verbs) {
            switch (verb) {
                case "gamerule" -> {
                    int i = indexOf(tokens, "gamerule");
                    if (i >= 0 && i + 1 < tokens.length && !gamerules.containsKey(tokens[i + 1])) {
                        String rule = tokens[i + 1];
                        String value = queryGamerule(server, rule);
                        if (value != null) gamerules.put(rule, value);
                    }
                }
                case "difficulty" -> {
                    if (difficulty == null) difficulty = server.getWorldData().getDifficulty();
                }
                case "worldborder" -> {
                    if (Double.isNaN(border)) border = server.overworld().getWorldBorder().getSize();
                }
                case "tick" -> tickTouched = true;
                case "effect" -> effectsTouched = true;
                case "gamemode" -> gamemodeTouched = true;
                default -> {
                }
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) players.add(p.getGameProfile().name());
    }

    private static int indexOf(String[] tokens, String word) {
        for (int i = 0; i < tokens.length; i++) if (tokens[i].equalsIgnoreCase(word)) return i;
        return -1;
    }

    private static String queryGamerule(MinecraftServer server, String rule) {
        CommandRunner.Result r = CommandRunner.runAt(server, server.overworld(), net.minecraft.world.phys.Vec3.ZERO,
                new Vec2(0, 0), "VoxelMind", "gamerule " + rule);
        if (!r.ok()) return null;
        String text = r.text().trim();
        int colon = text.lastIndexOf(':');
        return colon >= 0 ? text.substring(colon + 1).trim() : null;
    }

    /** Puts back what was touched; returns a short description. */
    public String restore(MinecraftServer server, List<ServerPlayer> humans) {
        StringBuilder done = new StringBuilder();
        var level = server.overworld();
        var pos = net.minecraft.world.phys.Vec3.ZERO;
        for (Map.Entry<String, String> g : gamerules.entrySet()) {
            CommandRunner.runAt(server, level, pos, new Vec2(0, 0), "VoxelMind", "gamerule " + g.getKey() + " " + g.getValue());
        }
        if (!gamerules.isEmpty()) done.append(gamerules.size()).append(" gamerule(s)");
        if (difficulty != null) {
            CommandRunner.runAt(server, level, pos, new Vec2(0, 0), "VoxelMind", "difficulty " + difficulty.getSerializedName());
            done.append(done.isEmpty() ? "" : ", ").append("difficulty");
        }
        if (!Double.isNaN(border)) {
            CommandRunner.runAt(server, level, pos, new Vec2(0, 0), "VoxelMind", "worldborder set " + (long) border);
            done.append(done.isEmpty() ? "" : ", ").append("world border");
        }
        if (tickTouched) {
            CommandRunner.runAt(server, level, pos, new Vec2(0, 0), "VoxelMind", "tick unfreeze");
            CommandRunner.runAt(server, level, pos, new Vec2(0, 0), "VoxelMind", "tick rate 20");
            done.append(done.isEmpty() ? "" : ", ").append("tick rate");
        }
        for (ServerPlayer p : humans) {
            String name = p.getGameProfile().name();
            if (effectsTouched) CommandRunner.runAt(server, level, pos, new Vec2(0, 0), "VoxelMind", "effect clear " + name);
            if (gamemodeTouched) {
                CommandRunner.runAt(server, level, pos, new Vec2(0, 0), "VoxelMind",
                        "gamemode " + server.getDefaultGameType().getName().toLowerCase(Locale.ROOT) + " " + name);
            }
        }
        if (effectsTouched) done.append(done.isEmpty() ? "" : ", ").append("effects");
        if (gamemodeTouched) done.append(done.isEmpty() ? "" : ", ").append("game modes");
        gamerules.clear();
        difficulty = null;
        border = Double.NaN;
        tickTouched = effectsTouched = gamemodeTouched = false;
        return done.toString();
    }

    static String translatedArg(Component c, int index) {
        if (c.getContents() instanceof TranslatableContents t && t.getArgs().length > index) return String.valueOf(t.getArgs()[index]);
        return "";
    }
}
