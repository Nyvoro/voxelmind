package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.body.PathFinder;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Placed;
import com.voxelmind.local.world.Resources;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * explore: walks out in one compass direction (nearby ≈ 72 blocks, wide ≈ 110) and
 * stops early when the thing looked for comes into view. Walks only; never digs.
 * What it found is reported with how far below or above it is, whether it is under
 * water, and whether a walking or swimming route leads to it.
 */
public final class ExploreAction extends BodyAction {
    /** Search budget for the route check to what was found. */
    private static final int ROUTE_NODES = 4000;
    private final String targetText;
    private final String directionText;
    private final boolean wide;
    private BlockPos origin;
    private int[] dir;
    private String dirName;
    private BiPredicate<BlockPos, BlockState> blockGoal;
    private Predicate<Entity> entityGoal;
    private String goalLabel = "";
    private int legs;
    private BlockPos legTarget;

    public ExploreAction(String call, String target, String direction, String scope) {
        super("explore", call);
        this.targetText = target == null ? "" : target.trim();
        this.directionText = direction == null ? "" : direction.trim();
        this.wide = "wide".equalsIgnoreCase(scope);
    }

    @Override
    protected void onStart(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        origin = body.blockPosition();
        if (!directionText.isEmpty()) {
            dir = Names.compass(directionText);
            if (dir == null) {
                failed("'" + directionText + "' is not a compass direction");
                return;
            }
            dirName = Names.normalize(directionText);
        } else {
            String[] names = {"north", "east", "south", "west"};
            dirName = names[body.getRandom().nextInt(4)];
            dir = Names.compass(dirName);
        }
        String t = Names.normalize(targetText);
        if (t.isEmpty()) {
            goalLabel = "";
        } else if (t.equals("trees") || t.equals("tree") || t.equals("wood")) {
            goalLabel = "trees";
            blockGoal = (p, s) -> s.is(BlockTags.LOGS);
        } else if (t.equals("water")) {
            goalLabel = "water";
            blockGoal = (p, s) -> s.getFluidState().is(FluidTags.WATER);
        } else if (t.equals("animals") || t.equals("animal")) {
            goalLabel = "animals";
            entityGoal = e -> e instanceof Animal && e.isAlive();
        } else if (t.equals("village")) {
            goalLabel = "a village";
            entityGoal = e -> Names.id(e.getType()).equals("villager");
        } else if (t.equals("ore") || t.equals("ores")) {
            goalLabel = "ore";
            blockGoal = (p, s) -> Resources.isOre(s.getBlock());
        } else {
            EntityType<?> type = Names.entityType(t);
            var block = Names.block(t);
            if (type != null) {
                goalLabel = Names.id(type);
                entityGoal = e -> e.getType() == type && e.isAlive();
            } else if (block != null) {
                goalLabel = Names.id(block);
                blockGoal = (p, s) -> s.is(block);
            } else {
                failed("'" + targetText + "' is not something I can look out for (trees, water, animals, village, ore, a creature or block name)");
                return;
            }
        }
        Hit already = found(ctx);
        if (already != null) {
            done(goalLabel + " is already in view: " + describe(ctx, already));
            return;
        }
        nextLeg(ctx);
    }

    private int range() {
        return wide ? 110 : 72;
    }

    private void nextLeg(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        ServerLevel level = ctx.level();
        int step = 24 * (legs + 1);
        double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1]);
        int x = origin.getX() + (int) Math.round(dir[0] / len * Math.min(step, range()));
        int z = origin.getZ() + (int) Math.round(dir[1] / len * Math.min(step, range()));
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        legTarget = new BlockPos(x, y, z);
        nav.allowDig(false);
        nav.navigateNear(legTarget, 3.0);
        legs++;
    }

    /** What came into view: its description and where it is. */
    private record Hit(String text, BlockPos pos) {}

    private @Nullable Hit found(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        if (blockGoal != null) {
            // A block a player placed is not what explore looks out for: a log wall is no tree.
            BlockPos pos = Targets.nearestBlock(ctx.level(), body.blockPosition(),
                    (p, s) -> blockGoal.test(p, s) && !Placed.isPlaced(ctx.level(), p), 20);
            return pos == null ? null : new Hit("closest at " + Names.pos(pos) + ", " + Math.round(Math.sqrt(pos.distSqr(body.blockPosition()))) + "m away", pos);
        }
        if (entityGoal != null) {
            Entity e = Targets.nearestEntity(body, entityGoal, 28);
            return e == null ? null : new Hit(Names.entity(e) + " [#" + e.getId() + "] at " + Names.pos(e.blockPosition()) + ", "
                    + Math.round(body.distanceTo(e)) + "m away", e.blockPosition());
        }
        return null;
    }

    /**
     * The hit with where it lies: how far below or above the body, how deep under water, and whether a walking or
     * swimming route (no digging) leads to within reach of it.
     */
    private String describe(ActionContext ctx, Hit hit) {
        ServerLevel level = ctx.level();
        BlockPos feet = nav.feet();
        StringBuilder text = new StringBuilder(hit.text());
        int dy = hit.pos().getY() - feet.getY();
        if (dy <= -2) text.append(", ").append(-dy).append(" blocks below you");
        else if (dy >= 2) text.append(", ").append(dy).append(" blocks above you");
        int depth = 0;
        for (BlockPos p = hit.pos(); PathFinder.isWater(level, p) && depth < level.getHeight(); p = p.above()) depth++;
        if (depth > 0) text.append(", under water ").append(depth).append(depth == 1 ? " block" : " blocks").append(" deep");
        BlockPos target = hit.pos();
        PathFinder.Result route = PathFinder.find(level, feet, target, f -> Moves.eyeDistance(f, target) <= 4.0, ROUTE_NODES);
        if (!route.reachesGoal()) {
            text.append("; no walking or swimming route to within reach of it was found");
            if (depth > PathFinder.MAX_UNDER_WATER) text.append(" (routes never dive more than ").append(PathFinder.MAX_UNDER_WATER)
                    .append(" steps in a row with the head under water)");
        }
        return text.toString();
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        if (ticks % 20 == 0 && (blockGoal != null || entityGoal != null)) {
            Hit hit = found(ctx);
            if (hit != null) {
                done("explored " + dirName + " " + distance(body) + " blocks and found " + goalLabel + ": " + describe(ctx, hit));
                return;
            }
        }
        body.motor.sprint = false;
        Navigator.Status s = nav.tick();
        if (s == Navigator.Status.ARRIVED || s == Navigator.Status.FAILED) {
            if (distance(body) >= range() - 6 || legs >= 6 || (s == Navigator.Status.FAILED && legs >= 2)) {
                String end = "explored " + dirName + " " + distance(body) + " blocks to " + Names.pos(body.blockPosition());
                if (goalLabel.isEmpty()) done(end + "; look at [SENSORY] for what is here");
                else failed(end + " without seeing " + goalLabel + (s == Navigator.Status.FAILED ? "; the way was blocked: " + nav.failure() : ""));
                return;
            }
            nextLeg(ctx);
        }
    }

    private int distance(CompanionPlayer body) {
        return (int) Math.round(Math.sqrt(origin.distSqr(body.blockPosition())));
    }

    @Override
    public String progress() {
        if (ctx == null || origin == null) return "";
        return "exploring " + dirName + (goalLabel.isEmpty() ? "" : " for " + goalLabel) + ", " + distance(ctx.body()) + " blocks out";
    }
}
