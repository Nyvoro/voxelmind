package com.voxelmind.local.hosted;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.companion.CompanionManager;
import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.world.Names;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Passes world events to running God and Builder sessions: as rule triggers, as timeline facts and as
 * wakes. Also removes a Builder body left over from an earlier session when its chunk loads.
 */
public final class HostedEvents {
    private static final float DAMAGE_WAKE = 4f;
    private static final java.util.List<Entity> LOADED_BODIES = new java.util.ArrayList<>();

    private HostedEvents() {}

    public static void register() {
        // Checked at the end of the tick: a body a Builder is summoning right now loads before its UUID is known.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity.entityTags().contains(SpiritBody.TAG)) LOADED_BODIES.add(entity);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (LOADED_BODIES.isEmpty()) return;
            CompanionManager m = CompanionManager.get();
            for (Entity e : LOADED_BODIES) if (m == null || !m.isBuilderBody(e.getUUID())) e.discard();
            LOADED_BODIES.clear();
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer p) || p instanceof CompanionPlayer) return;
            for (GodMind god : gods()) {
                god.fireRules(new GodRules.Event("block_break", p, (ServerLevel) level, Vec3.atCenterOf(pos), Names.id(state.getBlock()), "", ""));
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            CompanionManager m = CompanionManager.get();
            if (m == null || !(entity.level() instanceof ServerLevel level)) return;
            String message = entity.getCombatTracker().getDeathMessage().getString();
            if (entity instanceof ServerPlayer p && !(p instanceof CompanionPlayer)) {
                for (GodMind god : m.gods()) {
                    god.fireRules(new GodRules.Event("player_death", p, level, p.position(), "", "", message));
                    if (god.sees(p)) god.worldEvent(p.getGameProfile().name() + " died (" + message + ")", true);
                }
                for (HostedMind mind : m.hostedOf(p.getUUID())) {
                    if (mind instanceof BuilderMind) mind.worldEvent("your owner died (" + message + ")", true);
                }
                return;
            }
            for (GodMind god : m.gods()) {
                god.fireRules(new GodRules.Event("entity_death", killer(source.getEntity()), level, entity.position(), "", Names.id(entity.getType()), message));
            }
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (!(entity instanceof ServerPlayer p) || p instanceof CompanionPlayer || damageTaken <= 0) return;
            Entity attacker = source.getEntity();
            String from = attacker != null ? Names.entity(attacker) : source.getMsgId();
            for (GodMind god : gods()) {
                god.fireRules(new GodRules.Event("damage", p, Compat.level(p), p.position(), "", attacker == null ? "" : Names.id(attacker.getType()), from));
                if (god.sees(p)) {
                    god.worldEvent(p.getGameProfile().name() + " took " + String.format(Locale.ROOT, "%.1f", damageTaken) + " damage from " + from
                            + "; HP now " + Math.round(p.getHealth()) + "/20", damageTaken >= DAMAGE_WAKE);
                }
            }
        });
        EntitySleepEvents.START_SLEEPING.register((entity, pos) -> {
            if (!(entity instanceof ServerPlayer p) || p instanceof CompanionPlayer) return;
            for (GodMind god : gods()) {
                god.fireRules(new GodRules.Event("sleep", p, Compat.level(p), p.position(), "", "", ""));
                if (god.sees(p)) god.worldEvent(p.getGameProfile().name() + " went to sleep", true);
            }
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
            if (player instanceof CompanionPlayer) return;
            String text = player.getGameProfile().name() + " travelled from " + HostedFacts.dim(origin) + " to " + HostedFacts.dim(destination);
            for (GodMind god : gods()) {
                god.fireRules(new GodRules.Event("dimension_change", player, destination, player.position(), "", "", HostedFacts.dim(destination)));
                if (god.sees(player)) god.worldEvent(text, true);
            }
        });
    }

    private static java.util.List<GodMind> gods() {
        CompanionManager m = CompanionManager.get();
        return m == null ? java.util.List.of() : m.gods();
    }

    private static @org.jspecify.annotations.Nullable ServerPlayer killer(@org.jspecify.annotations.Nullable Entity e) {
        return e instanceof ServerPlayer p && !(p instanceof CompanionPlayer) ? p : null;
    }
}
