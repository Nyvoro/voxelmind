package com.voxelmind.local.action;

import com.voxelmind.local.world.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * survey: reads the ground around a point without moving. Reports, for every column,
 * the y of the top block one would stand on; a block built on that ground goes at y+1.
 */
public final class SurveyAction extends BodyAction {
    private final String centerText;
    private final int radius;

    public SurveyAction(String call, String center, int radius) {
        super("survey", call);
        this.centerText = center == null ? "" : center.trim();
        this.radius = Math.max(1, Math.min(8, radius <= 0 ? 5 : radius));
    }

    @Override
    protected void onStart(ActionContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos center = ctx.body().blockPosition();
        if (!centerText.isEmpty() && !centerText.equalsIgnoreCase("here")) {
            String[] parts = centerText.replace("\"", " ").replace("'", " ").replace(",", " ").replace("(", " ").replace(")", " ").trim().split("\\s+");
            try {
                if (parts.length == 2) center = new BlockPos(Integer.parseInt(parts[0]), center.getY(), Integer.parseInt(parts[1]));
                else if (parts.length == 3) center = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                else throw new NumberFormatException();
            } catch (NumberFormatException e) {
                failed("center must be \"x z\", \"x y z\" or \"here\"");
                return;
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("ground around (").append(center.getX()).append(",").append(center.getZ()).append("), radius ").append(radius)
                .append(": numbers are the y of the top solid block (stand on it at y+1; a block built on it goes at y+1); ")
                .append("~ water surface, L lava, T tree/leaves on top. Rows north→south, columns west→east.\n");
        out.append("      ");
        for (int dx = -radius; dx <= radius; dx++) out.append(String.format("%5d", center.getX() + dx));
        out.append('\n');
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dz = -radius; dz <= radius; dz++) {
            int z = center.getZ() + dz;
            out.append(String.format("z=%-4d", z));
            for (int dx = -radius; dx <= radius; dx++) {
                int x = center.getX() + dx;
                if (!level.isLoaded(new BlockPos(x, center.getY(), z))) {
                    out.append("    ?");
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockState surface = level.getBlockState(new BlockPos(x, top, z));
                BlockState above = level.getBlockState(new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z));
                String mark = "";
                if (surface.getFluidState().is(FluidTags.WATER)) mark = "~";
                else if (surface.getFluidState().is(FluidTags.LAVA)) mark = "L";
                else if (above.is(BlockTags.LEAVES) || surface.is(BlockTags.LOGS)) mark = "T";
                out.append(String.format("%5s", top + mark));
                if (mark.isEmpty()) {
                    min = Math.min(min, top);
                    max = Math.max(max, top);
                }
            }
            out.append('\n');
        }
        if (min != Integer.MAX_VALUE) out.append("dry ground ranges from y=").append(min).append(" to y=").append(max).append('.');
        done(out.toString());
    }

    @Override
    protected void onTick(ActionContext ctx) {
    }

    @Override
    public String progress() {
        return "surveying around " + (centerText.isEmpty() ? "here" : centerText) + " " + Names.pos(ctx == null ? BlockPos.ZERO : ctx.body().blockPosition());
    }
}
