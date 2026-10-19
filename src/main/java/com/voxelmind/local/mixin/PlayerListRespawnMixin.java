package com.voxelmind.local.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import com.voxelmind.local.body.CompanionPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A respawned companion must come back as a companion body, not as a plain player. A wrap instead of a redirect,
 * so it chains with other mods that make their own players here (Carpet's fake players redirect the same call;
 * two redirects on one call made the game crash when a world was opened).
 */
@Mixin(PlayerList.class)
public abstract class PlayerListRespawnMixin {
    @WrapOperation(
            method = "respawn",
            at = @At(value = "NEW", target = "net/minecraft/server/level/ServerPlayer"))
    private ServerPlayer voxelmindLocal$respawnBody(
            MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info,
            Operation<ServerPlayer> original, @Local(argsOnly = true) ServerPlayer oldPlayer) {
        if (oldPlayer instanceof CompanionPlayer) {
            return new CompanionPlayer(server, level, profile);
        }
        return original.call(server, level, profile, info);
    }
}
