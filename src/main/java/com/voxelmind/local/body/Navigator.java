package com.voxelmind.local.body;

import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.world.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Walks the body along {@link PathFinder} paths. When no walking route exists it
 * plans through natural terrain, mines the cells in the way and builds pillar
 * steps with carried blocks. Re-plans when stuck or when a partial path runs out.
 * Closed doors and fence gates on the way that open by hand are opened like a
 * player opens them, and closed again once the body is past them.
 */
public final class Navigator {
    public enum Status { IDLE, MOVING, ARRIVED, FAILED }

    private static final int NODE_BUDGET = 6000;
    private static final int DIG_NODE_BUDGET = 9000;
    private static final int MAX_LEGS = 12;
    private static final int MAX_REPLANS = 6;
    private static final double DIG_RANGE = 64;
    /** A door the body opened is closed again once the body is this many blocks past it. */
    private static final double PAST_DOOR = 2.0;
    private static final int MAX_DOOR_CLICKS = 3;
    /** Right behind a doorway the body walks this close to the next cell's centre, clear of the door it closes behind it. */
    private static final double BEHIND_DOOR = 0.15;
    /**
     * Doors (their lower cell) and gates each body opened and has not closed again, by body. They outlive one walk: a
     * door the body still stood in when an action ended is closed on a later walk, once the body is past it; one the
     * body is out of reach of is forgotten.
     */
    private static final Map<UUID, List<GlobalPos>> OPENED = new HashMap<>();
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
    private @Nullable BlockPos opening;
    private int openTicks;
    private int openClicks;

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
        opening = null;
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

    /** Ends the walk; doors and gates the body opened and has left are closed again. */
    public void stop() {
        path = null;
        breaker = null;
        opening = null;
        status = Status.IDLE;
        body.motor.forward = 0;
        body.motor.strafe = 0;
        body.motor.jump = false;
        body.motor.sprint = false;
        closeDoors(true);
    }

    public Status tick() {
        if (status != Status.MOVING || path == null) return status;
        ServerLevel level = Compat.level(body);
        CompanionPlayer.Motor motor = body.motor;
        Vec3 pos = body.position();
        closeDoors(false);

        while (index < path.size()) {
            BlockPos node = path.get(index);
            BlockPos before = path.get(index - 1);
            double dx = node.getX() + 0.5 - pos.x;
            double dz = node.getZ() + 0.5 - pos.z;
            boolean sameLevel = Math.abs(pos.y - node.getY()) < 0.9 || (body.isInWater() && Math.abs(pos.y - node.getY()) < 1.6);
            // A pillar step only counts once the body stands on the new block, not at the top of the jump.
            boolean pillarStep = node.getX() == before.getX() && node.getZ() == before.getZ() && node.getY() > before.getY();
            if (pillarStep && !(body.onGround() && feet().getY() >= node.getY())) break;
            double near = PathFinder.isDoorway(level, before) ? BEHIND_DOOR : 0.35;
            if (dx * dx + dz * dz < near * near && sameLevel) {
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

        // A closed door or gate on this step is opened by hand first.
        BlockPos door = closedDoor(level, prev, node);
        if (door != null) return open(level, door);

        // Clear the cells this step passes through.
        for (BlockPos cell : cellsToClear(level, prev, node)) {
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

    /**
     * A closed door or gate the body opens by hand in the cells it is about to step into, or in the doorway it still
     * stands in; the upper half of a door first, at eye height.
     */
    private @Nullable BlockPos closedDoor(ServerLevel level, BlockPos prev, BlockPos node) {
        BlockPos[] cells = prev.equals(feet()) ? new BlockPos[]{node.above(), node, prev.above(), prev} : new BlockPos[]{node.above(), node};
        for (BlockPos cell : cells) {
            if (PathFinder.opensByHand(level.getBlockState(cell))) return cell;
        }
        return null;
    }

    /** Opens a closed door or gate like a player: turn to it, right-click it, and step on once it shows open. */
    private Status open(ServerLevel level, BlockPos door) {
        CompanionPlayer.Motor motor = body.motor;
        motor.forward = 0;
        motor.strafe = 0;
        motor.jump = false;
        ticksSinceCheck = 0;
        checkpoint = body.position();
        if (!door.equals(opening)) {
            opening = door;
            openTicks = 0;
            openClicks = 0;
        }
        BlockHitResult hit = Hands.aim(body, level, door);
        Hands.lookAt(body, hit.getLocation());
        if (!Hands.facing(body, hit.getLocation()) && ++openTicks < 10) return status;
        BlockState state = level.getBlockState(door);
        BlockPos base = base(state, door);
        if (openClicks++ >= MAX_DOOR_CLICKS || !Hands.inReach(body, door)) {
            return fail("could not open " + Names.id(state.getBlock()) + " at " + Names.pos(base));
        }
        Hands.use(body, level, hit);
        if (!PathFinder.opensByHand(level.getBlockState(door))) {
            GlobalPos opened = GlobalPos.of(level.dimension(), base);
            List<GlobalPos> doors = OPENED.computeIfAbsent(body.getUUID(), id -> new ArrayList<>());
            if (!doors.contains(opened)) doors.add(opened);
            opening = null;
            // The bump against the closed door is no step to jump up.
            jumpCooldown = 4;
        }
        return status;
    }

    /**
     * Closes the doors and gates this body opened, as they were found: once it is PAST_DOOR blocks past one, or, when a
     * walk ends, once it has left the doorway (for a gate: where the closed gate stands); never while someone else stands
     * in the door's cells. One out of reach, or in another dimension, is forgotten and stays as it is; that is checked
     * before its block is read, so a remembered door far away never loads its chunk.
     */
    private void closeDoors(boolean walkEnded) {
        List<GlobalPos> doors = OPENED.get(body.getUUID());
        if (doors == null || body.isDeadOrDying() || body.isRemoved()) return;
        ServerLevel level = Compat.level(body);
        for (Iterator<GlobalPos> it = doors.iterator(); it.hasNext(); ) {
            GlobalPos entry = it.next();
            BlockPos door = entry.pos();
            if (!entry.dimension().equals(level.dimension()) || !Hands.inReach(body, door)) {
                it.remove();
                continue;
            }
            BlockState state = level.getBlockState(door);
            boolean isDoor = state.getBlock() instanceof DoorBlock;
            if (!(isDoor || state.getBlock() instanceof FenceGateBlock) || !state.getValue(BlockStateProperties.OPEN)) {
                it.remove();    // closed by someone else, or gone: nothing to restore
                continue;
            }
            AABB cells = isDoor ? new AABB(door).expandTowards(0, 1, 0) : new AABB(door);
            // A closed door's panel stands at the edge of its cell, clear of a body still standing in the doorway.
            AABB closed = isDoor ? cells : state.setValue(BlockStateProperties.OPEN, false).getCollisionShape(level, door).bounds().move(door);
            boolean past = walkEnded ? !body.getBoundingBox().intersects(closed)
                    : body.position().distanceToSqr(Vec3.atBottomCenterOf(door)) >= PAST_DOOR * PAST_DOOR;
            if (!past) continue;
            if (!level.getEntitiesOfClass(LivingEntity.class, cells, e -> e != body && e.isAlive() && !e.isSpectator()).isEmpty()) continue;
            Hands.use(body, level, Hands.aim(body, level, isDoor ? door.above() : door));
            it.remove();
        }
        if (doors.isEmpty()) OPENED.remove(body.getUUID());
    }

    /** The body uses the door or gate at {@code pos} on purpose now: walking no longer closes it for the body. */
    public static void forget(CompanionPlayer body, ServerLevel level, BlockPos pos) {
        List<GlobalPos> doors = OPENED.get(body.getUUID());
        if (doors == null) return;
        doors.remove(GlobalPos.of(level.dimension(), base(level.getBlockState(pos), pos)));
        if (doors.isEmpty()) OPENED.remove(body.getUUID());
    }

    /** The cell a door or gate is remembered by: the lower half of a door. */
    private static BlockPos base(BlockState state, BlockPos pos) {
        return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    /** Cells the body mines open to move from {@code prev} to {@code node}; doorways on the step are walked through instead. */
    static List<BlockPos> cellsToClear(Level level, BlockPos prev, BlockPos node) {
        List<BlockPos> cells = cellsToClear(prev, node);
        cells.removeIf(cell -> PathFinder.isDoorway(level, cell));
        return cells;
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
