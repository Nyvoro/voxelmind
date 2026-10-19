package com.voxelmind.local;

import com.voxelmind.local.command.VmlCommand;
import com.voxelmind.local.companion.CompanionManager;
import com.voxelmind.local.config.LocalConfig;
import com.voxelmind.local.world.Placed;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.entity.item.FallingBlockEntity;
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
        try {
            // Own skin PNGs go here (/vml skin <name> <file.png>); made at start so players find it.
            java.nio.file.Files.createDirectories(com.voxelmind.local.companion.Skins.folder());
            java.nio.file.Files.createDirectories(com.voxelmind.local.mind.Brains.folder());
        } catch (java.io.IOException e) {
            LOG.warn("Could not create the skins or brains folder", e);
        }
        com.voxelmind.local.mind.Brains.reload();
        com.voxelmind.local.net.MenuPayloads.register();
        com.voxelmind.local.net.Speech.register();
        com.voxelmind.local.hosted.HostedEvents.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> VmlCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(Placed::start);
        ServerLifecycleEvents.SERVER_STARTED.register(CompanionManager::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> CompanionManager.stop());
        ServerLifecycleEvents.SERVER_STOPPED.register(Placed::stop);
        // The placed-blocks index is saved with the world (autosave, /save-all, the pause save, backups), not only with
        // the companions, so a hard stop keeps it as current as the world's own files.
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> Placed.save(server));
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> Placed.onBreak(level, pos, state));
        // A placed block that falls (sand, gravel, concrete powder) is still placed where it lands.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof FallingBlockEntity falling) Placed.onFallStart(level, falling);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof FallingBlockEntity falling) Placed.onFallEnd(level, falling);
        });
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
