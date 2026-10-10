package com.voxelmind.local.body;

import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.world.Cover;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Placed;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Walks the body along {@link PathFinder} paths. When no walking route exists it
 * plans through natural terrain, mines the cells in the way and builds pillar
 * steps with carried blocks. Re-plans when stuck or when a partial path runs out.
 * Closed doors and fence gates on the way that open by hand are opened like a
 * player opens them, closed iron doors through a button or lever beside them,
 * and what the body opened is closed again once the body is past it. A door it
 * opened and could not close is named in the walk's {@link #doorFacts()}.
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
    /** Ticks a door opened through a switch may take to show open before the walk gives up on it. */
    private static final int SWITCH_WAIT_TICKS = 10;
    /** Right behind a doorway the body walks this close to the next cell's centre, clear of the door it closes behind it. */
    private static final double BEHIND_DOOR = 0.15;

    /**
     * A door (its lower cell) or gate the body opened and has not closed again: the block it was, the lever that opened an
     * iron door (flipped back instead of the door), and who last stood in it while the body walked on.
     */
    private static final class Opened {
        final GlobalPos door;
        final String block;
        final @Nullable BlockPos lever;
        @Nullable String kept;

        Opened(GlobalPos door, String block, @Nullable BlockPos lever) {
            this.door = door;
            this.block = block;
            this.lever = lever;
        }

        BlockPos handle() {
            return lever != null ? lever : door.pos();
        }
    }

    /**
     * What each body opened and has not closed again. It outlives one walk: a door the body still stood in when an action
     * ended is closed on a later walk, once the body is past it; one the body is out of reach of is forgotten.
     */
    private static final Map<UUID, List<Opened>> OPENED = new HashMap<>();
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
    /** Doors and gates this walk opened and left open, by door cell: the fact for the action's result. */
    private final Map<BlockPos, String> leftOpen = new LinkedHashMap<>();

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
        // Standing in a doorway is no arrival unless the doorway is the target: a walk never ends in a door it must close.
        if (goal.test(start) && (start.equals(target) || !PathFinder.isPassage(level.getBlockState(start)))) return arrive();
        PathFinder.Result result = PathFinder.find(level, start, target, goal, NODE_BUDGET);
        PathFinder.Blocked blocked = new PathFinder.Blocked();
        if (!result.reachesGoal() && allowDig && Math.sqrt(start.distSqr(target)) <= DIG_RANGE) {
            PathFinder.Mode mode = new PathFinder.Mode(true, Hands.scaffoldCount(body) > 0, body, protect, blocked);
            PathFinder.Result digging = PathFinder.find(level, start, target, goal, DIG_NODE_BUDGET, mode);
            if (digging.reachesGoal() || result.path().size() < 2) result = digging;
        }
        if (result.isEmpty() || result.path().size() < 2) {
            noRouteFrom = start;
            noRouteTo = target;
            noRouteAt = level.getGameTime();
            String why = !allowDig ? "" : blocked.text() == null ? " (not even by digging)"
                    : " (not even by digging; routes never dig blocks a player placed, such as the " + blocked.text() + ")";
            return fail("no route from " + fmt(start) + " toward " + fmt(target) + why);
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
            if (goal.test(feet()) && (feet().equals(target) || !PathFinder.isPassage(level.getBlockState(feet())))) return arrive();
            if (!pathReachesGoal && ++legs < MAX_LEGS) return plan();
            if (++replans <= MAX_REPLANS) return plan();
            return fail("could not get closer than " + fmt(feet()) + " to " + fmt(target));
        }

        BlockPos node = path.get(index);
        BlockPos prev = path.get(index - 1);

        // A closed door or gate on this step is opened by hand first, a closed iron door through its button or lever.
        BlockPos door = closedDoor(level, prev, node);
        if (door != null) return open(level, door, prev);

        // Clear the cells this step passes through: natural terrain and the body's own pillar steps, never another
        // block a player placed.
        for (BlockPos cell : cellsToClear(level, prev, node)) {
            if (PathFinder.passable(level, cell)) continue;
            BlockState state = level.getBlockState(cell);
            boolean ownStep = Placed.isRouteStep(level, cell, body.getUUID());
            String placer = ownStep ? null : Placed.placer(level, cell);
            if (!ownStep && !Hands.isNaturalTerrain(state) || !Hands.dryToBreak(level, cell) || (protect != null && protect.test(cell)) || placer != null) {
                if (++replans > MAX_REPLANS) {
                    return fail(state.getBlock().getName().getString() + " blocks the way at " + fmt(cell) + (placer == null ? "" : ", placed by " + placer));
                }
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
            if (Hands.place(body, level, scaffold, prev)) {
                placed++;
                // The step is the route's own: a later route of this body may dig it down again.
                Placed.markRouteStep(level, prev, body.getUUID());
            }
        }
        if (pillarTicks > 20 && body.onGround()) {
            pillarJumped = false;
            if (++replans > MAX_REPLANS * 2) return fail("could not build up at " + fmt(prev));
        }
        return status;
    }

    /**
     * A closed door or gate the body opens in the cells it is about to step into, or in the doorway it still stands in;
     * the upper half of a door first, at eye height. Closed doors that open by hand, and closed iron doors with a switch
     * on the body's side.
     */
    private @Nullable BlockPos closedDoor(ServerLevel level, BlockPos prev, BlockPos node) {
        BlockPos[] cells = prev.equals(feet()) ? new BlockPos[]{node.above(), node, prev.above(), prev} : new BlockPos[]{node.above(), node};
        for (BlockPos cell : cells) {
            BlockState state = level.getBlockState(cell);
            if (PathFinder.opensByHand(state)) return cell;
            // An iron door whose switch was just used stays this step's door until it opens or the walk gives up on it.
            if (state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN)
                    && (cell.equals(opening) || PathFinder.switchFor(level, cell, prev) != null)) return cell;
        }
        return null;
    }

    /**
     * Opens a closed door or gate like a player: turn to it, right-click it, and step on once it shows open. A closed iron
     * door is opened the same way through the button or lever beside it on the body's side ({@code from}).
     */
    private Status open(ServerLevel level, BlockPos door, BlockPos from) {
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
        BlockState state = level.getBlockState(door);
        BlockPos base = base(state, door);
        BlockPos handle = door;
        if (!PathFinder.opensByHand(state)) {
            BlockPos lever = PathFinder.switchFor(level, door, from);
            if (lever == null) {
                // The switch was pressed already (a button still down) or is gone: wait for the door, then give up.
                if (++openTicks < SWITCH_WAIT_TICKS) return status;
                return fail("could not open " + Names.id(state.getBlock()) + " at " + Names.pos(base) + ": no button or lever beside it opened it");
            }
            handle = lever;
        }
        BlockHitResult hit = Hands.aim(body, level, handle);
        Hands.lookAt(body, hit.getLocation());
        if (!Hands.facing(body, hit.getLocation()) && ++openTicks < 10) return status;
        if (openClicks++ >= MAX_DOOR_CLICKS || !Hands.inReach(body, handle)) {
            return fail("could not open " + Names.id(state.getBlock()) + " at " + Names.pos(base)
                    + (handle.equals(door) ? "" : " with the " + Names.id(level.getBlockState(handle).getBlock()) + " at " + Names.pos(handle)));
        }
        boolean lever = !handle.equals(door) && PathFinder.isLever(level.getBlockState(handle));
        Hands.use(body, level, hit);
        BlockState now = level.getBlockState(door);
        if (isOpen(now)) {
            // A button springs back and its door closes by itself; a door or gate opened by hand, or by a lever, is
            // remembered to be closed again.
            if (handle.equals(door) || lever) {
                GlobalPos opened = GlobalPos.of(level.dimension(), base);
                List<Opened> doors = OPENED.computeIfAbsent(body.getUUID(), id -> new ArrayList<>());
                if (doors.stream().noneMatch(o -> o.door.equals(opened))) {
                    doors.add(new Opened(opened, Names.id(now.getBlock()), lever ? handle.immutable() : null));
                }
            }
            opening = null;
            // The bump against the closed door is no step to jump up.
            jumpCooldown = 4;
        } else if (!handle.equals(door)) {
            openTicks = 0;
        }
        return status;
    }

    /**
     * Closes the doors and gates this body opened, as they were found: once it is PAST_DOOR blocks past one, or, when a
     * walk ends, once it has stepped out of the doorway and is clear of where the closed door stands; never while someone
     * else stands in the door's cells. One out of reach, or in another dimension, is forgotten and stays as it is; its
     * block is read only when its chunk is loaded, so a remembered door far away never loads its chunk. What stays open
     * becomes a fact of this walk ({@link #doorFacts()}).
     */
    private void closeDoors(boolean walkEnded) {
        List<Opened> doors = OPENED.get(body.getUUID());
        if (doors == null || body.isDeadOrDying() || body.isRemoved()) return;
        ServerLevel level = Compat.level(body);
        for (Iterator<Opened> it = doors.iterator(); it.hasNext(); ) {
            Opened o = it.next();
            BlockPos door = o.door.pos();
            boolean here = o.door.dimension().equals(level.dimension());
            if (!here || !Hands.inReach(body, o.handle())) {
                it.remove();
                if (!here || !level.isLoaded(door) || isOpen(level.getBlockState(door))) {
                    leftOpen.put(door, o.block + " at " + Names.pos(door) + " left open: "
                            + (o.kept != null ? o.kept + " as you walked on" : "you were moved out of its reach"));
                }
                continue;
            }
            BlockState state = level.getBlockState(door);
            boolean isDoor = state.getBlock() instanceof DoorBlock;
            if (!isOpen(state) || o.lever != null && !leverOn(level, o.lever)) {
                it.remove();    // closed by someone else, or gone: nothing to restore
                leftOpen.remove(door);
                continue;
            }
            boolean past;
            if (walkEnded) {
                String where = inTheWay(level, door, state, isDoor);
                past = where == null;
                if (!past) leftOpen.put(door, o.block + " at " + Names.pos(door) + " stays open: " + where);
            } else {
                past = body.position().distanceToSqr(Vec3.atBottomCenterOf(door)) >= PAST_DOOR * PAST_DOOR;
            }
            if (!past) continue;
            String occupant = occupant(level, door, isDoor, body);
            if (occupant != null) {
                o.kept = occupant + " stood in it";
                if (walkEnded) leftOpen.put(door, o.block + " at " + Names.pos(door) + " stays open: " + occupant + " stands in it");
                continue;
            }
            Hands.use(body, level, Hands.aim(body, level, o.lever != null ? o.lever : isDoor ? door.above() : door));
            it.remove();
            leftOpen.remove(door);
        }
        if (doors.isEmpty()) OPENED.remove(body.getUUID());
    }

    /** Where the body stands in the way of this open door closing ("you stand in its doorway"), or null when it does not. */
    private @Nullable String inTheWay(ServerLevel level, BlockPos door, BlockState state, boolean isDoor) {
        BlockPos at = feet();
        if (isDoor && (at.equals(door) || at.equals(door.above()))) return "you stand in its doorway" + Cover.sides(level, door, state);
        AABB closed = state.setValue(BlockStateProperties.OPEN, false).getCollisionShape(level, door).bounds().move(door);
        if (isDoor) closed = closed.expandTowards(0, 1, 0);
        // The closed panel stands at the edge of the door's cell, where it can miss a body still standing in the doorway.
        return body.getBoundingBox().intersects(closed) ? "you stand where it closes" : null;
    }

    /** The name of someone other than {@code body} standing in the door's cells, or null. */
    private static @Nullable String occupant(ServerLevel level, BlockPos door, boolean isDoor, CompanionPlayer body) {
        AABB cells = isDoor ? new AABB(door).expandTowards(0, 1, 0) : new AABB(door);
        List<LivingEntity> in = level.getEntitiesOfClass(LivingEntity.class, cells, e -> e != body && e.isAlive() && !e.isSpectator());
        return in.isEmpty() ? null : Names.entity(in.getFirst());
    }

    private static boolean isOpen(BlockState state) {
        return (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) && state.getValue(BlockStateProperties.OPEN);
    }

    private static boolean leverOn(ServerLevel level, BlockPos lever) {
        BlockState state = level.getBlockState(lever);
        return PathFinder.isLever(state) && state.getValue(BlockStateProperties.POWERED);
    }

    /**
     * Whether the body is clear of every door and gate it opened and can still reach: it neither stands in one's doorway
     * nor where one's closed panel stands, so a walk that stops here can close them all.
     */
    public boolean clearOfDoors() {
        List<Opened> doors = OPENED.get(body.getUUID());
        if (doors == null) return true;
        ServerLevel level = Compat.level(body);
        for (Opened o : doors) {
            BlockPos door = o.door.pos();
            if (!o.door.dimension().equals(level.dimension()) || !Hands.inReach(body, door)) continue;
            BlockState state = level.getBlockState(door);
            if (isOpen(state) && inTheWay(level, door, state, state.getBlock() instanceof DoorBlock) != null) return false;
        }
        return true;
    }

    /**
     * The doors and gates this walk opened and did not close, as a fact for the action's result: "; oak_door at (1,2,3)
     * stays open: you stand in its doorway"; empty when it closed everything it opened.
     */
    public String doorFacts() {
        return leftOpen.isEmpty() ? "" : "; " + String.join("; ", leftOpen.values());
    }

    /**
     * The body leaves the world (dismissed, its owner left, the world closes): it closes the doors, gates and levers it
     * opened and can still reach, as a player shuts the door on the way out, unless someone else stands in one. Its body
     * is gone in the same tick, so standing in the doorway does not keep a door open. Nothing stays remembered.
     */
    public static void leave(CompanionPlayer body) {
        List<Opened> doors = OPENED.remove(body.getUUID());
        if (doors == null || body.isDeadOrDying() || body.isRemoved()) return;
        ServerLevel level = Compat.level(body);
        for (Opened o : doors) {
            BlockPos door = o.door.pos();
            if (!o.door.dimension().equals(level.dimension()) || !Hands.inReach(body, o.handle())) continue;
            BlockState state = level.getBlockState(door);
            boolean isDoor = state.getBlock() instanceof DoorBlock;
            if (!isOpen(state) || o.lever != null && !leverOn(level, o.lever) || occupant(level, door, isDoor, body) != null) continue;
            Hands.use(body, level, Hands.aim(body, level, o.lever != null ? o.lever : isDoor ? door.above() : door));
        }
    }

    /** The body uses the door or gate at {@code pos} on purpose now: walking no longer closes it for the body. */
    public static void forget(CompanionPlayer body, ServerLevel level, BlockPos pos) {
        List<Opened> doors = OPENED.get(body.getUUID());
        if (doors == null) return;
        GlobalPos door = GlobalPos.of(level.dimension(), base(level.getBlockState(pos), pos));
        doors.removeIf(o -> o.door.equals(door) || o.lever != null && o.lever.equals(pos) && o.door.dimension().equals(level.dimension()));
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
