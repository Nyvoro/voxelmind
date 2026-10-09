package com.voxelmind.local.client;

import com.voxelmind.local.client.ui.Ui;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Live view of one companion: what the world shows about its body and what it is doing. */
public final class MonitorScreen extends PanelScreen {
    private static final int LABEL_W = 70;

    private final UUID id;

    public MonitorScreen(Screen parent, UUID id) {
        super(tr("monitor.title"), parent);
        this.id = id;
    }

    @Override
    protected int panelHeight() {
        return 232;
    }

    @Override
    protected void paint(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = left + PAD;
        int xr = right - PAD;
        int y = top + PAD;
        MenuState.View view = MenuState.view();
        MenuState.Mate m = view == null ? null : view.find(id);
        Ui.text(g, font, tr("monitor.title").copy().withStyle(ChatFormatting.BOLD), x, y, Ui.TEXT_PRIMARY);
        int by = bottom - PAD - 20;
        if (m == null) {
            Ui.centered(g, font, tr(view == null ? "loading" : "monitor.gone"), (left + right) / 2, (top + bottom) / 2, Ui.TEXT_SECONDARY);
            button(g, x, by, xr - x, 20, tr("back"), Ui.ButtonStyle.NEUTRAL, this::onClose);
            return;
        }
        Ui.textRight(g, font, Component.literal(m.name()).withStyle(ChatFormatting.BOLD), xr - 22, y, Ui.TEXT_SECONDARY);
        Faces.portrait(g, m, xr - 16, y - 2, 16);
        y += 16;
        Ui.divider(g, x, xr, y);
        y += 8;

        int vx = x + LABEL_W;
        int vw = xr - vx;
        label(g, "monitor.status", x, y);
        Ui.pill(g, font, vx, y, statusDot(m), status(m), Ui.TEXT_PRIMARY);
        y += 14;
        if (m.hosted()) {
            label(g, "monitor.kind", x, y);
            String kind = tr("kind." + m.kind()).getString() + " · " + (m.kind().equals("god")
                    ? tr("campaign." + m.campaign()).getString() + " · " + tr("chaos").getString() + " " + m.chaos() + "/5 · " + tr(m.guests() ? "guests.on" : "guests.off").getString()
                    : tr("body." + m.bodyType()).getString()) + (m.present() ? " · " + tr("monitor.turns", m.turns()).getString() : "");
            y = wrapped(g, kind, vx, y, vw, 2, Ui.TEXT_PRIMARY) - 4;
        } else {
            label(g, "monitor.mode", x, y);
            Ui.text(g, font, tr(m.hardcore() ? "mode.hardcore.short" : "mode.normal.short"), vx, y, m.hardcore() ? Ui.TEXT_DANGER : Ui.TEXT_PRIMARY);
            y += 14;
        }
        label(g, "monitor.worlds", x, y);
        Ui.text(g, font, tr(m.global() ? "worlds.all.short" : "worlds.this.short"), vx, y, m.global() ? Ui.TEXT_ACCENT : Ui.TEXT_PRIMARY);
        y += 14;
        if (m.fallen()) {
            String how = (m.fallenBy().isBlank() ? tr("fallen").getString() : m.fallenBy()) + " · " + age(m.fallenAgoMs());
            y = wrapped(g, "☠ " + how, vx, y, vw, 2, Ui.TEXT_DANGER);
        }

        if (m.hosted() && m.present() && !m.dimension().isEmpty()) {
            label(g, "monitor.position", x, y);
            Ui.text(g, font, Component.literal(m.x() + ", " + m.y() + ", " + m.z() + " · " + m.dimension()), vx, y, Ui.TEXT_PRIMARY);
            y += 14;
        }
        if (m.present() && !m.hosted()) {
            label(g, "monitor.health", x, y);
            Ui.text(g, font, Component.literal(String.format(Locale.ROOT, "%.1f / %.0f ♥", m.hp(), m.maxHp())), vx, y, Ui.TEXT_PRIMARY);
            Ui.bar(g, xr - 100, y + 1, 100, 6, m.maxHp() > 0 ? m.hp() / m.maxHp() : 0, Ui.HEALTH);
            y += 14;
            label(g, "monitor.hunger", x, y);
            Ui.text(g, font, Component.literal(m.food() + " / 20"), vx, y, Ui.TEXT_PRIMARY);
            Ui.bar(g, xr - 100, y + 1, 100, 6, m.food() / 20.0, Ui.HUNGER);
            y += 14;
            label(g, "monitor.position", x, y);
            Ui.text(g, font, Component.literal(m.x() + ", " + m.y() + ", " + m.z() + " · " + m.dimension()), vx, y, Ui.TEXT_PRIMARY);
            y += 14;
        }

        label(g, "monitor.doing", x, y);
        String doing = m.hosted() ? (m.activity() != null ? m.activity().call() : tr(m.present() ? "monitor.waiting" : "monitor.idle").getString())
                : m.activity() != null
                ? call(m.activity().call()) + " · " + m.activity().seconds() + "s" + (m.activity().progress().isBlank() ? "" : " · " + m.activity().progress())
                : tr("monitor.idle").getString();
        y = wrapped(g, doing, vx, y, vw, 3, Ui.TEXT_PRIMARY);

        label(g, "monitor.last", x, y);
        if (m.last() != null) {
            String last = CompanionsScreen.mark(m.last().outcome()) + (m.hosted() ? "" : " " + m.last().tool()) + " (" + age(m.last().agoMs()) + "): " + m.last().result();
            y = wrapped(g, last, vx, y, vw, 4, Ui.TEXT_SECONDARY);
        } else {
            y = wrapped(g, "–", vx, y, vw, 1, Ui.TEXT_MUTED);
        }

        label(g, "monitor.mind", x, y);
        String mind = view.connected() ? CompanionsScreen.model(view.model()) : tr("ai.none").getString();
        Ui.text(g, font, Component.literal(Ui.fit(font, mind, vw)), vx, y, view.connected() ? Ui.TEXT_SECONDARY : Ui.TEXT_DANGER);

        Ui.centered(g, font, tr("monitor.updated", age(System.currentTimeMillis() - view.receivedAt())), (left + right) / 2, by - 12, Ui.TEXT_MUTED);
        int w = (xr - x - 8) / 3;
        button(g, x, by, w, 20, tr("back"), Ui.ButtonStyle.NEUTRAL, this::onClose);
        button(g, x + w + 4, by, w, 20, tr("memories"), Ui.ButtonStyle.GHOST,
                () -> minecraft.gui.setScreen(new MemoriesScreen(this, id)));
        button(g, x + 2 * (w + 4), by, xr - x - 2 * (w + 4), 20, tr(m.present() ? "call_here" : "summon"),
                m.fallen() ? Ui.ButtonStyle.DISABLED : Ui.ButtonStyle.PRIMARY, () -> command("vml summon " + m.name()));
    }

    private void label(GuiGraphicsExtractor g, String key, int x, int y) {
        Ui.text(g, font, tr(key), x, y, Ui.TEXT_MUTED);
    }

    /** Draws up to {@code maxLines} wrapped lines and returns the y below them. */
    private int wrapped(GuiGraphicsExtractor g, String text, int x, int y, int w, int maxLines, int color) {
        List<FormattedCharSequence> lines = font.split(Component.literal(text), w);
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            g.text(font, lines.get(i), x, y + i * 10, color);
        }
        return y + Math.max(1, Math.min(maxLines, lines.size())) * 10 + 4;
    }
}
