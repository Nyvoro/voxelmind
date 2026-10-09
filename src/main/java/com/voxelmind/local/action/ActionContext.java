package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.compat.Compat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

/** What an action needs from its companion: the body and who the owner is. */
public final class ActionContext {
    private final Supplier<CompanionPlayer> body;
    private final UUID ownerId;

    public ActionContext(Supplier<CompanionPlayer> body, UUID ownerId) {
        this.body = body;
        this.ownerId = ownerId;
    }

    public CompanionPlayer body() {
        return body.get();
    }

    public ServerLevel level() {
        return Compat.level(body());
    }

    public MinecraftServer server() {
        return body().level().getServer();
    }

    public UUID ownerId() {
        return ownerId;
    }

    public @Nullable ServerPlayer owner() {
        return server().getPlayerList().getPlayer(ownerId);
    }
}
