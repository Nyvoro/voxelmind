package com.voxelmind.local.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.voxelmind.local.world.Placed;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player's right-click on a block (humans and companion bodies alike) that turns a placed block into another form where
 * it lies, e.g. an axe stripping a log or honeycomb waxing copper: the placed-blocks index keeps it as that player's.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void voxelmindLocal$before(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit,
                                       CallbackInfoReturnable<InteractionResult> cir, @Share("before") LocalRef<BlockState> before) {
        // Bone meal grows a sapling or mushroom into something new: that is the world's growth, not the placed block.
        before.set(level instanceof ServerLevel && !stack.is(Items.BONE_MEAL) ? level.getBlockState(hit.getBlockPos()) : null);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void voxelmindLocal$after(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit,
                                      CallbackInfoReturnable<InteractionResult> cir, @Share("before") LocalRef<BlockState> before) {
        BlockState was = before.get();
        if (was != null && level instanceof ServerLevel server) Placed.onUsed(server, hit.getBlockPos(), was);
    }
}
