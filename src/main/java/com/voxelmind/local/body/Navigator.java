package com.voxelmind.local.body;

import com.voxelmind.local.compat.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Walks the body along {@link PathFinder} paths. When no walking route exists it
 * plans through natural terrain, mines the cells in the way and builds pillar
 * steps with carried blocks. Re-plans when stuck or when a partial path runs out.
 */
public final class Navigator {
    public enum Status { IDLE, MOVING, ARRIVED, FAILED }

    private static final int NODE_BUDGET = 6000;
    private static final int DIG_NODE_BUDGET = 9000;
    private static final int MAX_LEGS = 12;
    private static final int MAX_REPLANS = 6;
    private static final double DIG_RANGE = 64;
    /**
     * A search that found no route is not repeated for the same start and target within this many ticks: callers
     * that follow or chase re-plan every tick, and a failed search (walk plus dig budget) costs a large part of a
     * tick. A technical bound on server-thread work; the caller still sees FAILED with the same reason.
     */
    private static final int NO_ROUTE_BACKOFF_TICKS = 20;
    /** Within one action a followed or chased target moves a little every tick. */
    private static final int NO_ROUTE_TARGET_SLACK = 8;

    private final CompanionPlayer body;
    private @Nullable List<BlockPos> path;
    private int index;
    private boolean pathReachesGoal;
    private BlockPos target = BlockPos.ZERO;
    private Predicate<BlockPos> goal = p -> false;
    private Status status = Status.IDLE;
    private String failure = "";
    private int legs;
    private int replans;
    private int ticksSinceCheck;
    private Vec3 checkpoint = Vec3.ZERO;
    private int jumpCooldown;
    private boolean allowDig = true;
    private @Nullable Predicate<BlockPos> protect;
    private Hands.@Nullable Breaker breaker;
    private boolean pillarJumped;
    private int pillarTicks;
    private int dug;
    private int placed;
    private @Nullable BlockPos noRouteFrom;
    private @Nullable BlockPos noRouteTo;
    private long noRouteAt;

    public Navigator(CompanionPlayer body) {
        this.body = body;
    }

    public Status status() {
        return status;
    }

    public String failure() {
        return failure;
    }

    public BlockPos target() {
        return target;
    }

    /** Blocks mined and placed for routes since this navigator was created. */
    public int dug() {
        return dug;
    }

    public int placed() {
        return placed;
    }

    public Navigator allowDig(boolean allow) {
        this.allowDig = allow;
        return this;
    }

    /** Cells the route must never dig through, e.g. a build in progress. */
    public Navigator protect(@Nullable Predicate<BlockPos> cells) {
        this.protect = cells;
        return this;
    }

    /** Plans a route toward {@code target}; arrival is when {@code goal} accepts the body's standing block. */
    public Status navigate(BlockPos target, Predicate<BlockPos> goal) {
        this.target = target;
        this.goal = goal;
        this.legs = 0;
        this.replans = 0;
        long now = Compat.level(body).getGameTime();
        if (noRouteTo != null && noRouteFrom != null && now - noRouteAt < NO_ROUTE_BACKOFF_TICKS
                && noRouteTo.distManhattan(target) <= NO_ROUTE_TARGET_SLACK && noRouteFrom.distManhattan(feet()) <= 2) {
            stop();
            status = Status.FAILED;
            return status;
        }
        return plan();
    }

    public Status navigateNear(BlockPos target, double radius) {
        double r2 = radius * radius;
        return navigate(target, p -> p.distToCenterSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) <= r2);
    }

    private Status plan() {
        ServerLevel level = Compat.level(body);
        BlockPos start = feet();
        breaker = null;
        pillarJumped = false;
        if (goal.test(start)) return arrive();
        PathFinder.Result result = PathFinder.find(level, start, target, goal, NODE_BUDGET);
        if (!result.reachesGoal() && allowDig && Math.sqrt(start.distSqr(target)) <= DIG_RANGE) {
            PathFinder.Mode mode = new PathFinder.Mode(true, Hands.scaffoldCount(body) > 0, body, protect);
            PathFinder.Result digging = PathFinder.find(level, start, target, goal, DIG_NODE_BUDGET, mode);
            if (digging.reachesGoal() || result.path().size() < 2) result = digging;
        }
        if (result.isEmpty() || result.path().size() < 2) {
            noRouteFrom = start;
            noRouteTo = target;
            noRouteAt = level.getGameTime();
            return fail("no route from " + fmt(start) + " toward " + fmt(target) + (allowDig ? " (not even by digging)" : ""));
        }
        path = result.path();
        pathReachesGoal = result.reachesGoal();
        index = 1;
        status = Status.MOVING;
        ticksSinceCheck = 0;
        checkpoint = body.position();
        return status;
    }

    public void stop() {
        path = null;
        breaker = null;
        status = Status.IDLE;
        body.motor.forward = 0;
        body.motor.strafe = 0;
        body.motor.jump = false;
        body.motor.sprint = false;
    }

    public Status tick() {
        if (status != Status.MOVING || path == null) return status;
        ServerLevel level = Compat.level(body);
        CompanionPlayer.Motor motor = body.motor;
        Vec3 pos = body.position();

        while (index < path.size()) {
            BlockPos node = path.get(index);
            BlockPos before = path.get(index - 1);
            double dx = node.getX() + 0.5 - pos.x;
            double dz = node.getZ() + 0.5 - pos.z;
            boolean sameLevel = Math.abs(pos.y - node.getY()) < 0.9 || (body.isInWater() && Math.abs(pos.y - node.getY()) < 1.6);
            // A pillar step only counts once the body stands on the new block, not at the top of the jump.
            boolean pillarStep = node.getX() == before.getX() && node.getZ() == before.getZ() && node.getY() > before.getY();
            if (pillarStep && !(body.onGround() && feet().getY() >= node.getY())) break;
            if (dx * dx + dz * dz < 0.35 * 0.35 && sameLevel) {
                index++;
                pillarJumped = false;
            } else {
                break;
            }
        }
        if (index >= path.size()) {
            if (goal.test(feet())) return arrive();
            if (!pathReachesGoal && ++legs < MAX_LEGS) return plan();
            if (++replans <= MAX_REPLANS) return plan();
            return fail("could not get closer than " + fmt(feet()) + " to " + fmt(target));
        }

        BlockPos node = path.get(index);
        BlockPos prev = path.get(index - 1);

        // Clear the cells this step passes through.
        for (BlockPos cell : cellsToClear(prev, node)) {
            if (PathFinder.passable(level, cell)) continue;
            if (!Hands.isNaturalTerrain(level.getBlockState(cell)) || !Hands.dryToBreak(level, cell) || (protect != null && protect.test(cell))) {
                if (++replans > MAX_REPLANS) return fail(level.getBlockState(cell).getBlock().getName().getString() + " blocks the way at " + fmt(cell));
                return plan();
            }
            motor.forward = 0;
            motor.strafe = 0;
            motor.jump = false;
            if (breaker == null || !breaker.pos().equals(cell)) breaker = new Hands.Breaker(body, cell);
            if (breaker.tick()) {
                if (breaker.failed()) return fail(breaker.failure());
                dug++;
                breaker = null;
            }
            ticksSinceCheck = 0;
            checkpoint = pos;
            return status;
        }
        breaker = null;

        // Pillar step: jump and place a block where the feet were.
        if (node.getX() == prev.getX() && node.getZ() == prev.getZ() && node.getY() == prev.getY() + 1) {
            return pillar(level, prev);
        }
        // Dig-down step: the cell is open now; wait to fall into it.
        if (node.getX() == prev.getX() && node.getZ() == prev.getZ() && node.getY() < prev.getY()) {
            motor.forward = 0;
            motor.jump = false;
            ticksSinceCheck = 0;
            checkpoint = pos;
            return status;
        }

        double dx = node.getX() + 0.5 - pos.x;
        double dz = node.getZ() + 0.5 - pos.z;
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        motor.targetYaw = yaw;
        motor.targetPitch = 0f;
        float turn = Math.abs(Mth.wrapDegrees(yaw - body.getYRot()));
        motor.forward = turn < 50 ? 1.0f : 0.15f;
        motor.strafe = 0;

        boolean wantsUp = node.getY() > pos.y + 0.5;
        if (body.isInWater()) {
            motor.jump = node.getY() >= Math.floor(pos.y) - 0.2;
        } else if (jumpCooldown > 0) {
            jumpCooldown--;
            motor.jump = false;
        } else if (body.onGround() && (wantsUp || (body.horizontalCollision && turn < 50))) {
            motor.jump = true;
            jumpCooldown = 4;
        } else {
            motor.jump = false;
        }

        if (++ticksSinceCheck >= 25) {
            double moved = pos.distanceTo(checkpoint);
            ticksSinceCheck = 0;
            checkpoint = pos;
            if (moved < 0.4) {
                if (++replans > MAX_REPLANS) return fail("stuck near " + fmt(feet()) + " on the way to " + fmt(target));
                return plan();
            }
        }
        return status;
    }

    private Status pillar(ServerLevel level, BlockPos prev) {
        CompanionPlayer.Motor motor = body.motor;
        Vec3 pos = body.position();
        double dx = prev.getX() + 0.5 - pos.x;
        double dz = prev.getZ() + 0.5 - pos.z;
        motor.targetPitch = 90f;
        if (dx * dx + dz * dz > 0.04 && body.onGround() && !pillarJumped) {
            motor.targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            motor.forward = 0.3f;
            return status;
        }
        motor.forward = 0;
        ticksSinceCheck = 0;
        checkpoint = pos;
        if (!pillarJumped) {
            if (!body.onGround()) return status;
            Item scaffold = Hands.scaffoldItem(body);
            if (scaffold == null) return fail("no blocks left to build up with near " + fmt(prev));
            motor.jump = true;
            pillarJumped = true;
            pillarTicks = 0;
            return status;
        }
        motor.jump = false;
        pillarTicks++;
        if (pos.y >= prev.getY() + 1.0) {
            Item scaffold = Hands.scaffoldItem(body);
            if (scaffold == null) return fail("no blocks left to build up with near " + fmt(prev));
            if (Hands.place(body, level, scaffold, prev)) placed++;
        }
        if (pillarTicks > 20 && body.onGround()) {
            pillarJumped = false;
            if (++replans > MAX_REPLANS * 2) return fail("could not build up at " + fmt(prev));
        }
        return status;
    }

    /** Cells that must be open for the body to move from {@code prev} to {@code node}. */
    static List<BlockPos> cellsToClear(BlockPos prev, BlockPos node) {
        List<BlockPos> cells = new ArrayList<>(4);
        int dy = node.getY() - prev.getY();
        if (node.getX() == prev.getX() && node.getZ() == prev.getZ()) {
            if (dy > 0) cells.add(prev.above(2));
            else if (dy < 0) cells.add(prev.below());
            return cells;
        }
        if (dy == 0) {
            cells.add(node.above());
            cells.add(node);
        } else if (dy > 0) {
            cells.add(prev.above(2));
            cells.add(node.above());
            cells.add(node);
        } else {
            BlockPos side = new BlockPos(node.getX(), prev.getY(), node.getZ());
            cells.add(side.above());
            for (BlockPos cell = side; cell.getY() >= node.getY(); cell = cell.below()) cells.add(cell);
        }
        return cells;
    }

    private Status arrive() {
        stop();
        status = Status.ARRIVED;
        return status;
    }

    private Status fail(String why) {
        stop();
        failure = why;
        status = Status.FAILED;
        return status;
    }

    /** The block the body's feet are in. */
    public BlockPos feet() {
        return BlockPos.containing(body.getX(), body.getY() + 0.05, body.getZ());
    }

    public static String fmt(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
