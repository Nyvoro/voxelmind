package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.body.PathFinder;
import com.voxelmind.local.world.Cover;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** go_to: one travel leg to a player, creature, block, coordinates or compass direction. */
public final class GoToAction extends BodyAction {
    private final String targetText;
    private final boolean through;
    private Targets.Target target;
    /** With through: the cell just past the door on its far side, where the walk ends. */
    private BlockPos throughTo;
    private BlockPos lastPlanned;
    private int replanTimer;
    private double startDistance;

    public GoToAction(String call, String targetText) {
        this(call, targetText, false);
    }

    public GoToAction(String call, String targetText, boolean through) {
        super("go_to", call);
        this.targetText = targetText;
        this.through = through;
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
            // Next to it, but not in a doorway the body opened: from there the walk first steps out and closes the door.
            if (startDistance <= 2.5 && nav.clearOfDoors()) {
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
            startDistance = Math.sqrt(body.blockPosition().distSqr(pt.pos()));
            if (PathFinder.isPassage(state) && through) {
                throughTo = farSide(body, pt.pos(), state);
                BlockPos goal = throughTo;
                nav.navigate(goal, p -> p.equals(goal));
            } else if (PathFinder.isPassage(state)) {
                // A doorway is a cell to stand in, not a block to stand next to: walking there means going through it.
                nav.navigate(pt.pos(), p -> p.equals(pt.pos()));
            } else {
                nav.navigateNear(pt.pos(), state.getCollisionShape(ctx.level(), pt.pos()).isEmpty() ? 1.2 : 2.5);
            }
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
            // Close enough, unless the body stands in a doorway it opened: then the route goes on past the door, where
            // stopping closes it (a route never ends in a doorway).
            if (ctx.body().distanceTo(e) <= 2.5 && nav.clearOfDoors()) {
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
                // A route never ends in a doorway; where it ends, the walk is over.
                if (d <= 3.5) done("arrived next to " + et.label() + " at " + where + routeWork());
                else {
                    lastPlanned = et.entity().blockPosition();
                    nav.navigateNear(lastPlanned, 2.0);
                }
            } else {
                // "Arrived" only for the cell itself; near a block or a cell, the result says how near.
                BlockPos goal = throughTo != null ? throughTo : ((Targets.PosTarget) target).pos();
                BlockPos at = ctx.body().blockPosition();
                String reached = throughTo != null && at.equals(goal)
                        ? "went through " + target.label() + " to " + Names.pos(goal) + " (under " + Cover.over(ctx.level(), goal) + ")"
                        : at.equals(goal) ? "arrived at " + target.label()
                        : "stopped " + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(at.distSqr(goal))) + " blocks from " + target.label();
                done(reached + "; standing at " + where + routeWork());
            }
        } else if (nav.status() == Navigator.Status.FAILED) {
            failed("could not reach " + target.label() + ": " + nav.failure() + "; standing at " + Names.pos(ctx.body().blockPosition()));
        }
    }

    /**
     * The cell just past a door or fence gate on the side the body is not on, along the axis it is walked through;
     * from inside the doorway, the side the body faces.
     */
    private static BlockPos farSide(CompanionPlayer body, BlockPos pos, BlockState state) {
        BlockPos lower = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Direction.Axis axis = facing.getAxis();
        int side = Integer.signum(body.blockPosition().get(axis) - lower.get(axis));
        Direction away = side == 0 ? Direction.fromYRot(body.getYRot())
                : Direction.fromAxisAndDirection(axis, side > 0 ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE);
        if (away.getAxis() != axis) away = facing;
        return lower.relative(away);
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
