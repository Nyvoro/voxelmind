package com.voxelmind.local.mixin;

import com.mojang.authlib.GameProfile;
import com.voxelmind.local.body.CompanionPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A respawned companion must come back as a companion body, not as a plain player. */
@Mixin(PlayerList.class)
public abstract class PlayerListRespawnMixin {
    @Redirect(
            method = "respawn",
            at = @At(value = "NEW", target = "net/minecraft/server/level/ServerPlayer"))
    private ServerPlayer voxelmindLocal$respawnBody(
            MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info,
            ServerPlayer oldPlayer, boolean keepAllPlayerData, Entity.RemovalReason reason) {
        if (oldPlayer instanceof CompanionPlayer) {
            return new CompanionPlayer(server, level, profile);
        }
        return new ServerPlayer(server, level, profile, info);
    }
}
