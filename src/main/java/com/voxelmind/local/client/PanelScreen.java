package com.voxelmind.local.client;

import com.voxelmind.local.client.ui.ClickRegions;
import com.voxelmind.local.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A menu screen in the original VoxelMind look: one dark panel, painted controls registered as click
 * regions while drawing, vanilla widgets only for text input. The world keeps running behind it.
 */
abstract class PanelScreen extends Screen {
    static final int PANEL_WIDTH = 320;
    static final int PAD = 10;

    protected final @Nullable Screen parent;
    protected final ClickRegions clicks = new ClickRegions();
    protected int left;
    protected int right;
    protected int top;
    protected int bottom;
    private int mouseX;
    private int mouseY;

    protected PanelScreen(Component title, @Nullable Screen parent) {
        super(title);
        this.parent = parent;
    }

    static Component tr(String key, Object... args) {
        return Component.translatable("voxelmind_local.menu." + key, args);
    }

    /** Panel height; the default fills the screen with a small margin. */
    protected int panelHeight() {
        return height - 16;
    }

    @Override
    protected final void init() {
        int h = Math.min(panelHeight(), height - 8);
        left = width / 2 - PANEL_WIDTH / 2;
        right = left + PANEL_WIDTH;
        top = Math.max(4, (height - h) / 2);
        bottom = top + h;
        MenuState.request();
        build();
    }

    /** Adds vanilla widgets (text fields); painted controls go in {@link #paint}. */
    protected void build() {}

    protected abstract void paint(GuiGraphicsExtractor g, int mouseX, int mouseY);

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        g.fill(0, 0, width, height, Ui.BG_OVERLAY);
        Ui.panel(g, left, top, right, bottom);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        clicks.clear();
        paint(g, mouseX, mouseY);
    }

    //? if >=1.21.9 {
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        return event.button() == 0 && clicks.dispatch(event.x(), event.y());
    }
    //?} else {
    /*@Override
    public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        return button == 0 && clicks.dispatch(x, y);
    }
    *///?}

    protected boolean hovered(int x, int y, int w, int h) {
        return ClickRegions.hover(x, y, w, h, mouseX, mouseY);
    }

    /** Paints a button and registers its click region unless it is disabled. */
    protected void button(GuiGraphicsExtractor g, int x, int y, int w, int h, Component label, Ui.ButtonStyle style, Runnable action) {
        Ui.button(g, font, x, y, w, h, label, style, style != Ui.ButtonStyle.DISABLED && hovered(x, y, w, h));
        if (style != Ui.ButtonStyle.DISABLED) clicks.add(x, y, w, h, action);
    }

    /** Runs a /vml command as the player; the server answers in chat and the next snapshot shows the result. */
    protected void command(String command) {
        if (minecraft.player == null) return;
        minecraft.player.connection.sendCommand(command);
        MenuState.request();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    /** "12s", "5m", "3h", "2d". */
    static String age(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 172800) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }

    /** Status pill text and dot color of a companion. */
    static Component status(MenuState.Mate m) {
        if (m.fallen()) return tr("status.fallen");
        if (!m.active()) return tr("status.away");
        if (!m.present()) return tr("status.arriving");
        if (m.dead()) return tr("status.dead");
        if (m.activity() != null) return tr("status.busy");
        return tr("status.here");
    }

    static int statusDot(MenuState.Mate m) {
        if (m.fallen()) return Ui.DOT_PROBLEM;
        if (!m.active()) return Ui.DOT_OFFLINE;
        if (!m.present()) return Ui.DOT_BUSY;
        if (m.dead()) return Ui.DOT_PROBLEM;
        if (m.activity() != null) return Ui.DOT_BUSY;
        return Ui.DOT_ONLINE;
    }

    /** {@code gather(resource="wood", amount=8)} reads as {@code gather · resource=wood, amount=8}. */
    static String call(String call) {
        int open = call.indexOf('(');
        if (open < 0 || !call.endsWith(")")) return call;
        String args = call.substring(open + 1, call.length() - 1).replace("\"", "");
        return args.isBlank() ? call.substring(0, open) : call.substring(0, open) + " · " + args;
    }
}
