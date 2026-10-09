package com.voxelmind.local.client;

import com.voxelmind.local.client.ui.Ui;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a companion remembers, read-only: the long-term memory it rewrites itself, what it knows about
 * its owner, and the newest events of its timeline.
 */
public final class MemoriesScreen extends PanelScreen {
    private static final int LINE = 10;

    private record Line(FormattedCharSequence text, int color, int indent) {}

    private final UUID id;
    private int scroll;

    public MemoriesScreen(Screen parent, UUID id) {
        super(tr("memories.title"), parent);
        this.id = id;
    }

    private List<Line> lines(MenuState.View view, MenuState.Mate m, int width) {
        List<Line> out = new ArrayList<>();
        heading(out, tr("memories.self"), width);
        body(out, m.previously().isBlank() ? tr("memories.none").getString() : m.previously(), width, m.previously().isBlank());
        out.add(new Line(FormattedCharSequence.EMPTY, 0, 0));
        heading(out, tr("memories.owner", view.owner()), width);
        body(out, m.knowingOwner().isBlank() ? tr("memories.none").getString() : m.knowingOwner(), width, m.knowingOwner().isBlank());
        out.add(new Line(FormattedCharSequence.EMPTY, 0, 0));
        heading(out, tr("memories.recent", m.timeline().size()), width);
        for (MenuState.Row row : m.timeline()) {
            String text = age(row.agoMs()) + "  " + row.line();
            int color = row.fromOwner() ? Ui.TEXT_ACCENT : "result".equals(row.kind()) ? Ui.TEXT_MUTED : Ui.TEXT_SECONDARY;
            List<FormattedCharSequence> wrapped = font.split(Component.literal(text), width - 6);
            for (int i = 0; i < wrapped.size(); i++) out.add(new Line(wrapped.get(i), color, i == 0 ? 0 : 6));
        }
        return out;
    }

    private void heading(List<Line> out, Component text, int width) {
        out.add(new Line(text.copy().withStyle(ChatFormatting.BOLD).getVisualOrderText(), Ui.TEXT_PRIMARY, 0));
    }

    private void body(List<Line> out, String text, int width, boolean muted) {
        for (FormattedCharSequence line : font.split(Component.literal(text), width)) {
            out.add(new Line(line, muted ? Ui.TEXT_MUTED : Ui.TEXT_SECONDARY, 0));
        }
    }

    @Override
    protected void paint(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = left + PAD;
        int xr = right - PAD;
        int y = top + PAD;
        MenuState.View view = MenuState.view();
        MenuState.Mate m = view == null ? null : view.find(id);
        Ui.text(g, font, tr("memories.title").copy().withStyle(ChatFormatting.BOLD), x, y, Ui.TEXT_PRIMARY);
        int by = bottom - PAD - 20;
        if (m == null) {
            Ui.centered(g, font, tr(view == null ? "loading" : "monitor.gone"), (left + right) / 2, (top + bottom) / 2, Ui.TEXT_SECONDARY);
            button(g, x, by, xr - x, 20, tr("back"), Ui.ButtonStyle.NEUTRAL, this::onClose);
            return;
        }
        Ui.textRight(g, font, Component.literal(m.name()).withStyle(ChatFormatting.BOLD), xr, y, Ui.TEXT_SECONDARY);
        y += 12;
        String rewritten = m.memoryAgoMs() < 0 ? tr("memories.never").getString() : tr("memories.rewritten", age(m.memoryAgoMs())).getString();
        Ui.text(g, font, Component.literal(rewritten), x, y, Ui.TEXT_MUTED);
        y += 12;
        Ui.divider(g, x, xr, y);
        y += 6;

        List<Line> lines = lines(view, m, xr - x - 6);
        int areaBottom = by - 16;
        int visible = Math.max(1, (areaBottom - y) / LINE);
        scroll = Math.max(0, Math.min(scroll, lines.size() - visible));
        for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++) {
            Line line = lines.get(i);
            g.text(font, line.text(), x + line.indent(), y + (i - scroll) * LINE, line.color());
        }
        if (lines.size() > visible) {
            int trackH = visible * LINE;
            int thumbH = Math.max(8, trackH * visible / lines.size());
            int thumbY = y + (trackH - thumbH) * scroll / Math.max(1, lines.size() - visible);
            g.fill(xr - 2, y, xr, y + trackH, Ui.DIVIDER);
            g.fill(xr - 2, thumbY, xr, thumbY + thumbH, Ui.NEUTRAL_EDGE_TOP);
        }
        Ui.centered(g, font, tr("memories.hint", m.name()), (left + right) / 2, by - 12, Ui.TEXT_MUTED);
        button(g, x, by, xr - x, 20, tr("back"), Ui.ButtonStyle.NEUTRAL, this::onClose);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll += scrollY < 0 ? 3 : -3;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
