package com.voxelmind.local.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Turns the target text of a tool call into something in the world. */
public final class Targets {
    private Targets() {}

    public sealed interface Target permits EntityTarget, PosTarget {
        String label();
    }

    public record EntityTarget(Entity entity) implements Target {
        public String label() {
            return Names.entity(entity);
        }
    }

    public record PosTarget(BlockPos pos, String label) implements Target {}

    /** Either a target or the reason none was found. */
    public record Resolution(@Nullable Target target, String error) {
        static Resolution of(Target target) {
            return new Resolution(target, "");
        }

        static Resolution error(String error) {
            return new Resolution(null, error);
        }
    }

    public static final int ENTITY_RANGE = 48;
    public static final int BLOCK_RANGE = 24;

    public static Resolution resolve(Player self, UUID ownerId, String raw, boolean allowBlocks, boolean allowDirections) {
        if (raw == null || raw.isBlank()) return Resolution.error("no target given");
        ServerLevel level = (ServerLevel) self.level();
        String text = raw.trim();
        String norm = Names.normalize(text);

        if (norm.equals("owner") || norm.equals("my_owner")) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
            return owner != null ? Resolution.of(new EntityTarget(owner)) : Resolution.error("your owner is not online");
        }

        Entity byId = entityById(level, text);
        if (byId != null) return Resolution.of(new EntityTarget(byId));

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player != self && player.getGameProfile().name().equalsIgnoreCase(text)) {
                if (player.level() != level) return Resolution.error(text + " is in another dimension");
                return Resolution.of(new EntityTarget(player));
            }
        }

        BlockPos coords = Names.coords(text);
        if (coords != null) return Resolution.of(new PosTarget(coords, Names.pos(coords)));
        int[] xz = Names.coords2(text);
        if (xz != null) {
            // Two numbers mean a column; stand on its ground.
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, xz[0], xz[1]);
            BlockPos ground = new BlockPos(xz[0], y, xz[1]);
            return Resolution.of(new PosTarget(ground, Names.pos(ground) + " (ground of column " + xz[0] + " " + xz[1] + ")"));
        }

        if (allowDirections) {
            int[] dir = Names.compass(text);
            if (dir != null) {
                double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1]);
                BlockPos from = self.blockPosition();
                int x = from.getX() + (int) Math.round(dir[0] / len * 24);
                int z = from.getZ() + (int) Math.round(dir[1] / len * 24);
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                return Resolution.of(new PosTarget(pos, text + " " + Names.pos(pos)));
            }
        }

        EntityType<?> type = Names.entityType(text);
        if (type != null) {
            Entity nearest = nearestEntity(self, e -> e.getType() == type && e.isAlive(), ENTITY_RANGE);
            if (nearest != null) return Resolution.of(new EntityTarget(nearest));
            if (!allowBlocks || Names.block(text) == null) return Resolution.error("no " + Names.id(type) + " within " + ENTITY_RANGE + " blocks");
        }

        if (allowBlocks) {
            Block block = Names.block(text);
            if (block != null) {
                BlockPos pos = nearestBlock(level, self.blockPosition(), s -> s.is(block), BLOCK_RANGE);
                if (pos != null) return Resolution.of(new PosTarget(pos, Names.id(block) + " " + Names.pos(pos)));
                return Resolution.error("no " + Names.id(block) + " within " + BLOCK_RANGE + " blocks");
            }
        }
        return Resolution.error("'" + text + "' is not a player, entity id, coordinates" + (allowDirections ? ", direction" : "")
                + (allowBlocks ? ", block" : "") + " or creature name I can find");
    }

    /** "#123", "player#123", "zombie#123" refer to the entity id shown in perception. */
    public static @Nullable Entity entityById(ServerLevel level, String text) {
        int hash = text.lastIndexOf('#');
        if (hash < 0) return null;
        try {
            int id = Integer.parseInt(text.substring(hash + 1).trim());
            Entity entity = level.getEntity(id);
            return entity != null && entity.isAlive() ? entity : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static @Nullable Entity nearestEntity(Player self, Predicate<Entity> filter, double range) {
        List<Entity> found = self.level().getEntities(self, new AABB(self.blockPosition()).inflate(range), filter);
        return found.stream().min(Comparator.comparingDouble(self::distanceToSqr)).orElse(null);
    }

    public static @Nullable LivingEntity nearestLiving(Player self, Predicate<LivingEntity> filter, double range) {
        Entity e = nearestEntity(self, x -> x instanceof LivingEntity l && l.isAlive() && filter.test(l), range);
        return (LivingEntity) e;
    }

    /** Nearest block matching {@code filter} within a cube of {@code range}; loaded chunks only. */
    public static @Nullable BlockPos nearestBlock(ServerLevel level, BlockPos center, Predicate<BlockState> filter, int range) {
        return nearestBlock(level, center, (pos, state) -> filter.test(state), range);
    }

    /** Nearest block whose position and state match; loaded chunks only. */
    public static @Nullable BlockPos nearestBlock(ServerLevel level, BlockPos center,
                                                  java.util.function.BiPredicate<BlockPos, BlockState> filter, int range) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int vertical = Math.min(range, 16);
        for (int dy = -vertical; dy <= vertical; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    double d = dx * dx + dy * dy * 1.5 + dz * dz;
                    if (d >= bestDist) continue;
                    if (!level.isLoaded(cursor)) continue;
                    if (filter.test(cursor, level.getBlockState(cursor))) {
                        bestDist = d;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }
}
