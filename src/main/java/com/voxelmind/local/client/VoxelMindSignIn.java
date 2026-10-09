package com.voxelmind.local.client;

import com.voxelmind.local.provider.VoxelMindApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.util.concurrent.CompletableFuture;

/**
 * Signs in to the VoxelMind API with the player's Minecraft account: the game joins a server id at
 * Mojang, exactly as when joining a multiplayer server, and the API asks Mojang who joined. Neither the
 * password nor the Mojang token leaves this PC.
 */
public final class VoxelMindSignIn {
    private VoxelMindSignIn() {}

    public static CompletableFuture<VoxelMindApi.Login> start() {
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        return VoxelMindApi.startLogin().thenCompose(serverId -> CompletableFuture.supplyAsync(() -> {
            try {
                mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
            } catch (Exception e) {
                throw new IllegalStateException("Mojang did not accept the sign-in (" + e.getMessage()
                        + "). It needs a Minecraft account signed in through the launcher.", e);
            }
            return serverId;
        })).thenCompose(serverId -> VoxelMindApi.finishLogin(user.getName(), serverId))
                .thenApply(login -> {
                    VoxelMindApi.adopt(login.token());
                    return login;
                });
    }
}
