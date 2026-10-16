package com.voxelmind.local.client;

import com.voxelmind.local.provider.VoxelMindApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.concurrent.CompletableFuture;

/**
 * Signs in to the VoxelMind API with the player's Minecraft account: the game signs the API's server id with
 * the player key Mojang certifies for chat signing, and the API checks Mojang's certificate for that key. The
 * private key, the password and the Mojang token never leave this PC; only the public certificate does.
 */
public final class VoxelMindSignIn {
    private VoxelMindSignIn() {}

    public static CompletableFuture<VoxelMindApi.Login> start() {
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        return VoxelMindApi.startLogin().thenCombine(mc.getProfileKeyPairManager().prepareKeyPair(), (serverId, keys) -> {
            ProfileKeyPair pair = keys.orElseThrow(() -> new IllegalStateException(
                    "Minecraft has no player certificate from Mojang. It needs a Minecraft account signed in through the launcher."));
            try {
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(pair.privateKey());
                signature.update((VoxelMindApi.CHALLENGE + serverId).getBytes(StandardCharsets.UTF_8));
                ProfilePublicKey.Data data = pair.publicKey().data();
                return new VoxelMindApi.Proof(serverId, user.getProfileId().toString(), data.expiresAt().toEpochMilli(),
                        data.key().getEncoded(), data.keySignature(), signature.sign());
            } catch (Exception e) {
                throw new IllegalStateException("The sign-in could not be signed (" + e.getMessage() + ").", e);
            }
        }).thenCompose(proof -> VoxelMindApi.finishLogin(user.getName(), proof))
                .thenApply(login -> {
                    VoxelMindApi.adopt(login.token(), login.name());
                    return login;
                });
    }
}
