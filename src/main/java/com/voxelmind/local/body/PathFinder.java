package com.voxelmind.local.body;

import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Placed;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * A* over standing positions of a player-sized body. Walking mode: walk, diagonal
 * walk, one-block jumps, drops of up to three blocks, swimming, and straight through
 * doors and fence gates (closed ones the body opens by hand, closed iron doors through
 * a button or lever beside them). Digging mode adds mining through natural terrain and
 * the body's own pillar steps, and building one-block pillar steps with carried blocks;
 * their cost is the real break time. Blocks a player placed are never dug.
 */
public final class PathFinder {
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    public static final int MAX_DROP = 3;
    /**
     * Steps in a row with the head under water that a route may take: a body holds its breath about 15 seconds, and a
     * route that ends under water must leave the way back within the same breath, so half of it. A longer dive drowns
     * the body, the way a fall of more than MAX_DROP hurts it.
     */
    public static final int MAX_UNDER_WATER = 6;
    private static final double BLOCKED = Double.MAX_VALUE;
    /** Extra cost per closed door half or gate on a step: the body stops and opens it first. */
    private static final double CLOSED_DOOR = 1.0;

    /**
     * Walking only, or also digging/building for a given body. {@code blocked}, when given, learns the first block a
     * player placed that a digging move would have had to break.
     */
    public record Mode(boolean dig, boolean pillar, @Nullable CompanionPlayer body, @Nullable Predicate<BlockPos> protect,
                       @Nullable Blocked blocked) {
        public static final Mode WALK = new Mode(false, false, null, null, null);
    }

    /** The first block a player placed that stood in a digging search's way: what, where and who placed it. */
    public static final class Blocked {
        private @Nullable String text;

        void note(BlockState state, BlockPos pos, String placer) {
            if (text == null) text = Names.id(state.getBlock()) + " at " + Names.pos(pos) + " placed by " + placer;
        }

        public @Nullable String text() {
            return text;
        }
    }

    public record Result(List<BlockPos> path, boolean reachesGoal, int expanded) {
        public boolean isEmpty() {
            return path.isEmpty();
        }
    }

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        double g;
        double f;
        Node parent;
        boolean closed;
        /** Steps in a row up to here with the head under water. */
        int underWater;

        Node(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(f, other.f);
        }
    }

    private PathFinder() {}

    public static Result find(Level level, BlockPos start, BlockPos target, Predicate<BlockPos> goal, int maxNodes) {
        return find(level, start, target, goal, maxNodes, Mode.WALK);
    }

    /**
     * Searches from {@code start} until {@code goal} accepts a position. When the
     * node budget runs out, returns the path to the explored position closest to
     * {@code target} so the caller can walk there and search again. A doorway ends
     * a route only when it is the target itself: standing in it, the door could not
     * be closed again.
     */
    public static Result find(Level level, BlockPos start, BlockPos target, Predicate<BlockPos> goal, int maxNodes, Mode mode) {
        Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        Node first = new Node(start);
        first.g = 0;
        first.underWater = headUnderWater(level, start) ? 1 : 0;
        first.f = heuristic(start, target);
        nodes.put(start.asLong(), first);
        open.add(first);
        Node best = first;
        double bestH = first.f;
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node current = open.poll();
            if (current.closed) continue;
            current.closed = true;
            expanded++;
            if (goal.test(current.pos) && (current.pos.equals(target) || !isPassage(level.getBlockState(current.pos)))) {
                return new Result(trace(current), true, expanded);
            }
            double h = heuristic(current.pos, target);
            if (h < bestH) {
                bestH = h;
                best = current;
            }
            for (Step step : neighbours(level, current.pos, mode)) {
                Node next = nodes.get(step.pos.asLong());
                if (next == null) {
                    next = new Node(step.pos);
                    next.g = Double.MAX_VALUE;
                    nodes.put(step.pos.asLong(), next);
                }
                if (next.closed) continue;
                int underWater = headUnderWater(level, step.pos) ? current.underWater + 1 : 0;
                if (underWater > MAX_UNDER_WATER) continue;
                double g = current.g + step.cost;
                if (g < next.g) {
                    next.g = g;
                    next.f = g + heuristic(step.pos, target);
                    next.parent = current;
                    next.underWater = underWater;
                    open.add(next);
                }
            }
        }
        return new Result(best == first ? List.of() : trace(best), false, expanded);
    }

    private record Step(BlockPos pos, double cost) {}

    private static List<Step> neighbours(Level level, BlockPos pos, Mode mode) {
        List<Step> out = new ArrayList<>(16);
        boolean inWater = isWater(level, pos);
        BlockState here = level.getBlockState(pos);
        // Standing in a doorway, the only ways out lead straight on through the door's opening.
        Direction.@Nullable Axis doorway = doorAxis(here);
        for (int[] d : DIRS) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            BlockPos side = pos.offset(d[0], 0, d[1]);
            Direction.Axis axis = d[0] != 0 ? Direction.Axis.X : Direction.Axis.Z;
            if (doorway != null && (diagonal || axis != doorway)) continue;
            if (diagonal) {
                if (!bodyFits(level, pos.offset(d[0], 0, 0)) || !bodyFits(level, pos.offset(0, 0, d[1]))) continue;
                if (standable(level, side)) out.add(new Step(side, 1.414 + penalty(level, side)));
                continue;
            }
            if (standable(level, side)) {
                out.add(new Step(side, 1.0 + penalty(level, side)));
                continue;
            }
            BlockPos up = side.above();
            if (passable(level, pos.above(2)) && standable(level, up)) {
                out.add(new Step(up, 2.0 + penalty(level, up)));
                continue;
            }
            // Through a door or fence gate, level or one step up.
            double door = through(level, pos, side, axis);
            if (door < BLOCKED && isFloor(level, side.below())) {
                out.add(new Step(side, 1.0 + door));
                continue;
            }
            door = through(level, pos, up, axis);
            if (door < BLOCKED && passable(level, pos.above(2)) && isFloor(level, side)) {
                out.add(new Step(up, 2.0 + door));
                continue;
            }
            boolean dropped = false;
            if (bodyFits(level, side)) {
                for (int k = 1; k <= MAX_DROP; k++) {
                    BlockPos down = side.below(k);
                    if (standable(level, down)) {
                        out.add(new Step(down, 1.0 + 0.5 * k + penalty(level, down)));
                        dropped = true;
                        break;
                    }
                    if (!passable(level, down)) break;
                }
            }
            if (!mode.dig() || dropped || inWater) continue;
            // Digging moves: through the side cells, up a step, or down a step.
            double flat = clear(level, side, mode) + clear(level, side.above(), mode);
            if (flat < BLOCKED && isFloor(level, side.below())) {
                out.add(new Step(side, 1.0 + flat));
            } else if (flat < BLOCKED && flat > 0) {
                BlockPos landing = landing(level, side);
                if (landing != null) out.add(new Step(landing, 1.0 + flat + 0.5 * (side.getY() - landing.getY())));
            }
            double stepUp = clear(level, pos.above(2), mode) + clear(level, up, mode) + clear(level, up.above(), mode);
            if (stepUp < BLOCKED && isFloor(level, side)) {
                out.add(new Step(up, 2.0 + stepUp));
            }
            BlockPos downStep = side.below();
            double stepDown = clear(level, side.above(), mode) + clear(level, side, mode) + clear(level, downStep, mode);
            if (stepDown < BLOCKED && isFloor(level, downStep.below())) {
                out.add(new Step(downStep, 1.5 + stepDown));
            }
        }
        if (inWater) {
            BlockPos up = pos.above();
            if (passable(level, up.above()) && (isWater(level, up) || standable(level, up))) out.add(new Step(up, 1.5));
            BlockPos down = pos.below();
            if (isWater(level, down) && passable(level, down)) out.add(new Step(down, 1.5));
        }
        // A doorway is never built up in or dug down from: the door stands in it and on the block below. Nor is the cell
        // of a fence gate built up in: no block can be placed where the gate stands.
        if (mode.pillar() && !inWater && !isPassage(here) && isFloor(level, pos.below())) {
            double head = clear(level, pos.above(2), mode);
            if (head < BLOCKED) out.add(new Step(pos.above(), 3.0 + head));
        }
        if (mode.dig() && !inWater && doorway == null) {
            BlockPos down = pos.below();
            double cost = clear(level, down, mode);
            if (cost > 0 && cost < BLOCKED && !isHazardBelow(level, down)) {
                BlockPos landing = isFloor(level, down.below()) ? down : landing(level, down);
                if (landing != null) out.add(new Step(landing, 2.0 + cost + 0.5 * (down.getY() - landing.getY())));
            }
        }
        return out;
    }

    /** Cost of making a cell passable: 0 when it already is, the break effort when it may be dug, BLOCKED otherwise. */
    private static double clear(Level level, BlockPos cell, Mode mode) {
        if (passable(level, cell)) return 0;
        if (!mode.dig() || mode.body() == null || !(level instanceof ServerLevel server)) return BLOCKED;
        if (mode.protect() != null && mode.protect().test(cell)) return BLOCKED;
        BlockState state = level.getBlockState(cell);
        // Natural terrain and the body's own pillar steps only: never another block a player placed, whatever its type.
        boolean ownStep = Placed.isRouteStep(server, cell, mode.body().getUUID());
        if (!ownStep && !Hands.isNaturalTerrain(state) || !Hands.dryToBreak(server, cell)) return BLOCKED;
        if (!ownStep) {
            String placer = Placed.placer(server, cell);
            if (placer != null) {
                if (mode.blocked() != null) mode.blocked().note(state, cell, placer);
                return BLOCKED;
            }
        }
        float ticks = Hands.breakTicks(mode.body(), server, cell, state);
        if (ticks == Float.MAX_VALUE || ticks > 400) return BLOCKED;
        return 1.0 + ticks / 10.0;
    }

    /** Where a body falling from {@code from} (an open cell once cleared) lands, within MAX_DROP blocks; null otherwise. */
    private static @Nullable BlockPos landing(Level level, BlockPos from) {
        for (int k = 1; k <= MAX_DROP; k++) {
            BlockPos cell = from.below(k);
            if (!passable(level, cell)) return null;
            if (isWater(level, cell)) return cell;
            if (isFloor(level, cell.below())) return cell;
        }
        return null;
    }

    private static boolean isHazardBelow(Level level, BlockPos dug) {
        for (int k = 1; k <= 2; k++) {
            if (level.getFluidState(dug.below(k)).is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    private static double penalty(Level level, BlockPos pos) {
        return isWater(level, pos) ? 2.0 : 0.0;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.5;
    }

    private static List<BlockPos> trace(Node node) {
        List<BlockPos> path = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent) path.add(n.pos);
        Collections.reverse(path);
        return path;
    }

    /** Feet and head cells are open. */
    public static boolean bodyFits(Level level, BlockPos feet) {
        return passable(level, feet) && passable(level, feet.above());
    }

    /** A body can stand here: room for feet and head, and either a floor or water to swim in. */
    public static boolean standable(Level level, BlockPos feet) {
        if (!bodyFits(level, feet)) return false;
        if (isWater(level, feet)) return true;
        return isFloor(level, feet.below());
    }

    public static boolean passable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isHazard(state)) return false;
        if (state.getFluidState().is(FluidTags.LAVA)) return false;
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Cost of standing up in {@code feet}, coming from {@code from}, on a step along {@code axis} through a door or fence
     * gate: 0 through an open one, CLOSED_DOOR per closed half or gate the body opens by hand first, CLOSED_DOOR for a
     * closed iron door with a button or lever beside it on {@code from}'s side, BLOCKED otherwise. A door is passed only
     * through its opening (along its facing axis, feet in the lower half, head in the upper), where the panel of the open
     * door lies at the side of the cell; an open fence gate has nothing left to bump into.
     */
    private static double through(Level level, BlockPos from, BlockPos feet, Direction.Axis axis) {
        return through(level, from, feet, axis, DoubleBlockHalf.LOWER) + through(level, from, feet.above(), axis, DoubleBlockHalf.UPPER);
    }

    private static double through(Level level, BlockPos from, BlockPos cell, Direction.Axis axis, DoubleBlockHalf half) {
        BlockState state = level.getBlockState(cell);
        if (state.getBlock() instanceof DoorBlock) {
            if (doorAxis(state) != axis || state.getValue(DoorBlock.HALF) != half) return BLOCKED;
            if (state.getValue(DoorBlock.OPEN)) return 0;
            if (opensByHand(state)) return CLOSED_DOOR;
            // A closed iron door is decided once, at its lower half: passable when a switch on this side opens it.
            if (half == DoubleBlockHalf.UPPER) return 0;
            return switchFor(level, cell, from) != null ? CLOSED_DOOR : BLOCKED;
        }
        if (opensByHand(state)) return CLOSED_DOOR;
        return passable(level, cell) ? 0 : BLOCKED;
    }

    /**
     * A button or lever, not switched on, that opens the closed door at {@code door} (either half) from {@code from}'s
     * side, the way redstone powers a door: the switch stands next to a door half, or is attached to a solid block next
     * to one. Null when there is none.
     */
    public static @Nullable BlockPos switchFor(Level level, BlockPos door, BlockPos from) {
        BlockState state = level.getBlockState(door);
        if (!(state.getBlock() instanceof DoorBlock)) return null;
        BlockPos lower = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? door.below() : door;
        Direction.Axis axis = state.getValue(DoorBlock.FACING).getAxis();
        int side = Integer.signum(from.get(axis) - lower.get(axis));
        if (side == 0) return null;
        for (BlockPos half : new BlockPos[]{lower, lower.above()}) {
            for (Direction d : Direction.values()) {
                BlockPos next = half.relative(d);
                if (next.equals(lower) || next.equals(lower.above())) continue;
                BlockState nextState = level.getBlockState(next);
                if (isSwitch(nextState)) {
                    if (Integer.signum(next.get(axis) - lower.get(axis)) == side) return next;
                    continue;
                }
                if (!nextState.isRedstoneConductor(level, next)) continue;
                for (Direction e : Direction.values()) {
                    BlockPos at = next.relative(e);
                    BlockState switchState = level.getBlockState(at);
                    if (isSwitch(switchState) && at.relative(attachedTo(switchState)).equals(next)
                            && Integer.signum(at.get(axis) - lower.get(axis)) == side) return at;
                }
            }
        }
        return null;
    }

    /** A button or lever that is not switched on. */
    private static boolean isSwitch(BlockState state) {
        return (state.is(BlockTags.BUTTONS) || state.getBlock() instanceof LeverBlock) && !state.getValue(BlockStateProperties.POWERED);
    }

    /** A lever (rather than a button): it stays switched on until it is flipped back. */
    public static boolean isLever(BlockState state) {
        return state.getBlock() instanceof LeverBlock;
    }

    /** The side of a button or lever that holds the block it is attached to. */
    private static Direction attachedTo(BlockState state) {
        AttachFace face = state.getValue(BlockStateProperties.ATTACH_FACE);
        return face == AttachFace.FLOOR ? Direction.DOWN : face == AttachFace.CEILING ? Direction.UP
                : state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
    }

    /** A door or fence gate: walked through, never stood in, so it can be closed again. */
    public static boolean isPassage(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock;
    }

    /** The axis a body walks through this door along (its facing axis); null when it is not a door. */
    private static Direction.@Nullable Axis doorAxis(BlockState state) {
        return state.getBlock() instanceof DoorBlock ? state.getValue(DoorBlock.FACING).getAxis() : null;
    }

    /** A closed door or fence gate a player opens by hand: wooden and copper doors and every fence gate, not iron doors. */
    public static boolean opensByHand(BlockState state) {
        if (state.getBlock() instanceof DoorBlock door) return !state.getValue(DoorBlock.OPEN) && door.type().canOpenByHand();
        return state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN);
    }

    /** A door cell a route walks through rather than clears: an open door of any kind, or a closed one opened by hand. */
    public static boolean isDoorway(Level level, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        return state.getBlock() instanceof DoorBlock && (state.getValue(DoorBlock.OPEN) || opensByHand(state));
    }

    public static boolean isFloor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isHazard(state) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)) return false;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return false;
        double top = shape.max(Direction.Axis.Y);
        return top > 0.4 && top <= 1.0;
    }

    public static boolean isWater(Level level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER);
    }

    /** A body standing with its feet at {@code feet} has its head under water. */
    public static boolean headUnderWater(Level level, BlockPos feet) {
        return isWater(level, feet.above());
    }

    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
    }
}
