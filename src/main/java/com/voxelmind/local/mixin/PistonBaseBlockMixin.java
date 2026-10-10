package com.voxelmind.local.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.voxelmind.local.world.Placed;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A piston moving placed blocks: their entries in the placed-blocks index move along, before the blocks do. */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
    @WrapOperation(method = "moveBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;resolve()Z"))
    private boolean voxelmindLocal$movePlaced(PistonStructureResolver resolver, Operation<Boolean> original,
                                              @Local(argsOnly = true) Level level, @Local(argsOnly = true) Direction direction,
                                              @Local(argsOnly = true) boolean extending) {
        boolean moves = original.call(resolver);
        // Once resolved, the move happens: the blocks to push go one cell along the push direction, the others break.
        if (moves && level instanceof ServerLevel server) {
            Placed.onPistonMove(server, resolver.getToPush(), resolver.getToDestroy(), extending ? direction : direction.getOpposite());
        }
        return moves;
    }
}
