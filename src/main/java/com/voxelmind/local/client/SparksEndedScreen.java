package com.voxelmind.local.client;

import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.client.ui.Ui;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Shown once after an update from a config that thought with VoxelMind Sparks (LocalConfig.migrate): Sparks has ended,
 * the way on is another AI provider or a local model (the button opens the AI settings), and one line about the game
 * VoxelMind is becoming. Companions, memories and the other settings are untouched.
 */
public final class SparksEndedScreen extends PanelScreen {
    private static final Component BODY = Component.translatable("voxelmind_local.notice.sparks_ended");
    private static final Component GAME = Component.translatable("voxelmind_local.game").append(" ")
            .append(Component.literal("voxel-mind.com").withStyle(ChatFormatting.UNDERLINE));

    public SparksEndedScreen(@Nullable Screen parent) {
        super(Component.translatable("voxelmind_local.sparks_ended.title"), parent);
    }

    private int textWidth() {
        return Math.min(panelWidth(), width - 8) - 2 * PAD;
    }

    private int lines(Component text) {
        return font.split(text, textWidth()).size() * font.lineHeight;
    }

    /** Title, the notice, the line about the game and two buttons; the panel is as tall as the language's text needs. */
    @Override
    protected int panelHeight() {
        return PAD + 20 + lines(BODY) + 8 + lines(GAME) + 12 + 44 + PAD;
    }

    @Override
    protected void build() {
        int x = left + PAD;
        int y = bottom - PAD - 20;
        addRenderableWidget(Button.builder(Component.translatable("voxelmind_local.sparks_ended.choose"),
                b -> minecraft.gui.setScreen(new SettingsScreen(parent))).bounds(x, y - 24, textWidth(), 20).build());
        addRenderableWidget(Button.builder(Component.translatable("voxelmind_local.sparks_ended.later"), b -> onClose())
                .bounds(x, y, textWidth(), 20).build());
    }

    @Override
    protected void paint(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = left + PAD;
        int w = textWidth();
        int y = top + PAD;
        Ui.text(g, font, Component.literal("VoxelMind").withStyle(ChatFormatting.BOLD), x, y, Ui.TEXT_PRIMARY);
        y += 12;
        Ui.divider(g, x, right - PAD, y);
        y += 8;
        Ui.wordWrap(g, font, BODY, x, y, w, Ui.TEXT_PRIMARY);
        y += lines(BODY) + 8;
        // One quiet line about the game, a link like the ones under the companion menu.
        int h = lines(GAME);
        boolean hover = hovered(x, y - 1, w, h + 2);
        Ui.wordWrap(g, font, GAME, x, y, w, hover ? Ui.TEXT_PRIMARY : Ui.TEXT_MUTED);
        clicks.add(x, y - 1, w, h + 2, () -> Util.getPlatform().openUri(VoxelMindLocal.GAME_URL));
    }
}
