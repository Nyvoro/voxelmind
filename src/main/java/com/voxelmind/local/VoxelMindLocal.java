package com.voxelmind.local;

import com.voxelmind.local.command.VmlCommand;
import com.voxelmind.local.companion.CompanionManager;
import com.voxelmind.local.config.LocalConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VoxelMindLocal implements ModInitializer {
    public static final String MOD_ID = "voxelmind_local";
    public static final Logger LOG = LoggerFactory.getLogger("VoxelMind Local");
    /** Donations for the free mod; linked from the menu, /vml help and Mod Menu (fabric.mod.json). */
    public static final String SUPPORT_URL = "https://ko-fi.com/voxelmind";
    /** Discord invite into #voxelmind-feature-request; there is no feedback database any more. */
    public static final String FEATURE_REQUEST_URL = "https://discord.gg/jRthVZV2uk";

    @Override
    public void onInitialize() {
        LocalConfig.load();
        com.voxelmind.local.net.MenuPayloads.register();
        com.voxelmind.local.net.Speech.register();
        com.voxelmind.local.hosted.HostedEvents.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> VmlCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(CompanionManager::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> CompanionManager.stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            CompanionManager m = CompanionManager.get();
            if (m != null) m.tick();
        });
        // Connection events can arrive on network threads; world changes happen on the server thread.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            CompanionManager m = CompanionManager.get();
            if (m != null) m.onPlayerJoin(handler.player);
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> {
            CompanionManager m = CompanionManager.get();
            if (m != null) m.onPlayerLeave(handler.player);
        }));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            CompanionManager m = CompanionManager.get();
            if (m != null) m.onRespawn(oldPlayer, newPlayer);
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            CompanionManager m = CompanionManager.get();
            if (m != null) m.onChat(sender, message.decoratedContent().getString());
        });
        LOG.info("VoxelMind Local loaded; config at {}", LocalConfig.path());
    }
}
