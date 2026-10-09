package com.voxelmind.local.mixin;

import com.voxelmind.local.body.CompanionPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Companions do not count toward the players needed to skip the night. */
@Mixin(SleepStatus.class)
public abstract class SleepStatusMixin {
    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
    private boolean voxelmindLocal$companionsDoNotCount(ServerPlayer player) {
        return player.isSpectator() || player instanceof CompanionPlayer;
    }
}
