package com.voxelmind.local.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
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
    /** The context the placement really used: scaffolding clicked onto scaffolding goes into another cell than the clicked one. */
    @ModifyExpressionValue(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/BlockItem;updatePlacementContext(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/item/context/BlockPlaceContext;"))
    private BlockPlaceContext voxelmindLocal$placedWith(BlockPlaceContext updated, @Share("placedWith") LocalRef<BlockPlaceContext> placedWith) {
        placedWith.set(updated);
        return updated;
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void voxelmindLocal$recordPlaced(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir,
                                             @Share("placedWith") LocalRef<BlockPlaceContext> placedWith) {
        if (!cir.getReturnValue().consumesAction()) return;
        BlockPlaceContext used = placedWith.get() != null ? placedWith.get() : context;
        if (!(used.getLevel() instanceof ServerLevel level) || !(used.getPlayer() instanceof ServerPlayer player)) return;
        BlockItem item = (BlockItem) (Object) this;
        BlockPos pos = used.getClickedPos();
        BlockState now = level.getBlockState(pos);
        // The item's block, its wall form (torches, signs, banners, heads), or another form it took at once (concrete
        // powder placed against water hardens).
        if (!now.is(item.getBlock()) && now.getBlock().asItem() != item && !Placed.sameKind(now.getBlock(), item.getBlock())) return;
        Placed.onPlace(level, pos, player);
    }
}
