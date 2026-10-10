package com.voxelmind.local.mixin;

import com.voxelmind.local.world.Placed;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every block a player places by hand goes into the placed-blocks index (humans and companion bodies alike). */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void voxelmindLocal$recordPlaced(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!cir.getReturnValue().consumesAction()) return;
        if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) return;
        BlockItem item = (BlockItem) (Object) this;
        BlockPos pos = context.getClickedPos();
        BlockState now = level.getBlockState(pos);
        // The item's block, or its wall form (torches, signs, banners, heads).
        if (!now.is(item.getBlock()) && now.getBlock().asItem() != item) return;
        Placed.onPlace(level, pos, player);
    }
}
