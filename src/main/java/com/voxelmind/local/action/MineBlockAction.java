package com.voxelmind.local.action;

import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Resources;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/** mine_block: one block, either the nearest visible of a type or at given coordinates. */
public final class MineBlockAction extends BodyAction {
    private final String targetText;
    private BlockBreaker breaker;

    public MineBlockAction(String call, String targetText) {
        super("mine_block", call);
        this.targetText = targetText;
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
            // Prefer one you can see; otherwise the nearest one, digging through what is in the way.
            pos = Targets.nearestBlock(ctx.level(), ctx.body().blockPosition(),
                    (p, s) -> s.is(block) && Resources.exposed(ctx.level(), p), Targets.BLOCK_RANGE);
            if (pos == null) {
                pos = Targets.nearestBlock(ctx.level(), ctx.body().blockPosition(), s -> s.is(block), Targets.BLOCK_RANGE);
            }
            if (pos == null) {
                failed("no " + Names.id(block) + " within " + Targets.BLOCK_RANGE + " blocks");
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
