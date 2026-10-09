package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.body.PathFinder;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;

import java.util.Map;

/**
 * dig: a straight shaft down or a two-high tunnel forward. Stops before lava,
 * open drops and water, and reports how far it got.
 */
public final class DigAction extends BodyAction {
    private final String direction;
    private final int distance;
    private Direction forward;
    private BlockPos origin;
    private int dug;
    private int step;
    private BlockBreaker breaker;
    private BlockPos walkTarget;
    private Map<Item, Integer> before;
    private int blocksBroken;

    public DigAction(String call, String direction, int distance) {
        super("dig", call);
        this.direction = Names.normalize(direction);
        this.distance = distance;
    }

    @Override
    protected void onStart(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        origin = body.blockPosition();
        before = Inv.snapshot(body);
        switch (direction) {
            case "down" -> forward = Direction.DOWN;
            case "forward" -> forward = Direction.fromYRot(body.getYRot());
            case "up" -> {
                failed("digging straight up is not available; place_block under_feet builds a pillar instead");
                return;
            }
            default -> {
                failed("direction must be down or forward");
                return;
            }
        }
    }

    private int target() {
        if (forward == Direction.DOWN) return distance <= 0 ? 10 : Math.min(distance, 64);
        return distance <= 0 ? 8 : Math.min(distance, 24);
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        ServerLevel level = ctx.level();
        if (breaker != null) {
            BlockBreaker.State s = breaker.tick();
            if (s == BlockBreaker.State.DONE || s == BlockBreaker.State.FAILED) {
                if (s == BlockBreaker.State.FAILED && !level.getBlockState(breaker.target()).isAir()) {
                    finish(ctx, "stopped: " + breaker.failure());
                    return;
                }
                blocksBroken++;
                breaker = null;
            }
            return;
        }
        if (walkTarget != null) {
            if (nav.tick() == Navigator.Status.MOVING) return;
            if (nav.status() == Navigator.Status.FAILED && !body.blockPosition().equals(walkTarget)) {
                finish(ctx, "stopped: could not step into the tunnel (" + nav.failure() + ")");
                return;
            }
            walkTarget = null;
        }
        // Progress is the distance actually covered; picking up drops can also carry the body forward.
        dug = forward == Direction.DOWN ? origin.getY() - body.blockPosition().getY()
                : (body.blockPosition().getX() - origin.getX()) * forward.getStepX() + (body.blockPosition().getZ() - origin.getZ()) * forward.getStepZ();
        if (dug >= target()) {
            finish(ctx, null);
            return;
        }
        BlockPos feet = body.blockPosition();
        if (forward == Direction.DOWN) {
            BlockPos below = feet.below();
            String hazard = hazardBelow(level, below);
            if (hazard != null) {
                finish(ctx, "stopped: " + hazard);
                return;
            }
            if (!level.getBlockState(below).isAir() && !level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                step = 0;
                if (body.onGround()) breaker = new BlockBreaker(ctx, nav, below);
                return;
            }
            if (++step > 60) finish(ctx, "stopped: not falling into the shaft at " + Names.pos(below));
            return;
        }
        BlockPos nextFeet = feet.relative(forward);
        BlockPos nextHead = nextFeet.above();
        for (BlockPos cell : new BlockPos[]{nextFeet, nextHead, nextHead.above(), nextFeet.relative(forward)}) {
            if (level.getFluidState(cell).is(FluidTags.LAVA)) {
                finish(ctx, "stopped: lava ahead at " + Names.pos(cell));
                return;
            }
        }
        if (!level.getBlockState(nextHead).getCollisionShape(level, nextHead).isEmpty()) {
            breaker = new BlockBreaker(ctx, nav, nextHead);
            return;
        }
        if (!level.getBlockState(nextFeet).getCollisionShape(level, nextFeet).isEmpty()) {
            breaker = new BlockBreaker(ctx, nav, nextFeet);
            return;
        }
        if (!PathFinder.isFloor(level, nextFeet.below()) && !PathFinder.isWater(level, nextFeet)) {
            finish(ctx, "stopped: no floor ahead at " + Names.pos(nextFeet.below()));
            return;
        }
        walkTarget = nextFeet;
        nav.navigate(nextFeet, p -> p.equals(nextFeet));
    }

    private static String hazardBelow(ServerLevel level, BlockPos below) {
        if (below.getY() <= level.getMinY()) return "reached the bottom of the world";
        for (int k = 0; k <= 3; k++) {
            BlockPos cell = below.below(k);
            if (level.getFluidState(cell).is(FluidTags.LAVA)) return "lava " + (k + 1) + " below at " + Names.pos(cell);
            if (level.getFluidState(cell).is(FluidTags.WATER)) return "water " + (k + 1) + " below at " + Names.pos(cell);
        }
        int air = 0;
        for (int k = 1; k <= 4; k++) {
            BlockPos cell = below.below(k);
            if (level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) air++;
            else break;
        }
        if (air >= 4) return "a drop of 4+ blocks below " + Names.pos(below);
        return null;
    }

    private void finish(ActionContext ctx, String stopReason) {
        CompanionPlayer body = ctx.body();
        int moved = forward == Direction.DOWN ? origin.getY() - body.blockPosition().getY()
                : (int) Math.round(Math.sqrt(origin.distSqr(body.blockPosition())));
        String gained = Inv.describe(Inv.gained(before, Inv.snapshot(body)));
        String text = "dug " + (forward == Direction.DOWN ? "down " : forward.getName() + " ") + moved + " of " + target()
                + " blocks, broke " + blocksBroken + " blocks, now at " + Names.pos(body.blockPosition()) + "; inventory gained " + gained;
        if (stopReason == null) done(text);
        else if (moved > 0) done(text + "; " + stopReason);
        else failed(text + "; " + stopReason);
    }

    @Override
    public String progress() {
        return forward == null ? "" : "digging " + (forward == Direction.DOWN ? "down" : forward.getName()) + ", " + dug + "/" + target();
    }
}
