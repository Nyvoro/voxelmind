package com.voxelmind.local.action;

import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Placed;
import com.voxelmind.local.world.Resources;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/** mine_block: one block, either the nearest visible of a type (that no player placed, unless included) or at given coordinates. */
public final class MineBlockAction extends BodyAction {
    private final String targetText;
    private final boolean includePlaced;
    private BlockBreaker breaker;

    public MineBlockAction(String call, String targetText) {
        this(call, targetText, false);
    }

    /** {@code includePlaced}: by type, blocks a player placed or planted count too. */
    public MineBlockAction(String call, String targetText, boolean includePlaced) {
        super("mine_block", call);
        this.targetText = targetText;
        this.includePlaced = includePlaced;
    }

    @Override
    protected void onStart(ActionContext ctx) {
        BlockPos pos = Names.coords(targetText);
        if (pos == null) {
            Block block = Names.block(targetText);
            if (block == null) {
                failed("'" + targetText + "' is neither coordinates nor a block name");
                return;
            }
            // Prefer one you can see; otherwise the nearest one, digging through what is in the way. A block of the type
            // means one from the world: blocks a player placed are only mined when named by their coordinates or with
            // include_placed.
            pos = Targets.nearestBlock(ctx.level(), ctx.body().blockPosition(),
                    (p, s) -> s.is(block) && Resources.exposed(ctx.level(), p) && (includePlaced || !Placed.isPlaced(ctx.level(), p)), Targets.BLOCK_RANGE);
            if (pos == null) {
                pos = Targets.nearestBlock(ctx.level(), ctx.body().blockPosition(),
                        (p, s) -> s.is(block) && (includePlaced || !Placed.isPlaced(ctx.level(), p)), Targets.BLOCK_RANGE);
            }
            if (pos == null) {
                BlockPos placed = includePlaced ? null : Targets.nearestBlock(ctx.level(), ctx.body().blockPosition(), s -> s.is(block), Targets.BLOCK_RANGE);
                failed("no " + Names.id(block) + " within " + Targets.BLOCK_RANGE + " blocks" + (includePlaced ? "" : " that no player placed")
                        + (placed == null ? "" : "; the nearest one, at " + Names.pos(placed) + ", was placed by " + Placed.placer(ctx.level(), placed)
                        + " (mine_block with its coordinates, or include_placed true, mines it)"));
                return;
            }
        }
        if (ctx.level().getBlockState(pos).isAir()) {
            failed("there is no block at " + Names.pos(pos) + " (air)");
            return;
        }
        breaker = new BlockBreaker(ctx, nav, pos);
    }

    @Override
    protected void onTick(ActionContext ctx) {
        BlockBreaker.State s = breaker.tick();
        if (s == BlockBreaker.State.DONE) done(breaker.describe());
        else if (s == BlockBreaker.State.FAILED) failed(breaker.failure());
    }

    @Override
    public String progress() {
        return breaker == null ? "" : "mining " + Names.pos(breaker.target()) + " (" + breaker.state().name().toLowerCase() + ")";
    }
}
