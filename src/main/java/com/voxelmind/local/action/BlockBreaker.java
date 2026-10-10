package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Hands;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Placed;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
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
import org.jspecify.annotations.Nullable;

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

    /** A broken block that a player had placed: its id and who placed it. */
    record PlacedBlock(String block, String placer) {}

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
    /** Who placed the block the hand is on, looked up before it breaks; null when no player placed it. */
    private @Nullable String handPlacer;
    private @Nullable String brokenPlacer;
    private int broken;
    private final List<PlacedBlock> brokePlaced = new ArrayList<>();

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

    /** Blocks the hand actually broke: the ones in the way and the target. */
    int broken() {
        return broken;
    }

    /** The broken blocks a player had placed. */
    List<PlacedBlock> brokePlaced() {
        return brokePlaced;
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
            // The other half of a door, bed or tall plant that is the target goes with the target itself.
            if (obstruction != null && !obstruction.equals(Placed.partner(level.getBlockState(target), target))) {
                // Only the target is broken whatever it is (it was chosen); in its way only what no player placed and no
                // door, gate or trapdoor.
                BlockState in = level.getBlockState(obstruction);
                String placer = Placed.placer(level, obstruction);
                if (placer != null || opens(in)) {
                    fail(Names.id(in.getBlock()) + " at " + Names.pos(obstruction) + (placer == null ? "" : ", placed by " + placer + ",")
                            + " is in the way to " + targetName + " at " + Names.pos(target) + "; blocks in the way are mined only when no "
                            + "player placed them and they are no door, gate or trapdoor");
                    return;
                }
                if (brokeInWay.size() >= MAX_OBSTRUCTIONS) {
                    fail("too many blocks between you and " + targetName + " at " + Names.pos(target));
                    return;
                }
                current = obstruction;
            }
        }
        if (hand == null || !hand.pos().equals(current)) {
            hand = new Hands.Breaker(body, current);
            handPlacer = Placed.placer(level, current);
        }
        if (!hand.tick()) return;
        Hands.Breaker done = hand;
        hand = null;
        if (done.failed()) {
            fail(done.failure());
            return;
        }
        // An empty name: the block was gone before the hand broke it.
        String placer = done.brokenName().isEmpty() ? null : handPlacer;
        if (!done.brokenName().isEmpty()) broken++;
        if (placer != null) brokePlaced.add(new PlacedBlock(done.brokenName(), placer));
        if (!current.equals(target)) {
            brokeInWay.add(done.brokenName() + (placer == null ? "" : " placed by " + placer));
            current = target;
            return;
        }
        brokenName = done.brokenName();
        brokenPlacer = placer;
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
        if (brokenPlacer != null) out.append(", placed by ").append(brokenPlacer);
        if (!brokeInWay.isEmpty()) out.append(" (first broke ").append(String.join(", ", brokeInWay)).append(" in the way)");
        if (!gained.isEmpty()) out.append("; inventory gained ").append(Inv.describe(gained));
        else if (!dropped) out.append("; no drop — this block needs a better tool to drop anything");
        else out.append("; nothing picked up");
        return out.toString();
    }

    /** Doors, fence gates and trapdoors: never mined because they stand in the way. */
    private static boolean opens(BlockState state) {
        return state.is(BlockTags.DOORS) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.TRAPDOORS);
    }

    private static BlockPos obstruction(CompanionPlayer body, BlockPos target) {
        Vec3 eye = body.getEyePosition();
        BlockHitResult hit = body.level().clip(new ClipContext(eye, Vec3.atCenterOf(target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, body));
        if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(target)) return hit.getBlockPos();
        return null;
    }
}
