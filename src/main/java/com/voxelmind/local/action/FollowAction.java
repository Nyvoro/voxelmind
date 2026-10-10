package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/** follow: stays close to a moving target for about a minute, then reports so the model decides again. */
public final class FollowAction extends BodyAction {
    public static final int DURATION_TICKS = 20 * 60;
    private static final double NEAR = 3.0;
    private static final double TELEPORT = 40.0;

    private final String targetText;
    private Entity entity;
    private String label = "";
    private BlockPos lastPlanned = BlockPos.ZERO;
    private int replanTimer;
    private int teleports;

    public FollowAction(String call, String targetText) {
        super("follow", call);
        this.targetText = targetText;
    }

    @Override
    protected void onStart(ActionContext ctx) {
        Targets.Resolution res = Targets.resolve(ctx.body(), ctx.ownerId(), targetText, false, false);
        if (!(res.target() instanceof Targets.EntityTarget et)) {
            failed("cannot follow '" + targetText + "': " + (res.target() == null ? res.error() : "it is not a player or creature"));
            return;
        }
        entity = et.entity();
        label = et.label();
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        if (!entity.isAlive() || entity.isRemoved()) {
            failed("lost " + label + ": no longer in the world");
            return;
        }
        if (entity.level() != body.level()) {
            failed("lost " + label + ": they went to another dimension");
            return;
        }
        if (ticks >= DURATION_TICKS) {
            done("followed " + label + " for about a minute; now " + Math.round(body.distanceTo(entity)) + "m apart at "
                    + Names.pos(body.blockPosition()));
            return;
        }
        double distance = body.distanceTo(entity);
        boolean owner = entity.getUUID().equals(ctx.ownerId());
        if (distance > TELEPORT && owner) {
            if (Moves.teleportNear(body, entity)) {
                teleports++;
                nav.stop();
                return;
            }
        }
        // Close enough, unless the body stands in a doorway it opened: then it walks on past the door, where it closes.
        if (distance <= NEAR && nav.clearOfDoors()) {
            nav.stop();
            Moves.lookAt(body, entity.getEyePosition());
            return;
        }
        body.motor.sprint = distance > 8;
        if (nav.status() != com.voxelmind.local.body.Navigator.Status.MOVING || (++replanTimer >= 15 && entity.blockPosition().distSqr(lastPlanned) > 4)) {
            replanTimer = 0;
            lastPlanned = entity.blockPosition();
            nav.navigateNear(lastPlanned, NEAR - 0.5);
        }
        nav.tick();
        body.motor.sprint = distance > 8;
    }

    @Override
    public String progress() {
        if (ctx == null || entity == null) return "";
        return "following " + label + " for " + (ticks / 20) + "s, " + Math.round(ctx.body().distanceTo(entity)) + "m apart"
                + (teleports > 0 ? ", teleported " + teleports + "×" : "");
    }
}
