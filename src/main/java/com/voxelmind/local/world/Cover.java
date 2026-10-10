package com.voxelmind.local.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

/** What is over a standing cell, and the two sides of a door or fence gate, stated literally. */
public final class Cover {
    private Cover() {}

    /** "open sky", or the first block above head height at {@code feet} with who placed it. */
    public static String over(ServerLevel level, BlockPos feet) {
        int head = feet.getY() + 1;
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, feet.getX(), feet.getZ()) - 1;
        if (top <= head) return "open sky";
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(feet.getX(), head + 1, feet.getZ());
        while (cursor.getY() < top && level.getBlockState(cursor).isAir()) cursor.move(Direction.UP);
        String placer = Placed.placer(level, cursor);
        return Names.id(level.getBlockState(cursor).getBlock()) + " at y=" + cursor.getY() + (placer == null ? "" : " (placed by " + placer + ")");
    }

    /**
     * The two cells a door or fence gate is walked through between, along its facing axis, with what is above each:
     * " (east side (x,y,z): under oak_planks at y=67 (placed by Gasade); west side (x,y,z): under open sky)"; "" for
     * anything else.
     */
    public static String sides(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(BlockTags.TRAPDOORS) || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return "";
        BlockPos lower = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        StringBuilder out = new StringBuilder(" (");
        for (Direction d : new Direction[]{facing, facing.getOpposite()}) {
            BlockPos side = lower.relative(d);
            if (d != facing) out.append("; ");
            out.append(d.getName()).append(" side ").append(Names.pos(side)).append(": under ").append(over(level, side));
        }
        return out.append(')').toString();
    }
}
