package com.voxelmind.local.client;

import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.client.ui.Ui;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Main menu: the player's companions as cards, with summon, dismiss, edit, monitor and delete. */
public final class CompanionsScreen extends PanelScreen {
    private static final int ROW = 42;
    private static final int CARD = 40;
    private static final long FLASH_MS = 6000;

    private int scroll;
    private int visibleRows = 1;
    private Component flash = Component.empty();
    private long flashAt;

    public CompanionsScreen(@Nullable Screen parent) {
        super(Component.literal("VoxelMind"), parent);
    }

    private void flash(Component text) {
        flash = text;
        flashAt = System.currentTimeMillis();
    }

    @Override
    protected void paint(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = left + PAD;
        int xr = right - PAD;
        int y = top + PAD;
        MenuState.View view = MenuState.view();

        Ui.text(g, font, Component.literal("VoxelMind").withStyle(ChatFormatting.BOLD), x, y, Ui.TEXT_PRIMARY);
        if (view != null) aiPill(g, view, xr, y);
        y += 12;
        Ui.text(g, font, tr("subtitle"), x, y, Ui.TEXT_MUTED);
        y += 12;
        Ui.divider(g, x, xr, y);
        y += 6;

        int footerTop = bottom - PAD - 20 - 4 - 14 - 4 - 10;
        if (view == null) {
            Component wait = MenuState.serverHasMod() && !MenuState.noAnswer() ? tr("loading") : tr("no_mod");
            Ui.centered(g, font, wait, (left + right) / 2, (y + footerTop) / 2, Ui.TEXT_SECONDARY);
            footer(g, null, x, xr, footerTop);
            return;
        }

        List<MenuState.Mate> mates = view.mates();
        Ui.section(g, font, tr("section", mates.size()), x, y);
        int listTop = y + 12;
        int listBottom = footerTop - (flashVisible() ? 18 : 6);
        visibleRows = Math.max(1, (listBottom - listTop) / ROW);
        scroll = Math.max(0, Math.min(scroll, mates.size() - visibleRows));
        if (mates.size() > visibleRows) {
            int last = Math.min(mates.size(), scroll + visibleRows);
            Ui.textRight(g, font, Component.literal((scroll + 1) + "–" + last + " / " + mates.size()), xr, y, Ui.TEXT_MUTED);
        }
        if (mates.isEmpty()) {
            int mid = (listTop + listBottom) / 2;
            Ui.centered(g, font, tr("empty.title"), (left + right) / 2, mid - 6, Ui.TEXT_SECONDARY);
            Ui.centered(g, font, tr("empty.hint"), (left + right) / 2, mid + 6, Ui.TEXT_MUTED);
        }
        int rowY = listTop;
        for (int i = scroll; i < Math.min(mates.size(), scroll + visibleRows); i++) {
            card(g, mates.get(i), x, xr, rowY);
            rowY += ROW;
        }
        if (flashVisible()) Ui.centered(g, font, flash, (left + right) / 2, footerTop - 13, Ui.TEXT_ACCENT);
        footer(g, view, x, xr, footerTop);
    }

    private boolean flashVisible() {
        return System.currentTimeMillis() - flashAt < FLASH_MS;
    }

    private void aiPill(GuiGraphicsExtractor g, MenuState.View view, int xr, int y) {
        boolean problem = !view.aiProblem().isEmpty();
        Component label = !view.connected() ? tr("ai.none")
                : view.aiOutOfJoules() ? tr("ai.joules_empty")
                : problem ? tr("ai.problem", model(view.model()))
                : Component.literal(model(view.model()));
        boolean ok = view.connected() && !problem;
        int w = Ui.pillWidth(font, label);
        int px = xr - w;
        boolean hover = hovered(px - 2, y - 2, w + 4, 12);
        Ui.pill(g, font, px, y, ok ? Ui.DOT_ONLINE : Ui.DOT_PROBLEM, label,
                hover && view.host() ? Ui.TEXT_PRIMARY : ok ? Ui.TEXT_SECONDARY : Ui.TEXT_DANGER);
        if (hover && problem) {
            g.setComponentTooltipForNextFrame(font, List.of(Component.literal(Ui.fit(font, view.aiProblem(), 2000))), px, y + 14);
        }
        if (view.host()) clicks.add(px - 2, y - 2, w + 4, 12, () -> minecraft.gui.setScreen(new SettingsScreen(this)));
    }

    /** "deepseek/deepseek-v4-flash-0731" shows as "deepseek-v4-flash-0731". */
    static String model(String id) {
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private void card(GuiGraphicsExtractor g, MenuState.Mate m, int x, int xr, int y) {
        Ui.card(g, x, y, xr, y + CARD, hovered(x, y, xr - x, CARD));
        Faces.portrait(g, m, x + 6, y + 6, 28);
        Component status = status(m);
        int pillW = Ui.pillWidth(font, status);
        Ui.pill(g, font, xr - 6 - pillW, y + 6, statusDot(m), status, Ui.TEXT_SECONDARY);
        boolean tagged = m.hardcore() || m.hosted();
        Component tag = m.hosted() ? tr("tag." + m.kind()) : m.hardcore() ? tr("mode.tag") : Component.empty();
        Component worlds = m.global() ? Component.literal("∞") : Component.empty();
        int tagW = (tagged ? font.width(tag) + 6 : 0) + (m.global() ? font.width(worlds) + 6 : 0);
        String name = Ui.fit(font, m.name(), xr - x - 52 - pillW - tagW);
        int tx = x + 40 + font.width(Component.literal(name).withStyle(ChatFormatting.BOLD)) + 6;
        Ui.text(g, font, Component.literal(name).withStyle(ChatFormatting.BOLD), x + 40, y + 6, Ui.TEXT_PRIMARY);
        if (m.global()) {
            Ui.text(g, font, worlds, tx, y + 6, Ui.TEXT_ACCENT);
            if (hovered(tx - 1, y + 5, font.width(worlds) + 2, 10)) g.setComponentTooltipForNextFrame(font, List.of(tr("worlds.all.hint")), tx, y + 20);
            tx += font.width(worlds) + 6;
        }
        if (tagged) Ui.text(g, font, tag, tx, y + 6, m.hosted() ? (m.kind().equals("god") ? Ui.TEXT_WARN : Ui.TEXT_ACCENT) : Ui.TEXT_DANGER);
        Ui.text(g, font, Component.literal(Ui.fit(font, info(m), xr - x - 46)), x + 40, y + 17, Ui.TEXT_MUTED);

        int by = y + 26;
        int bx = xr - 6 - 60;
        if (m.fallen()) {
            button(g, bx, by, 60, 12, tr("summon"), Ui.ButtonStyle.DISABLED, () -> {});
        } else if (m.active() && m.present()) {
            button(g, bx, by, 60, 12, tr("dismiss"), Ui.ButtonStyle.DANGER, () -> {
                command("vml dismiss " + m.name());
                flash(tr("flash.dismiss", m.name()));
            });
        } else {
            button(g, bx, by, 60, 12, tr("summon"), Ui.ButtonStyle.PRIMARY, () -> {
                command("vml summon " + m.name());
                flash(tr("flash.summon", m.name()));
            });
        }
        bx -= 4 + 48;
        button(g, bx, by, 48, 12, tr("edit"), Ui.ButtonStyle.GHOST,
                () -> minecraft.gui.setScreen(new CompanionEditScreen(this, m)));
        if (!m.present()) {
            bx -= 4 + 48;
            button(g, bx, by, 48, 12, tr("delete"), Ui.ButtonStyle.GHOST, () -> confirmDelete(m));
        }
        button(g, x + 40, by, 52, 12, tr("monitor"), Ui.ButtonStyle.GHOST,
                () -> minecraft.gui.setScreen(new MonitorScreen(this, m.id())));
    }

    private String info(MenuState.Mate m) {
        if (m.fallen()) return "☠ " + (m.fallenBy().isBlank() ? tr("fallen").getString() : m.fallenBy()) + " · " + age(m.fallenAgoMs());
        if (m.hosted() && m.activity() != null) return m.activity().call();
        if (m.activity() != null) return call(m.activity().call()) + " · " + m.activity().seconds() + "s";
        if (m.hosted() && m.last() != null) return mark(m.last().outcome()) + " " + age(m.last().agoMs()) + " · " + m.last().result();
        if (m.last() != null) return mark(m.last().outcome()) + " " + m.last().tool() + " · " + age(m.last().agoMs());
        if (!m.persona().isBlank()) return m.persona();
        return tr("info.new").getString();
    }

    static String mark(String outcome) {
        return switch (outcome) {
            case "done" -> "✓";
            case "preempted" -> "⊘";
            default -> "✗";
        };
    }

    private void confirmDelete(MenuState.Mate m) {
        minecraft.gui.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                command("vml forget " + m.name());
                flash(tr("flash.delete", m.name()));
            }
            minecraft.gui.setScreen(this);
        }, tr("delete.title", m.name()), tr(m.global() ? "delete.body.global" : "delete.body", m.name())));
    }

    private void footer(GuiGraphicsExtractor g, MenuState.@Nullable View view, int x, int xr, int y) {
        boolean ready = view != null;
        button(g, x, y, xr - x, 20, tr("new"), ready ? Ui.ButtonStyle.PRIMARY : Ui.ButtonStyle.DISABLED,
                () -> minecraft.gui.setScreen(new CompanionEditScreen(this, null)));
        int ly = y + 24;
        int w = (xr - x - 8) / 3;
        boolean host = view != null && view.host();
        button(g, x, ly, w, 14, tr("ai_settings"), host ? Ui.ButtonStyle.GHOST : Ui.ButtonStyle.DISABLED,
                () -> minecraft.gui.setScreen(new SettingsScreen(this)));
        button(g, x + w + 4, ly, w, 14, tr("commands"), Ui.ButtonStyle.GHOST, () -> {
            onClose();
            command("vml help");
        });
        button(g, x + 2 * (w + 4), ly, xr - x - 2 * (w + 4), 14, tr("close"), Ui.ButtonStyle.GHOST, this::onClose);
        links(g, ly + 14 + 5);
    }

    /**
     * Text links under the buttons, as in the original mod: feature requests on Discord, donations on Ko-fi, and the game
     * VoxelMind is becoming (its hover says so in one sentence).
     */
    private void links(GuiGraphicsExtractor g, int y) {
        Component[] labels = {tr("feature_request"), Component.empty().append(Component.literal("♥ ").withStyle(ChatFormatting.RED)).append(tr("support")),
                tr("game")};
        String[] urls = {VoxelMindLocal.FEATURE_REQUEST_URL, VoxelMindLocal.SUPPORT_URL, VoxelMindLocal.GAME_URL};
        Component[] hovers = {Component.literal(urls[0]), Component.literal(urls[1]),
                Component.translatable("voxelmind_local.game").append(" voxel-mind.com")};
        int sep = 14;
        int total = sep * (labels.length - 1);
        for (Component l : labels) total += font.width(l);
        int x = (left + right - total) / 2;
        for (int i = 0; i < labels.length; i++) {
            int w = font.width(labels[i]);
            boolean hover = hovered(x - 2, y - 2, w + 4, 12);
            Ui.text(g, font, labels[i], x, y, hover ? Ui.TEXT_PRIMARY : Ui.TEXT_SECONDARY);
            String url = urls[i];
            if (hover) g.setTooltipForNextFrame(font, font.split(hovers[i], 240), x, y + 14);
            clicks.add(x - 2, y - 2, w + 4, 12, () -> Util.getPlatform().openUri(url));
            x += w + sep;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll += scrollY < 0 ? 1 : -1;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
