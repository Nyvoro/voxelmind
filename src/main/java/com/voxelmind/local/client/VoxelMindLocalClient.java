package com.voxelmind.local.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.voxelmind.local.net.MenuPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

/**
 * Client side: the companion menu (V, the pause-menu button or /vml) and the AI settings
 * (/vml setup, a chat link or an unbound key).
 */
public final class VoxelMindLocalClient implements ClientModInitializer {
    private static final int UNBOUND = -1;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(MenuPayloads.OpenScreen.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if ("settings".equals(payload.screen())) openSettings(context.client());
                    else openMenu(context.client());
                }));
        ClientPlayNetworking.registerGlobalReceiver(MenuPayloads.Snapshot.TYPE,
                (payload, context) -> MenuState.receive(payload.json()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MenuState.clear());

        KeyMapping menuKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.voxelmind_local.menu", InputConstants.KEY_V, KeyMapping.Category.MISC));
        KeyMapping settingsKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.voxelmind_local.settings", UNBOUND, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.consumeClick()) {
                if (client.gui.screen() == null && client.player != null) openMenu(client);
            }
            while (settingsKey.consumeClick()) {
                if (client.hasSingleplayerServer()) openSettings(client);
                else if (client.player != null) client.player.sendSystemMessage(Component.translatable("voxelmind_local.settings.server_owned"));
            }
            MenuState.tick(client);
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof PauseScreen pause && pause.showsPauseMenu() && MenuState.serverHasMod()) {
                Screens.getWidgets(screen).add(Button.builder(Component.literal("♦ VoxelMind"),
                        b -> client.gui.setScreen(new CompanionsScreen(screen))).bounds(4, 4, 80, 20).build());
            }
        });
    }

    static void openMenu(Minecraft client) {
        client.gui.setScreen(new CompanionsScreen(client.gui.screen()));
    }

    static void openSettings(Minecraft client) {
        client.gui.setScreen(new SettingsScreen(client.gui.screen()));
    }
}
