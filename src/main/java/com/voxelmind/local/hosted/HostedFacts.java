package com.voxelmind.local.hosted;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.mind.Timeline;
import com.voxelmind.local.world.Names;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The fact blocks of the hosted minds, rendered like the hosted brain rendered the mod's snapshot. */
public final class HostedFacts {
    private static final List<String> VALUABLE = List.of("netherite", "diamond", "elytra", "totem_of_undying", "enchanted_golden_apple",
            "emerald", "ender_pearl", "golden_apple", "ancient_debris", "iron_ingot", "gold_ingot", "bow", "sword", "pickaxe", "shield",
            "bed", "bucket", "compass", "map", "saddle", "torch", "bread", "cooked", "apple");
    private static final int TIMELINE_ROWS = 80;
    private static final int TIMELINE_CHARS = 64_000;

    private HostedFacts() {}

    public static String version() {
        return FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("26.2");
    }

    public static String dim(ServerLevel level) {
        return level.dimension().identifier().getPath();
    }

    /** [WORLD] lines 1 and 2. */
    public static String world(MinecraftServer server, ServerLevel level) {
        long time = level.getDefaultClockTime();
        long t = Math.floorMod(time, 24000);
        long day = time / 24000;
        int hour = (int) (((t + 6000) % 24000) / 1000);
        int minute = (int) ((((t + 6000) % 1000) / 1000.0) * 60);
        String phase = t < 12000 ? "day" : t < 13000 ? "dusk" : t < 23000 ? "night" : "dawn";
        String weather = level.isThundering() ? "thunderstorm" : level.isRaining() ? "rain" : "clear";
        double border = level.getWorldBorder().getSize();
        var ticks = server.tickRateManager();
        return "[WORLD]\nMinecraft " + version() + ", world \"" + server.getWorldData().getLevelName() + "\", " + dim(level) + ". Day " + day + ", "
                + String.format(Locale.ROOT, "%02d:%02d", hour, minute) + " (" + phase + "), " + weather + ", difficulty "
                + server.getWorldData().getDifficulty().getSerializedName() + ".\n"
                + "Tick rate " + Math.round(ticks.tickrate()) + (ticks.isFrozen() ? " (FROZEN)" : "") + ", world border "
                + (border >= 5.9e7 ? "default" : Math.round(border) + " blocks") + ".";
    }

    /** One [PLAYERS] / [OWNER] entry. */
    public static String player(ServerPlayer p, boolean host) {
        ServerLevel level = Compat.level(p);
        BlockPos pos = p.blockPosition();
        String biome = level.getBiome(pos).unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown");
        StringBuilder out = new StringBuilder();
        out.append("- ").append(p.getGameProfile().name()).append(host ? " (host)" : "").append(": at ")
                .append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ()).append(" in ").append(dim(level))
                .append(", ").append(biome).append(", ").append(level.canSeeSky(pos.above()) ? "open sky" : "sky obstructed")
                .append(", light ").append(level.getMaxLocalRawBrightness(pos))
                .append(", HP ").append(String.format(Locale.ROOT, "%.1f", p.getHealth())).append("/20, food ").append(p.getFoodData().getFoodLevel())
                .append("/20, armor ").append(p.getArmorValue()).append(", XP ").append(p.experienceLevel).append(", ")
                .append(p.gameMode().getName()).append(", holding ")
                .append(p.getMainHandItem().isEmpty() ? "nothing" : Names.id(p.getMainHandItem().getItem()));
        if (p.getVehicle() != null) out.append(", riding ").append(Names.id(p.getVehicle().getType()));
        if (p.isSleeping()) out.append(", asleep");
        out.append(".\n  Effects: ");
        List<String> effects = new ArrayList<>();
        for (MobEffectInstance e : p.getActiveEffects()) {
            effects.add(e.getEffect().unwrapKey().map(k -> k.identifier().getPath()).orElse("effect") + " " + (e.getAmplifier() + 1)
                    + " " + (e.isInfiniteDuration() ? "∞" : (e.getDuration() / 20) + "s"));
        }
        out.append(effects.isEmpty() ? "none" : String.join(", ", effects)).append(". Looking at: ").append(lookingAt(p))
                .append(". Nearby (32m): ").append(nearby(p)).append(".\n  Inventory: ").append(inventory(p)).append('.');
        return out.toString();
    }

    public static String lookingAt(ServerPlayer p) {
        HitResult hit = p.pick(10.0, 0, false);
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
            return Names.id(p.level().getBlockState(b.getBlockPos()).getBlock()) + " (" + Math.round(Math.sqrt(b.getBlockPos().distToCenterSqr(p.getEyePosition()))) + "m)";
        }
        return "nothing in particular";
    }

    public static BlockPos lookingAtPos(ServerPlayer p) {
        HitResult hit = p.pick(10.0, 0, false);
        return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
    }

    private static String nearby(ServerPlayer p) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(32))) {
            String key;
            if (e instanceof CompanionPlayer) key = "companions";
            else if (e instanceof ServerPlayer) key = "players";
            else if (e instanceof AbstractVillager) key = "villagers";
            else if (e instanceof ItemEntity) key = "drops";
            else if (e instanceof Monster || e instanceof Animal || e instanceof LivingEntity) key = Names.id(e.getType());
            else continue;
            if (counts.size() >= 24 && !counts.containsKey(key)) continue;
            counts.merge(key, 1, Integer::sum);
        }
        if (counts.isEmpty()) return "nothing notable";
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(k + " " + v));
        return String.join(", ", parts);
    }

    private static String inventory(ServerPlayer p) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        int used = 0;
        var inv = p.getInventory();
        for (int i = 0; i < 36 && i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            used++;
            totals.merge(Names.id(s.getItem()), s.getCount(), Integer::sum);
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(totals.entrySet());
        ranked.sort((a, b) -> {
            int ra = rank(a.getKey());
            int rb = rank(b.getKey());
            return ra != rb ? Integer.compare(ra, rb) : Integer.compare(b.getValue(), a.getValue());
        });
        List<String> top = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ranked) {
            if (top.size() >= 10) break;
            top.add(e.getKey() + " x" + e.getValue());
        }
        return (top.isEmpty() ? "nothing notable" : String.join(", ", top)) + " (" + used + "/36 slots)";
    }

    private static int rank(String id) {
        for (int i = 0; i < VALUABLE.size(); i++) if (id.contains(VALUABLE.get(i))) return i;
        return VALUABLE.size();
    }

    /** "#seq Ns ago — text", oldest first, bounded like the hosted timeline. */
    public static String timeline(List<Timeline.Event> events, long now) {
        if (events.isEmpty()) return "[TIMELINE]\nNothing has happened yet.";
        List<String> lines = new ArrayList<>();
        int chars = 0;
        for (int i = events.size() - 1; i >= 0 && lines.size() < TIMELINE_ROWS; i--) {
            Timeline.Event e = events.get(i);
            String line = "#" + e.seq + " " + Timeline.age(now - e.epochMs) + " — " + rowText(e);
            if (chars + line.length() > TIMELINE_CHARS) break;
            chars += line.length();
            lines.addFirst(line);
        }
        return "[TIMELINE] (oldest first)\n" + String.join("\n", lines);
    }

    public static String rowText(Timeline.Event e) {
        return switch (e.kind) {
            case "said" -> "you said: \"" + e.text + "\"";
            case "chat_in" -> e.text;
            default -> e.text;
        };
    }

    /** "12m north-east" or "right here", from one position to another. */
    public static String direction(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        if (dist == 0) return "right here";
        String ns = Math.abs(dz) > Math.abs(dx) / 2.0 ? (dz < 0 ? "north" : "south") : "";
        String ew = Math.abs(dx) > Math.abs(dz) / 2.0 ? (dx > 0 ? "east" : "west") : "";
        return dist + "m " + (ns.isEmpty() ? ew : ew.isEmpty() ? ns : ns + "-" + ew);
    }
}
