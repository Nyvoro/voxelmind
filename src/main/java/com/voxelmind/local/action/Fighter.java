package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.body.PathFinder;
import com.voxelmind.local.compat.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Fights one living target the way a practised player melees: closes in only while beyond reach and holds at reach
 * inside it; hits when the attack is fully charged, sprinting into that hit for the sprint knockback; and while the
 * attack recharges steps back out of the target's own melee range, as a player holding S, where the ground behind is
 * safe (no drop of more than two blocks, no water, lava or fire). A creeper is fought like any other target.
 */
final class Fighter {
    enum State { ENGAGING, KILLED, LOST, UNREACHABLE }

    /** How far a mob's melee hit reaches beyond its own box (vanilla Mob.DEFAULT_ATTACK_REACH). */
    private static final double MOB_REACH = Math.sqrt(2.04) - 0.6;
    /** Distance kept beyond the target's own melee range while the attack recharges. */
    private static final double MARGIN = 0.5;
    /** How far behind the body the ground is checked before it steps back. */
    private static final double STEP_BACK = 0.9;

    private final ActionContext ctx;
    private final Navigator nav;
    private final LivingEntity target;
    private int hits;
    private int replan;
    private int ticksWithoutProgress;
    private double closest = Double.MAX_VALUE;

    Fighter(ActionContext ctx, Navigator nav, LivingEntity target) {
        this.ctx = ctx;
        this.nav = nav;
        this.target = target;
    }

    LivingEntity target() {
        return target;
    }

    int hits() {
        return hits;
    }

    State tick() {
        CompanionPlayer body = ctx.body();
        if (target.isDeadOrDying() || !target.isAlive() && !target.isRemoved()) return State.KILLED;
        if (target.isRemoved()) return target.isDeadOrDying() ? State.KILLED : State.LOST;
        if (target.level() != body.level() || body.distanceTo(target) > 48) return State.LOST;

        CompanionPlayer.Motor motor = body.motor;
        double distance = body.distanceTo(target);
        Moves.lookAt(body, target.getBoundingBox().getCenter());
        double reach = body.entityInteractionRange() - 0.3;
        boolean charged = body.getAttackStrengthScale(0.5f) >= 0.95f;
        boolean canSprint = body.getFoodData().getFoodLevel() > 6;
        boolean facing = Moves.facing(body, target.position());
        if (!charged && inItsReach(body)) {
            // Recharging inside the target's own melee range: step back out of it where the ground allows.
            nav.stop();
            motor.sprint = false;
            motor.forward = safeBehind(body) ? -1f : 0f;
            ticksWithoutProgress = 0;
            return State.ENGAGING;
        }
        if (distance <= reach) {
            nav.stop();
            ticksWithoutProgress = 0;
            if (!charged) {
                motor.forward = 0;
                motor.sprint = false;
            } else if (facing && (body.isSprinting() || !canSprint)) {
                body.attack(target);
                body.swing(InteractionHand.MAIN_HAND);
                body.resetAttackStrengthTicker();
                hits++;
                motor.forward = 0;
                motor.sprint = false;
            } else {
                // Into the charged hit at a sprint, as a player does for the sprint knockback.
                motor.forward = facing ? 1f : 0f;
                motor.sprint = facing && canSprint;
            }
            return State.ENGAGING;
        }
        if (distance < closest - 0.5) {
            closest = distance;
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > 200) {
            return State.UNREACHABLE;
        }
        if (nav.status() != Navigator.Status.MOVING || ++replan >= 10) {
            replan = 0;
            nav.navigateNear(target.blockPosition(), Math.max(1.0, reach - 0.8));
        }
        nav.tick();
        body.motor.sprint = distance > 6 || charged;
        return State.ENGAGING;
    }

    /** Whether the body stands within the target's own melee range plus MARGIN. */
    private boolean inItsReach(CompanionPlayer body) {
        if (target instanceof Mob mob) {
            double r = MOB_REACH + MARGIN;
            return mob.getBoundingBox().inflate(r, 0, r).intersects(body.getBoundingBox());
        }
        if (target instanceof Player player) return body.distanceTo(player) <= player.entityInteractionRange() + MARGIN;
        return false;
    }

    /**
     * Whether a step back, away from the target, lands on safe ground: room for the body, no water, and a floor at most
     * two blocks down with open, dry cells on the way (passable cells hold no lava or fire).
     */
    private boolean safeBehind(CompanionPlayer body) {
        ServerLevel level = Compat.level(body);
        Vec3 away = body.position().subtract(target.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1.0E-4) away = Vec3.directionFromRotation(0, body.getYRot()).multiply(-1, 0, -1);
        Vec3 probe = body.position().add(away.normalize().scale(STEP_BACK));
        BlockPos cell = BlockPos.containing(probe.x, body.getY() + 0.05, probe.z);
        if (!PathFinder.bodyFits(level, cell) || PathFinder.isWater(level, cell) || PathFinder.isWater(level, cell.above())) return false;
        for (int down = 1; down <= 3; down++) {
            BlockPos below = cell.below(down);
            if (PathFinder.isFloor(level, below)) return true;
            if (!PathFinder.passable(level, below) || PathFinder.isWater(level, below)) return false;
        }
        return false;
    }
}
