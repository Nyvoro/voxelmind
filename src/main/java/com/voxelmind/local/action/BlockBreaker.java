package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Hands;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Breaks one block the way a player does: walk into reach, look at it, mine for
 * the real break time with the best carried tool, then pick up what dropped.
 * Blocks between the eye and the target are mined first and reported.
 */
final class BlockBreaker {
    enum State { APPROACH, BREAK, COLLECT, DONE, FAILED }

    static final double REACH = 4.2;
    private static final int MAX_OBSTRUCTIONS = 6;
    private static final int COLLECT_TICKS = 50;

    private final ActionContext ctx;
    private final Navigator nav;
    private final BlockPos target;
    private final String targetName;
    private BlockPos current;
    private State state = State.APPROACH;
    private String failure = "";
    private Hands.Breaker hand;
    private int collectTicks;
    private boolean approachPlanned;
    private final List<String> brokeInWay = new ArrayList<>();
    private Map<Item, Integer> before;
    private Map<Item, Integer> gained = Map.of();
    private boolean dropped = true;
    private String brokenName = "";

    BlockBreaker(ActionContext ctx, Navigator nav, BlockPos target) {
        this.ctx = ctx;
        this.nav = nav;
        this.target = target;
        this.current = target;
        this.targetName = Names.id(ctx.level().getBlockState(target).getBlock());
        this.before = Inv.snapshot(ctx.body());
    }

    State state() {
        return state;
    }

    String failure() {
        return failure;
    }

    BlockPos target() {
        return target;
    }

    List<String> brokeInWay() {
        return brokeInWay;
    }

    Map<Item, Integer> gained() {
        return gained;
    }

    State tick() {
        switch (state) {
            case APPROACH -> approach();
            case BREAK -> breakTick();
            case COLLECT -> collect();
            default -> {
            }
        }
        return state;
    }

    private void approach() {
        CompanionPlayer body = ctx.body();
        if (ctx.level().getBlockState(target).isAir()) {
            fail(targetName + " at " + Names.pos(target) + " is already gone");
            return;
        }
        if (Moves.eyeDistance(nav.feet(), target) <= REACH) {
            nav.stop();
            state = State.BREAK;
            return;
        }
        if (!approachPlanned) {
            approachPlanned = true;
            nav.navigate(target, p -> Moves.eyeDistance(p, target) <= REACH - 0.3 && !p.equals(target) && !p.above().equals(target));
        }
        if (nav.tick() == Navigator.Status.FAILED) {
            fail("could not get within reach of " + targetName + " at " + Names.pos(target) + ": " + nav.failure());
        } else if (nav.status() == Navigator.Status.ARRIVED) {
            state = State.BREAK;
        }
        body.motor.sprint = false;
    }

    private void breakTick() {
        CompanionPlayer body = ctx.body();
        ServerLevel level = ctx.level();
        BlockState state = level.getBlockState(current);
        if (state.isAir() || state.getCollisionShape(level, current).isEmpty() && !state.getFluidState().isEmpty()) {
            if (current.equals(target)) {
                fail(targetName + " at " + Names.pos(target) + " is already gone");
                return;
            }
            current = target;
            hand = null;
            return;
        }
        if (Moves.eyeDistance(nav.feet(), current) > REACH + 0.6) {
            state(State.APPROACH);
            approachPlanned = false;
            hand = null;
            return;
        }
        if (current.equals(target) && hand == null) {
            BlockPos obstruction = obstruction(body, target);
            if (obstruction != null) {
                if (brokeInWay.size() >= MAX_OBSTRUCTIONS) {
                    fail("too many blocks between you and " + targetName + " at " + Names.pos(target));
                    return;
                }
                current = obstruction;
            }
        }
        if (hand == null || !hand.pos().equals(current)) hand = new Hands.Breaker(body, current);
        if (!hand.tick()) return;
        Hands.Breaker done = hand;
        hand = null;
        if (done.failed()) {
            fail(done.failure());
            return;
        }
        if (!current.equals(target)) {
            brokeInWay.add(done.brokenName());
            current = target;
            return;
        }
        brokenName = done.brokenName();
        dropped = done.dropped();
        state(State.COLLECT);
    }

    private void collect() {
        CompanionPlayer body = ctx.body();
        if (++collectTicks > COLLECT_TICKS) {
            finishCollect();
            return;
        }
        List<ItemEntity> items = ctx.level().getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(4.0),
                e -> e.isAlive() && !e.hasPickUpDelay() || e.isAlive() && e.getAge() < 40);
        if (items.isEmpty()) {
            if (collectTicks > 12) finishCollect();
            return;
        }
        ItemEntity nearest = items.stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElseThrow();
        if (body.distanceToSqr(nearest) < 1.0) return;
        if (nav.status() != Navigator.Status.MOVING || nav.target().distSqr(nearest.blockPosition()) > 1) {
            // Picking up never digs: drops that land in rock stay there.
            nav.allowDig(false);
            nav.navigateNear(nearest.blockPosition(), 0.9);
        }
        nav.tick();
    }

    private void finishCollect() {
        nav.stop();
        nav.allowDig(true);
        gained = Inv.gained(before, Inv.snapshot(ctx.body()));
        state(State.DONE);
    }

    private void state(State s) {
        this.state = s;
    }

    private void fail(String why) {
        nav.stop();
        failure = why;
        state = State.FAILED;
    }

    /** One-line result for the model. */
    String describe() {
        StringBuilder out = new StringBuilder();
        out.append("broke ").append(brokenName.isEmpty() ? targetName : brokenName).append(" at ").append(Names.pos(target));
        if (!brokeInWay.isEmpty()) out.append(" (first broke ").append(String.join(", ", brokeInWay)).append(" in the way)");
        if (!gained.isEmpty()) out.append("; inventory gained ").append(Inv.describe(gained));
        else if (!dropped) out.append("; no drop — this block needs a better tool to drop anything");
        else out.append("; nothing picked up");
        return out.toString();
    }

    private static BlockPos obstruction(CompanionPlayer body, BlockPos target) {
        Vec3 eye = body.getEyePosition();
        BlockHitResult hit = body.level().clip(new ClipContext(eye, Vec3.atCenterOf(target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, body));
        if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(target)) return hit.getBlockPos();
        return null;
    }
}
