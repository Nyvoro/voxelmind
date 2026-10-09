package com.voxelmind.local.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
//? if <26.1 {
/*import net.minecraft.util.FormattedCharSequence;
*///?}

import java.util.Locale;

/** The VoxelMind look from the original mod: dark panels, emerald accent, painted buttons, status pills. */
public final class Ui {
    public static final int BG_OVERLAY = 0x66000000;
    public static final int PANEL_BG = 0xF20F1418;
    public static final int PANEL_BORDER = 0xFF2A333B;
    public static final int CARD_BG = 0xC819212A;
    public static final int CARD_BG_HOVER = 0xDC232E39;
    public static final int CARD_BORDER = 0x5A3A4854;
    public static final int DIVIDER = 0xFF232B33;
    public static final int WELL_BG = 0xE60B0F13;

    public static final int ACCENT_FILL = 0xFF1E7A52;
    public static final int ACCENT_FILL_HOVER = 0xFF27965F;
    public static final int ACCENT_EDGE_TOP = 0xFF3FBF84;
    public static final int ACCENT_EDGE_BOT = 0xFF10402C;
    public static final int ACCENT_SOFT_BG = 0x3D34D399;
    public static final int ACCENT_SOFT_EDGE = 0xB034D399;

    public static final int DANGER_FILL = 0xFF7A2D2D;
    public static final int DANGER_FILL_HOVER = 0xFF9A3D3D;
    public static final int DANGER_EDGE_TOP = 0xFFBB5555;
    public static final int DANGER_EDGE_BOT = 0xFF551111;
    public static final int NEUTRAL_FILL = 0xFF2A333C;
    public static final int NEUTRAL_FILL_HOVER = 0xFF37424D;
    public static final int NEUTRAL_EDGE_TOP = 0xFF4A5866;
    public static final int NEUTRAL_EDGE_BOT = 0xFF161C22;
    public static final int DISABLED_FILL = 0xFF23282E;

    public static final int DOT_ONLINE = 0xFF3FBF84;
    public static final int DOT_OFFLINE = 0xFF6B7680;
    public static final int DOT_PROBLEM = 0xFFE05252;
    public static final int DOT_BUSY = 0xFFCFA43B;

    public static final int HEALTH = 0xFFD9443B;
    public static final int HUNGER = 0xFFD98A2B;

    public static final int TEXT_PRIMARY = 0xFFFFFFFF;
    public static final int TEXT_SECONDARY = 0xFFB8C2CC;
    public static final int TEXT_MUTED = 0xFF77838D;
    public static final int TEXT_ACCENT = 0xFF4ADE9E;
    public static final int TEXT_DANGER = 0xFFFF5555;
    public static final int TEXT_WARN = 0xFFE8C15A;

    public enum ButtonStyle { PRIMARY, DANGER, NEUTRAL, GHOST, DISABLED }

    private Ui() {}

    public static void panel(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, PANEL_BG);
        border(g, x1, y1, x2, y2, PANEL_BORDER);
    }

    public static void card(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, boolean hovered) {
        g.fill(x1, y1, x2, y2, hovered ? CARD_BG_HOVER : CARD_BG);
        border(g, x1, y1, x2, y2, CARD_BORDER);
    }

    public static void well(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, WELL_BG);
        border(g, x1, y1, x2, y2, 0xFF1C232A);
    }

    public static void border(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
        g.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
    }

    public static void divider(GuiGraphicsExtractor g, int x1, int x2, int y) {
        g.fill(x1, y, x2, y + 1, DIVIDER);
    }

    /** Base fill, light top edge, dark bottom edge, centred label. Hit-testing belongs to the caller. */
    public static void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, Component label,
                              ButtonStyle style, boolean hovered) {
        int fill;
        int top;
        int bottom;
        int text = TEXT_PRIMARY;
        switch (style) {
            case PRIMARY -> {
                fill = hovered ? ACCENT_FILL_HOVER : ACCENT_FILL;
                top = ACCENT_EDGE_TOP;
                bottom = ACCENT_EDGE_BOT;
            }
            case DANGER -> {
                fill = hovered ? DANGER_FILL_HOVER : DANGER_FILL;
                top = DANGER_EDGE_TOP;
                bottom = DANGER_EDGE_BOT;
            }
            case DISABLED -> {
                fill = DISABLED_FILL;
                top = 0xFF2E353C;
                bottom = 0xFF15191E;
                text = TEXT_MUTED;
            }
            case GHOST -> {
                g.fill(x, y, x + w, y + h, hovered ? NEUTRAL_FILL_HOVER : 0x66232B33);
                border(g, x, y, x + w, y + h, hovered ? NEUTRAL_EDGE_TOP : CARD_BORDER);
                centered(g, font, label, x + w / 2, y + (h - 8) / 2, hovered ? TEXT_PRIMARY : TEXT_SECONDARY);
                return;
            }
            default -> {
                fill = hovered ? NEUTRAL_FILL_HOVER : NEUTRAL_FILL;
                top = NEUTRAL_EDGE_TOP;
                bottom = NEUTRAL_EDGE_BOT;
            }
        }
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w, y + 1, top);
        g.fill(x, y + h - 1, x + w, y + h, bottom);
        centered(g, font, label, x + w / 2, y + (h - 8) / 2, text);
    }

    /** Coloured 4px dot and a word. */
    public static void pill(GuiGraphicsExtractor g, Font font, int x, int y, int dot, Component label, int color) {
        g.fill(x, y + 2, x + 4, y + 6, dot);
        g.text(font, label, x + 7, y, color);
    }

    public static int pillWidth(Font font, Component label) {
        return 7 + font.width(label);
    }

    /** A horizontal gauge in a well, {@code fraction} of the inner width filled with {@code color}. */
    public static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, double fraction, int color) {
        well(g, x, y, x + w, y + h);
        int inner = (int) Math.round((w - 2) * Math.max(0, Math.min(1, fraction)));
        if (inner > 0) g.fill(x + 1, y + 1, x + 1 + inner, y + h - 1, color);
    }

    public static void text(GuiGraphicsExtractor g, Font font, Component t, int x, int y, int color) {
        g.text(font, t, x, y, color);
    }

    public static void textRight(GuiGraphicsExtractor g, Font font, Component t, int right, int y, int color) {
        g.text(font, t, right - font.width(t), y, color);
    }

    public static void centered(GuiGraphicsExtractor g, Font font, Component t, int centerX, int y, int color) {
        g.text(font, t, centerX - font.width(t) / 2, y, color);
    }

    /** Wrapped text, one drop-shadowed line after the other (the old drawWordWrap drew without shadow). */
    public static void wordWrap(GuiGraphicsExtractor g, Font font, FormattedText text, int x, int y, int width, int color) {
        //? if >=26.1 {
        g.textWithWordWrap(font, text, x, y, width, color);
        //?} else {
        /*for (FormattedCharSequence line : font.split(text, width)) {
            g.text(font, line, x, y, color);
            y += font.lineHeight;
        }
        *///?}
    }

    /** Muted all-caps section label. */
    public static void section(GuiGraphicsExtractor g, Font font, Component label, int x, int y) {
        g.text(font, Component.literal(label.getString().toUpperCase(Locale.ROOT)), x, y, TEXT_MUTED);
    }

    /** {@code text} cut to {@code width} pixels with an ellipsis. */
    public static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }
}
