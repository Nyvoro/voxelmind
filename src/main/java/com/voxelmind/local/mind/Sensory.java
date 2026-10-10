package com.voxelmind.local.mind;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Cover;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Placed;
import com.voxelmind.local.world.Resources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Renders what the body perceives right now as compact text facts. */
public final class Sensory {
    private static final int SCAN_H = 20;
    private static final int SCAN_V = 10;
    /** Placed blocks are counted in the cube of this radius around the feet. */
    private static final int PLACED_RADIUS = 8;
    private static final Set<String> FEATURES = Set.of("crafting_table", "furnace", "blast_furnace", "smoker", "chest", "barrel",
            "enchanting_table", "anvil", "smithing_table", "composter", "trapped_chest");
    private static final Set<String> GROUND = Set.of("sand", "gravel", "clay", "sugar_cane", "pumpkin", "melon", "sweet_berry_bush",
            "obsidian", "red_sand", "snow_block", "ice");

    private Sensory() {}

    /** Blocks of one kind in view: those no player placed, and apart from them those players placed, by placer. */
    private static final class Agg {
        int count;
        BlockPos closest;
        double dist = Double.MAX_VALUE;
        BlockState closestState;
        int placed;
        final Map<String, Integer> placedBy = new LinkedHashMap<>();
        BlockPos placedClosest;
        double placedDist = Double.MAX_VALUE;

        double nearest() {
            return Math.min(dist, placedDist);
        }
    }

    public static String render(CompanionPlayer body, UUID ownerId, String ownerName, String wokeBy, String bodyAction) {
        ServerLevel level = Compat.level(body);
        StringBuilder out = new StringBuilder();
        out.append("[SENSORY]\nWhat you perceive right now; lists can be incomplete.\n");
        if (!wokeBy.isEmpty()) out.append("Woke by: ").append(wokeBy).append('\n');

        int food = body.getFoodData().getFoodLevel();
        out.append("HP ").append(Math.round(body.getHealth())).append("/20  Food ").append(food).append("/20");
        if (food <= 6) out.append(" (too hungry to sprint)");
        else if (food < 14) out.append(" (hungry)");
        out.append("  Armor ").append(body.getArmorValue()).append("  XP level ").append(body.experienceLevel);
        if (body.isInWater()) out.append("  [in water]");
        if (body.isInLava()) out.append("  [IN LAVA]");
        if (body.isOnFire()) out.append("  [on fire]");
        if (body.getAirSupply() < body.getMaxAirSupply()) out.append("  Air ").append(Math.max(0, body.getAirSupply() / 30)).append("/10");
        if (body.isSleeping()) out.append("  [sleeping]");
        out.append('\n');
        out.append(waterLine(level, body.blockPosition()));

        BlockPos feet = body.blockPosition();
        String biome = level.getBiome(feet).unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown");
        out.append("Pos ").append(Names.pos(feet)).append(" facing ").append(Names.facing(body.getYRot()))
                .append(", biome ").append(biome).append(", ").append(level.dimension().identifier().getPath()).append('\n');
        out.append("Time: ").append(timeText(level)).append("; weather ")
                .append(level.isThundering() ? "thunderstorm" : level.isRaining() ? "rain" : "clear").append('\n');
        out.append(groundLine(level, feet));
        out.append(aboveLine(level, feet));
        out.append("Standing on ").append(Names.id(level.getBlockState(feet.below()).getBlock())).append(placedNote(level, feet.below()))
                .append("; block in front: ").append(Names.id(level.getBlockState(feet.relative(Direction.fromYRot(body.getYRot()))).getBlock()))
                .append('\n');
        Placed.Summary placed = Placed.summary(level, feet, PLACED_RADIUS);
        if (placed.total() > 0) {
            out.append("Placed blocks within ").append(PLACED_RADIUS).append(": ").append(placed.total())
                    .append(" (").append(Placed.list(placed.byPlacer(), " ", 4)).append("): ").append(Placed.list(placed.byBlock(), " ×", 4)).append('\n');
        }

        out.append("In hand: ").append(body.getMainHandItem().isEmpty() ? "nothing" : Names.stack(body.getMainHandItem()))
                .append("; off hand: ").append(body.getOffhandItem().isEmpty() ? "nothing" : Names.stack(body.getOffhandItem()))
                .append("; armor: ").append(Inv.equipment(body)).append('\n');
        int used = Inv.usedSlots(body);
        out.append("Inventory (").append(used).append(" of 36 slots used):\n");
        String inv = Inv.list(body);
        out.append(inv.isEmpty() ? "  empty\n" : inv);

        appendPeople(out, body, level, ownerId, ownerName);
        appendEntities(out, body, level);
        appendBlocks(out, body, level);

        if (!bodyAction.isEmpty()) out.append("BODY ACTION: ").append(bodyAction).append('\n');
        return out.toString();
    }

    private static void appendPeople(StringBuilder out, CompanionPlayer body, ServerLevel level, UUID ownerId, String ownerName) {
        Vec3 me = body.position();
        List<String> lines = new ArrayList<>();
        boolean ownerSeen = false;
        for (ServerPlayer p : level.players()) {
            if (p == body || p.isSpectator()) continue;
            double d = p.distanceTo(body);
            boolean owner = p.getUUID().equals(ownerId);
            if (d > 64 && !owner) continue;
            if (owner) ownerSeen = true;
            StringBuilder line = new StringBuilder("  - ").append(p.getGameProfile().name());
            if (owner) line.append(" (your owner)");
            if (p instanceof CompanionPlayer) line.append(" (an AI companion)");
            line.append(" [#").append(p.getId()).append("] ").append(Math.round(d)).append("m ").append(Names.direction(me, p.position()))
                    .append(" at ").append(Names.pos(p.blockPosition()));
            if (!p.getMainHandItem().isEmpty()) line.append(", holding ").append(Names.id(p.getMainHandItem().getItem()));
            if (p.isSleeping()) line.append(", sleeping");
            if (p.isDeadOrDying()) line.append(", dead");
            lines.add(line.toString());
        }
        out.append("Players:\n");
        if (lines.isEmpty()) out.append("  none in range\n");
        else for (String l : lines) out.append(l).append('\n');
        if (!ownerSeen) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
            if (owner == null) out.append("Your owner ").append(ownerName).append(" is offline.\n");
            else out.append("Your owner ").append(ownerName).append(" is in ").append(owner.level().dimension().identifier().getPath()).append(".\n");
        }
    }

    private static void appendEntities(StringBuilder out, CompanionPlayer body, ServerLevel level) {
        Vec3 me = body.position();
        List<Entity> near = level.getEntities(body, new AABB(body.blockPosition()).inflate(24), e -> e.isAlive() && !(e instanceof ServerPlayer));
        near.sort(Comparator.comparingDouble(body::distanceToSqr));
        List<String> threats = new ArrayList<>();
        Map<String, List<Entity>> creatures = new LinkedHashMap<>();
        Map<String, Integer> items = new LinkedHashMap<>();
        Map<String, Entity> itemClosest = new LinkedHashMap<>();
        for (Entity e : near) {
            double d = body.distanceTo(e);
            if (e instanceof Enemy && e instanceof LivingEntity living) {
                if (threats.size() < 8) {
                    threats.add("  - " + Names.id(e.getType()) + " [#" + e.getId() + "] " + Math.round(d) + "m " + Names.direction(me, e.position())
                            + " at " + Names.pos(e.blockPosition()) + ", HP " + Math.round(living.getHealth()));
                }
            } else if (e instanceof ItemEntity item && d <= 16) {
                String id = Names.id(item.getItem().getItem());
                items.merge(id, item.getItem().getCount(), Integer::sum);
                itemClosest.putIfAbsent(id, item);
            } else if (e instanceof LivingEntity) {
                creatures.computeIfAbsent(Names.id(e.getType()), k -> new ArrayList<>()).add(e);
            }
        }
        if (!threats.isEmpty()) {
            out.append("Threats:\n");
            for (String t : threats) out.append(t).append('\n');
        }
        if (!creatures.isEmpty()) {
            out.append("Creatures: ");
            int n = 0;
            for (Map.Entry<String, List<Entity>> c : creatures.entrySet()) {
                if (n++ > 0) out.append("; ");
                Entity first = c.getValue().getFirst();
                out.append(c.getKey()).append(" ×").append(c.getValue().size()).append(" (closest [#").append(first.getId()).append("] ")
                        .append(Math.round(body.distanceTo(first))).append("m ").append(Names.direction(me, first.position()));
                if (first instanceof Animal animal && animal.isBaby()) out.append(", baby");
                out.append(')');
                if (n >= 8) break;
            }
            out.append('\n');
        }
        if (!items.isEmpty()) {
            out.append("Dropped items: ");
            int n = 0;
            for (Map.Entry<String, Integer> i : items.entrySet()) {
                if (n++ > 0) out.append("; ");
                Entity first = itemClosest.get(i.getKey());
                out.append(i.getKey()).append(" ×").append(i.getValue()).append(' ').append(Math.round(body.distanceTo(first))).append("m ")
                        .append(Names.direction(me, first.position()));
                if (n >= 10) break;
            }
            out.append('\n');
        }
    }

    private static void appendBlocks(StringBuilder out, CompanionPlayer body, ServerLevel level) {
        BlockPos center = body.blockPosition();
        Map<String, Agg> resources = new LinkedHashMap<>();
        Map<String, Agg> features = new LinkedHashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -SCAN_V; dy <= SCAN_V; dy++) {
            for (int dx = -SCAN_H; dx <= SCAN_H; dx++) {
                for (int dz = -SCAN_H; dz <= SCAN_H; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.isLoaded(cursor)) continue;
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    String key = null;
                    boolean feature = false;
                    String id = Names.id(state.getBlock());
                    if (state.is(BlockTags.LOGS) || Resources.isOre(state.getBlock())) key = id;
                    else if (FEATURES.contains(id) || state.is(BlockTags.BEDS)) {
                        key = state.is(BlockTags.BEDS) ? "bed" : id;
                        feature = true;
                    } else if (opens(state)) {
                        // A door is counted once, at its lower half.
                        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) continue;
                        key = id;
                        feature = true;
                    } else if (GROUND.contains(id) || state.is(BlockTags.CROPS)) key = id;
                    else if (state.getFluidState().is(FluidTags.LAVA) && state.getFluidState().isSource()) key = "lava";
                    else if (state.getFluidState().is(FluidTags.WATER) && state.getFluidState().isSource()) key = "water";
                    else if (id.equals("stone") || id.equals("deepslate") || id.equals("cobblestone")) key = id;
                    if (key == null) continue;
                    if (!feature && !Resources.exposed(level, cursor)) continue;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    Agg agg = (feature ? features : resources).computeIfAbsent(key, k -> new Agg());
                    // A resource a player placed is counted apart: gather and mine_block by type leave it.
                    String placer = feature ? null : Placed.placer(level, cursor);
                    if (placer != null) {
                        agg.placed++;
                        agg.placedBy.merge(placer, 1, Integer::sum);
                        if (d < agg.placedDist) {
                            agg.placedDist = d;
                            agg.placedClosest = cursor.immutable();
                        }
                        continue;
                    }
                    agg.count++;
                    if (d < agg.dist) {
                        agg.dist = d;
                        agg.closest = cursor.immutable();
                        agg.closestState = state;
                    }
                }
            }
        }
        Vec3 me = body.position();
        if (!resources.isEmpty()) {
            out.append("Resources you can see within ").append(SCAN_H).append(" blocks:\n");
            resources.entrySet().stream().sorted(Comparator.comparingDouble(e -> e.getValue().nearest())).limit(14).forEach(e -> {
                Agg agg = e.getValue();
                // "oak_log ×4 (closest …); 86 more placed by players (Robin 86), closest …" or "oak_log ×86, all placed by players (…), closest …"
                String placed = agg.placed == 0 ? "" : "placed by players (" + Placed.list(agg.placedBy, " ", 3) + "), closest "
                        + Math.round(agg.placedDist) + "m " + Names.direction(me, Vec3.atCenterOf(agg.placedClosest)) + " at " + Names.pos(agg.placedClosest);
                out.append("  - ").append(e.getKey());
                if (agg.count == 0) {
                    out.append(" ×").append(agg.placed).append(", all ").append(placed).append('\n');
                    return;
                }
                out.append(" ×").append(agg.count).append(" (closest ").append(Math.round(agg.dist)).append("m ")
                        .append(Names.direction(me, Vec3.atCenterOf(agg.closest))).append(" at ").append(Names.pos(agg.closest)).append(')')
                        .append(agg.placed == 0 ? "" : "; " + agg.placed + " more " + placed).append('\n');
            });
        }
        if (!features.isEmpty()) {
            out.append("Nearby: ");
            int n = 0;
            for (Map.Entry<String, Agg> e : features.entrySet()) {
                if (n++ > 0) out.append("; ");
                out.append(e.getKey()).append(e.getValue().count > 1 ? " ×" + e.getValue().count : "").append(" at ")
                        .append(Names.pos(e.getValue().closest)).append(' ').append(Math.round(e.getValue().dist)).append('m');
                BlockState closest = e.getValue().closestState;
                if (opens(closest) && closest.hasProperty(BlockStateProperties.OPEN)) {
                    out.append(closest.getValue(BlockStateProperties.OPEN) ? ", open" : ", closed");
                    out.append(Cover.sides(level, e.getValue().closest, closest));
                }
            }
            out.append('\n');
        }
    }

    /** Doors, fence gates and trapdoors: features with an open or closed state. */
    private static boolean opens(BlockState state) {
        return state.is(BlockTags.DOORS) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.TRAPDOORS);
    }

    /** How even the ground is nearby, from the surface heights within 6 blocks. */
    private static String groundLine(ServerLevel level, BlockPos feet) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        boolean water = false;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                if (!level.isLoaded(new BlockPos(x, feet.getY(), z))) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (level.getFluidState(new BlockPos(x, top, z)).is(FluidTags.WATER)) {
                    water = true;
                    continue;
                }
                min = Math.min(min, top);
                max = Math.max(max, top);
            }
        }
        if (min == Integer.MAX_VALUE) return "";
        return "Ground within 6 blocks: top blocks from y=" + min + " to y=" + max + (max - min <= 1 ? " (flat)" : max - min <= 3 ? " (uneven)" : " (hilly)")
                + (water ? ", some water" : "") + "; survey gives the exact grid.\n";
    }

    /** The column over the head, literally: the first block above it and the highest block of the column. */
    private static String aboveLine(ServerLevel level, BlockPos feet) {
        int head = feet.getY() + 1;
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, feet.getX(), feet.getZ()) - 1;
        if (top <= head) return "Above you: open sky.\n";
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(feet.getX(), head + 1, feet.getZ());
        while (cursor.getY() < top && level.getBlockState(cursor).isAir()) cursor.move(Direction.UP);
        BlockPos first = cursor.immutable();
        String placer = Placed.placer(level, first);
        StringBuilder line = new StringBuilder("Above you: ").append(Names.id(level.getBlockState(first).getBlock())).append(" at y=").append(first.getY())
                .append(" (").append(first.getY() - head).append(" up").append(placer == null ? "" : ", placed by " + placer).append(')');
        if (first.getY() == top) {
            line.append("; the highest block in this column is at y=").append(top);
        } else {
            BlockPos highest = new BlockPos(feet.getX(), top, feet.getZ());
            line.append("; the highest block in this column is ").append(Names.id(level.getBlockState(highest).getBlock())).append(" at y=").append(top)
                    .append(placedNote(level, highest));
        }
        return line.append(".\n").toString();
    }

    /** With the head in water: where the water above it ends, and whether air or a block comes next. */
    private static String waterLine(ServerLevel level, BlockPos feet) {
        BlockPos head = feet.above();
        if (!level.getFluidState(head).is(FluidTags.WATER)) return "";
        BlockPos.MutableBlockPos cursor = head.mutable();
        int limit = head.getY() + 48;
        while (cursor.getY() < limit && level.getFluidState(cursor).is(FluidTags.WATER)) cursor.move(Direction.UP);
        if (level.getFluidState(cursor).is(FluidTags.WATER)) return "Head under water: the water goes on above y=" + limit + ".\n";
        BlockState next = level.getBlockState(cursor);
        int surface = cursor.getY() - 1;
        return "Head under water: the water above you ends at y=" + surface + " (" + (surface - head.getY()) + " up); above it is "
                + (next.isAir() ? "air" : Names.id(next.getBlock()) + placedNote(level, cursor)) + " at y=" + cursor.getY() + ".\n";
    }

    /** " (placed by NAME)" when a player placed the block at {@code pos}. */
    private static String placedNote(ServerLevel level, BlockPos pos) {
        String placer = Placed.placer(level, pos);
        return placer == null ? "" : " (placed by " + placer + ")";
    }

    private static String timeText(ServerLevel level) {
        long t = Math.floorMod(level.getOverworldClockTime(), 24000L);
        String phase;
        long ticksToChange;
        if (t < 12000) {
            phase = "day";
            ticksToChange = 12000 - t;
            return phase + " (~" + Math.max(1, ticksToChange / 1200) + " min until dusk)";
        } else if (t < 13000) {
            return "dusk (night begins in ~" + Math.max(1, (13000 - t) / 20) + "s)";
        } else if (t < 23000) {
            return "night (~" + Math.max(1, (23000 - t) / 1200) + " min until dawn)";
        }
        return "dawn";
    }

    public static @Nullable ServerPlayer owner(CompanionPlayer body, UUID ownerId) {
        return body.level().getServer().getPlayerList().getPlayer(ownerId);
    }
}
