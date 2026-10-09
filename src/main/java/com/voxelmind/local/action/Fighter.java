package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/** Closes in on one living target and hits it whenever the attack is fully charged. */
final class Fighter {
    enum State { ENGAGING, KILLED, LOST, UNREACHABLE }

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

        double distance = body.distanceTo(target);
        Moves.lookAt(body, target.getBoundingBox().getCenter());
        double reach = body.entityInteractionRange() - 0.3;
        if (distance <= reach) {
            nav.stop();
            body.motor.forward = distance > 1.8 ? 0.4f : 0f;
            ticksWithoutProgress = 0;
            if (body.getAttackStrengthScale(0.5f) >= 0.95f && Moves.facing(body, target.position())) {
                body.attack(target);
                body.swing(InteractionHand.MAIN_HAND);
                body.resetAttackStrengthTicker();
                hits++;
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
        body.motor.sprint = distance > 6;
        return State.ENGAGING;
    }
}
