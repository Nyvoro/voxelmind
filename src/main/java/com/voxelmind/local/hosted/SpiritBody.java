package com.voxelmind.local.hosted;

import com.voxelmind.local.compat.Compat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * The Builder's visible creature: a vanilla allay (or bee, vex, parrot, bat) without AI that floats
 * behind the owner's shoulder. It is not saved with the world; leftovers are removed when they load.
 */
public final class SpiritBody {
    public static final String TAG = "voxelmind_builder_body";
    public static final Set<String> TYPES = Set.of("allay", "bee", "vex", "parrot", "bat");

    private @Nullable UUID uuid;
    private @Nullable ServerLevel level;
    public String type = "allay";
    public String mode = "follow";
    private long tick;

    public @Nullable Entity entity() {
        if (uuid == null || level == null) return null;
        Entity e = level.getEntity(uuid);
        return e != null && e.isAlive() ? e : null;
    }

    public boolean present() {
        return entity() != null;
    }

    public static boolean isLeftover(Entity e, @Nullable UUID current) {
        return e.entityTags().contains(TAG) && !e.getUUID().equals(current);
    }

    public @Nullable UUID uuid() {
        return uuid;
    }

    /**
     * Creates the creature next to the owner; returns an error text or null. Built directly rather than
     * with /summon: a command issued while another command runs (e.g. /vml builder) is only queued.
     */
    public @Nullable String spawn(ServerPlayer owner, String name) {
        despawn(owner.level().getServer());
        ServerLevel at = Compat.level(owner);
        var entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(
                net.minecraft.resources.Identifier.withDefaultNamespace(type));
        if (entityType.isEmpty()) return "there is no creature " + type;
        Entity body = entityType.get().create(at, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (body == null) return "the " + type + " could not be created";
        body.snapTo(owner.getX() + 1, owner.getEyeY() + 0.4, owner.getZ(), owner.getYRot(), 0f);
        if (body instanceof net.minecraft.world.entity.Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        body.setNoGravity(true);
        body.setSilent(true);
        body.setInvulnerable(true);
        body.setGlowingTag(true);
        body.setCustomName(net.minecraft.network.chat.Component.literal(name));
        body.setCustomNameVisible(true);
        body.addTag(TAG);
        if (!at.addFreshEntity(body)) return "the " + type + " could not enter the world here";
        uuid = body.getUUID();
        level = at;
        return null;
    }

    /** Removes this body and leftovers; the bodies of other running Builders stay. */
    public void despawn(MinecraftServer server) {
        com.voxelmind.local.companion.CompanionManager m = com.voxelmind.local.companion.CompanionManager.get();
        for (ServerLevel l : server.getAllLevels()) {
            for (Entity e : l.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Entity.class), e -> e.entityTags().contains(TAG))) {
                if (e.getUUID().equals(uuid) || m == null || !m.isBuilderBody(e.getUUID())) e.discard();
            }
        }
        uuid = null;
        level = null;
    }

    /** Floats behind the owner's shoulder; far away or in another dimension it jumps to them. */
    public void tick(@Nullable ServerPlayer owner) {
        tick++;
        Entity body = entity();
        if (body == null || owner == null || mode.equals("stay")) return;
        if (body.level() != owner.level() || body.distanceTo(owner) > 24) {
            come(owner);
            return;
        }
        double yaw = Math.toRadians(owner.getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double lx = Math.cos(yaw);
        double lz = Math.sin(yaw);
        double tx = owner.getX() - 1.5 * fx + 0.8 * lx;
        double tz = owner.getZ() - 1.5 * fz + 0.8 * lz;
        double ty = owner.getEyeY() + 0.4 + Math.sin(tick * 0.08) * 0.08;
        double nx = body.getX() + (tx - body.getX()) * 0.15;
        double ny = body.getY() + (ty - body.getY()) * 0.15;
        double nz = body.getZ() + (tz - body.getZ()) * 0.15;
        double dx = owner.getX() - nx;
        double dy = owner.getEyeY() - ny;
        double dz = owner.getZ() - nz;
        float lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float lookPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        body.snapTo(nx, ny, nz, lookYaw, lookPitch);
        body.setYHeadRot(lookYaw);
        body.setYBodyRot(lookYaw);
    }

    public void come(ServerPlayer owner) {
        Entity body = entity();
        if (body == null) return;
        if (body.level() != owner.level()) {
            String name = body.getCustomName() == null ? "Fairy" : body.getCustomName().getString();
            spawn(owner, name);
            return;
        }
        body.snapTo(owner.getX() + 1, owner.getEyeY() + 0.4, owner.getZ());
    }
}
