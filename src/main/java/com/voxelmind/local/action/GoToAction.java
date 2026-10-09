package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/** go_to: one travel leg to a player, creature, block, coordinates or compass direction. */
public final class GoToAction extends BodyAction {
    private final String targetText;
    private Targets.Target target;
    private BlockPos lastPlanned;
    private int replanTimer;
    private double startDistance;

    public GoToAction(String call, String targetText) {
        super("go_to", call);
        this.targetText = targetText;
    }

    @Override
    protected void onStart(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        Targets.Resolution res = Targets.resolve(body, ctx.ownerId(), targetText, true, true);
        if (res.target() == null) {
            failed("cannot go to '" + targetText + "': " + res.error());
            return;
        }
        target = res.target();
        if (target instanceof Targets.EntityTarget et) {
            Entity e = et.entity();
            if (e.level() != body.level()) {
                failed(et.label() + " is in another dimension");
                return;
            }
            startDistance = body.distanceTo(e);
            if (startDistance <= 2.5) {
                done("already next to " + et.label() + " (" + Math.round(startDistance) + "m)");
                return;
            }
            if (e.getUUID().equals(ctx.ownerId()) && startDistance > 64) {
                if (Moves.teleportNear(body, e)) {
                    done("your owner was " + Math.round(startDistance) + "m away; you teleported next to them at " + Names.pos(body.blockPosition()));
                } else {
                    failed("your owner is " + Math.round(startDistance) + "m away and there is no free spot next to them to teleport to");
                }
                return;
            }
            lastPlanned = e.blockPosition();
            nav.navigateNear(lastPlanned, 2.0);
        } else {
            Targets.PosTarget pt = (Targets.PosTarget) target;
            BlockState state = ctx.level().getBlockState(pt.pos());
            double radius = state.getCollisionShape(ctx.level(), pt.pos()).isEmpty() ? 1.2 : 2.5;
            startDistance = Math.sqrt(body.blockPosition().distSqr(pt.pos()));
            nav.navigateNear(pt.pos(), radius);
        }
        checkNav();
    }

    @Override
    protected void onTick(ActionContext ctx) {
        if (target instanceof Targets.EntityTarget et) {
            Entity e = et.entity();
            if (!e.isAlive() || e.isRemoved() || e.level() != ctx.body().level()) {
                failed(et.label() + " is gone");
                return;
            }
            if (ctx.body().distanceTo(e) <= 2.5) {
                done("arrived next to " + et.label() + " at " + Names.pos(ctx.body().blockPosition()) + routeWork());
                return;
            }
            if (++replanTimer >= 20 && e.blockPosition().distSqr(lastPlanned) > 4) {
                replanTimer = 0;
                lastPlanned = e.blockPosition();
                nav.navigateNear(lastPlanned, 2.0);
            }
        }
        nav.tick();
        checkNav();
    }

    private void checkNav() {
        if (finished()) return;
        if (nav.status() == Navigator.Status.ARRIVED) {
            String where = Names.pos(ctx.body().blockPosition());
            if (target instanceof Targets.EntityTarget et) {
                double d = ctx.body().distanceTo(et.entity());
                if (d <= 3.5) done("arrived next to " + et.label() + " at " + where + routeWork());
                else {
                    lastPlanned = et.entity().blockPosition();
                    nav.navigateNear(lastPlanned, 2.0);
                }
            } else {
                done("arrived at " + target.label() + "; standing at " + where + routeWork());
            }
        } else if (nav.status() == Navigator.Status.FAILED) {
            failed("could not reach " + target.label() + ": " + nav.failure() + "; standing at " + Names.pos(ctx.body().blockPosition()));
        }
    }

    private String routeWork() {
        if (nav.dug() == 0 && nav.placed() == 0) return "";
        return " (on the way you mined " + nav.dug() + " blocks and placed " + nav.placed() + " of your blocks)";
    }

    @Override
    public String progress() {
        if (ctx == null || target == null) return "";
        return "walking toward " + target.label() + ", now at " + Names.pos(ctx.body().blockPosition());
    }
}
