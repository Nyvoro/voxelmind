package com.voxelmind.local.client;

import com.voxelmind.local.client.ui.Ui;
import com.voxelmind.local.hosted.HostedTexts;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Create a companion, builder or god, or change an existing one: name, look, character and the settings
 * of its kind. Saving runs the same /vml commands a player could type; the character text is the owner's,
 * the model reads it as persona.
 */
public final class CompanionEditScreen extends PanelScreen {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** A Minecraft account, a PNG link or a .png from the skins folder, optionally followed by "slim" or "classic" (see Skins.resolve). */
    private static final Pattern SKIN = Pattern.compile("(?i)([A-Za-z0-9_]{3,16}|https?://\\S{4,190}|[^/\\\\:*?\"<>|]{1,80}\\.png)( (slim|classic))?");
    private static final int MAX_PERSONA = 500;
    private static final String[] CHIPS = {"buddy", "jester", "explorer", "builder", "guardian", "quiet"};
    private static final String[] KINDS = {"companion", "builder", "god"};
    private static final String[] BODIES = {"allay", "bee", "vex", "parrot", "bat"};
    private static final String[] CAMPAIGNS = {"sandbox", "trials", "living", "chaos"};

    private record Chip(int x, int y, int w, String id) {}

    private static final int MODE_W = 112;

    private final MenuState.@Nullable Mate mate;
    private String kind;
    private String mode;
    private String scope;
    private String body;
    private String campaign;
    private int chaos;
    private boolean guests;
    private String name = "";
    private String account = "";
    private String persona;
    private Component error = Component.empty();
    private final List<Chip> chips = new ArrayList<>();
    private int kindY;
    private int nameY;
    private int lookY;
    /** The brain row's top, or -1 when this kind has none (Builder and God think with their own hosted minds). */
    private int brainY = -1;
    private String brain;
    /** The brain list is open below its field; it then takes every click until something is picked or it is closed. */
    private boolean brainOpen;
    private int characterY;
    private int personaBottom;
    private EditBox nameBox;
    private MultiLineEditBox personaBox;

    /** {@code mate} null creates a new companion. */
    public CompanionEditScreen(Screen parent, MenuState.@Nullable Mate mate) {
        super(mate == null ? tr("create.title") : tr("edit.title", mate.name()), parent);
        this.mate = mate;
        this.persona = mate == null ? "" : mate.persona();
        MenuState.View view = MenuState.view();
        this.kind = mate != null ? mate.kind() : "companion";
        this.mode = mate != null ? mate.mode() : view != null && view.worldHardcore() ? "hardcore" : "normal";
        this.scope = mate != null ? mate.scope() : "world";
        this.account = mate != null ? mate.skinSource() : "";
        this.body = mate != null ? mate.bodyType() : "allay";
        this.campaign = mate != null ? mate.campaign() : "sandbox";
        this.chaos = mate != null ? mate.chaos() : 3;
        this.guests = mate == null || mate.guests();
        this.brain = mate != null && !mate.brain().isEmpty() ? mate.brain() : "default";
    }

    private boolean hosted() {
        return !kind.equals("companion");
    }

    @Override
    protected int panelHeight() {
        return kind.equals("companion") ? 268 : 240;
    }

    @Override
    protected int panelWidth() {
        // Wide enough for the character chips in one row and a readable look field.
        return 400;
    }

    @Override
    protected void build() {
        int x = left + PAD;
        int w = right - left - 2 * PAD;
        int y = top + PAD + 30;

        kindY = top + PAD + 11;

        nameY = y;
        if (mate == null) {
            nameBox = new EditBox(font, x, y + 10, w - MODE_W - 6, 18, tr("name"));
            nameBox.setMaxLength(16);
            nameBox.setHint(tr("name.hint"));
            nameBox.setValue(name);
            nameBox.setResponder(v -> name = v);
            addRenderableWidget(nameBox);
            setInitialFocus(nameBox);
        }
        y += 36;

        lookY = y;
        if (kind.equals("companion")) {
            EditBox skinBox = new EditBox(font, x + 24, y + 10, w - 24 - MODE_W - 6, 18, tr("skin"));
            skinBox.setMaxLength(200);
            skinBox.setHint(tr("skin.hint"));
            skinBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr("skin.tooltip")));
            skinBox.setValue(account);
            skinBox.setResponder(v -> account = v);
            addRenderableWidget(skinBox);
        }
        y += 36;

        brainY = -1;
        if (kind.equals("companion")) {
            brainY = y;
            y += 33;
        }

        characterY = y;
        chips.clear();
        int cx = x;
        int cy = y + 10;
        for (String id : CHIPS) {
            int cw = font.width(tr("chip." + id)) + 10;
            if (cx + cw > x + w) {
                cx = x;
                cy += 17;
            }
            chips.add(new Chip(cx, cy, cw, id));
            cx += cw + 4;
        }
        int boxY = cy + 18;
        // The text box takes the room left above the buttons, so on a short screen it shrinks instead of covering them.
        int boxH = Math.max(24, Math.min(60, bottom - PAD - 20 - 14 - boxY));
        if (hosted()) {
            // The character chips describe a player companion; a builder or god gets the space for its text.
            chips.clear();
            boxH += boxY - (y + 10);
            boxY = y + 10;
        }
        //? if >=1.21.6 {
        personaBox = MultiLineEditBox.builder().setX(x).setY(boxY).setPlaceholder(tr(hosted() ? "persona.hint." + kind : "persona.hint"))
                .setShowDecorations(false).build(font, w, boxH, tr("persona"));
        //?} else {
        /*// Before 1.21.6 the box has no builder and always draws its own frame.
        personaBox = new MultiLineEditBox(font, x, boxY, w, boxH, tr(hosted() ? "persona.hint." + kind : "persona.hint"), tr("persona"));
        *///?}
        //? if >=1.21.6 {
        personaBox.setCharacterLimit(MAX_PERSONA);
        personaBox.setValue(persona);
        personaBox.setValueListener(v -> persona = v);
        //?} else {
        /*// A character limit on the old box would draw a second counter under it, so the limit is applied here.
        personaBox.setValue(persona.length() > MAX_PERSONA ? persona.substring(0, MAX_PERSONA) : persona);
        personaBox.setValueListener(v -> {
            if (v.length() > MAX_PERSONA) personaBox.setValue(v.substring(0, MAX_PERSONA));
            else persona = v;
        });
        *///?}
        addRenderableWidget(personaBox);
        personaBottom = boxY + boxH;
    }

    @Override
    protected void paint(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = left + PAD;
        int xr = right - PAD;
        int y = top + PAD;
        Ui.text(g, font, title.copy().withStyle(ChatFormatting.BOLD), x, y, Ui.TEXT_PRIMARY);
        if (mate == null) {
            kindTabs(g, x, xr, kindY);
        } else {
            Component subtitle = hosted() ? tr("kind." + kind).copy().append(" · ").append(tr("edit.subtitle")) : tr("edit.subtitle");
            Ui.text(g, font, subtitle, x, y + 12, Ui.TEXT_MUTED);
        }
        Ui.divider(g, x, xr, y + 24);

        Ui.section(g, font, tr("name"), x, nameY);
        switch (kind) {
            case "builder" -> bodyCycle(g, xr - MODE_W, nameY);
            case "god" -> chaosChips(g, xr - MODE_W, nameY);
            default -> modeChips(g, xr - MODE_W, nameY);
        }
        if (mate == null) {
            Ui.textRight(g, font, Component.literal(name.length() + "/16"), xr - MODE_W - 6, nameY, name.length() >= 16 ? Ui.TEXT_WARN : Ui.TEXT_MUTED);
        } else {
            Ui.text(g, font, Component.literal(mate.name()).withStyle(ChatFormatting.BOLD), x, nameY + 14, Ui.TEXT_PRIMARY);
            Ui.text(g, font, tr("name.fixed"), x + font.width(mate.name()) + 12, nameY + 14, Ui.TEXT_MUTED);
        }

        switch (kind) {
            case "builder" -> {
                Ui.section(g, font, tr("builder.section"), x, lookY);
                Ui.text(g, font, Component.literal(Ui.fit(font, tr("builder.about").getString(), xr - MODE_W - 6 - x)), x, lookY + 15, Ui.TEXT_SECONDARY);
            }
            case "god" -> campaignRow(g, x, xr - MODE_W - 6, lookY);
            default -> {
                Ui.section(g, font, tr("skin"), x, lookY);
                PlayerFaceExtractor.extractRenderState(g, mate != null ? Faces.of(mate) : DefaultPlayerSkin.getDefaultSkin(), x, lookY + 10, 18);
            }
        }
        worldChips(g, xr - MODE_W, lookY);
        if (brainY >= 0) brainRow(g, x, xr, brainY);
        // The character box lies under the open list; hidden, it cannot catch the list's clicks.
        if (personaBox != null) personaBox.visible = !brainOpen;

        Ui.section(g, font, tr("character"), x, characterY);
        Ui.textRight(g, font, Component.literal(persona.length() + "/" + MAX_PERSONA), xr, characterY, Ui.TEXT_MUTED);
        for (Chip chip : chips) {
            String text = tr("chip." + chip.id() + ".text").getString();
            boolean selected = persona.trim().equals(text);
            boolean hover = hovered(chip.x(), chip.y(), chip.w(), 14);
            g.fill(chip.x(), chip.y(), chip.x() + chip.w(), chip.y() + 14, selected ? Ui.ACCENT_SOFT_BG : hover ? Ui.NEUTRAL_FILL_HOVER : 0x66232B33);
            Ui.border(g, chip.x(), chip.y(), chip.x() + chip.w(), chip.y() + 14, selected ? Ui.ACCENT_SOFT_EDGE : Ui.CARD_BORDER);
            Ui.centered(g, font, tr("chip." + chip.id()), chip.x() + chip.w() / 2, chip.y() + 3, selected ? Ui.TEXT_ACCENT : Ui.TEXT_SECONDARY);
            clicks.add(chip.x(), chip.y(), chip.w(), 14, () -> personaBox.setValue(text));
        }

        if (!error.getString().isEmpty()) Ui.text(g, font, error, x, personaBottom + 4, Ui.TEXT_DANGER);
        int by = bottom - PAD - 20;
        int half = (xr - x - 4) / 2;
        button(g, x, by, half, 20, tr("cancel"), Ui.ButtonStyle.NEUTRAL, this::onClose);
        button(g, x + half + 4, by, xr - x - half - 4, 20, tr(mate == null ? "create" : "save"), Ui.ButtonStyle.PRIMARY, this::save);
        brainDropdown(g, x, xr);
    }

    /** Companion, builder or god in the subtitle row; the latter two only for the host or an operator, since they get console power. */
    private void kindTabs(GuiGraphicsExtractor g, int x, int xr, int y) {
        MenuState.View view = MenuState.view();
        boolean mayHost = view != null && view.mayHost();
        int cw = (xr - x - 8) / 3;
        for (int i = 0; i < KINDS.length; i++) {
            String id = KINDS[i];
            int cx = x + i * (cw + 4);
            int width = i == KINDS.length - 1 ? xr - cx : cw;
            boolean locked = !id.equals("companion") && !mayHost;
            boolean selected = kind.equals(id);
            boolean hover = hovered(cx, y, width, 12);
            g.fill(cx, y, cx + width, y + 12, selected ? Ui.ACCENT_SOFT_BG : hover && !locked ? Ui.NEUTRAL_FILL_HOVER : 0x66232B33);
            Ui.border(g, cx, y, cx + width, y + 12, selected ? Ui.ACCENT_SOFT_EDGE : Ui.CARD_BORDER);
            Ui.centered(g, font, tr("kind." + id), cx + width / 2, y + 2, selected ? Ui.TEXT_ACCENT : locked ? Ui.TEXT_MUTED : Ui.TEXT_SECONDARY);
            if (hover) g.setComponentTooltipForNextFrame(font, List.of(tr(locked ? "kind.locked" : "kind." + id + ".hint")), cx, y + 24);
            if (!locked && !selected) {
                clicks.add(cx, y, width, 12, () -> {
                    kind = id;
                    error = Component.empty();
                    rebuildWidgets();
                });
            }
        }
    }

    /** Normal or hardcore; a companion that already fell in hardcore keeps its mode for good. */
    private void modeChips(GuiGraphicsExtractor g, int x, int y) {
        Ui.section(g, font, tr("mode"), x, y);
        boolean locked = mate != null && mate.fallen();
        int cw = (MODE_W - 4) / 2;
        String[] modes = {"normal", "hardcore"};
        for (int i = 0; i < 2; i++) {
            String id = modes[i];
            int cx = x + i * (cw + 4);
            int cy = y + 10;
            boolean selected = mode.equals(id);
            boolean hover = !locked && hovered(cx, cy, cw, 18);
            int bg = selected ? (id.equals("hardcore") ? 0x3DE05252 : Ui.ACCENT_SOFT_BG) : hover ? Ui.NEUTRAL_FILL_HOVER : 0x66232B33;
            int edge = selected ? (id.equals("hardcore") ? 0xB0E05252 : Ui.ACCENT_SOFT_EDGE) : Ui.CARD_BORDER;
            g.fill(cx, cy, cx + cw, cy + 18, bg);
            Ui.border(g, cx, cy, cx + cw, cy + 18, edge);
            int color = selected ? (id.equals("hardcore") ? Ui.TEXT_DANGER : Ui.TEXT_ACCENT) : locked ? Ui.TEXT_MUTED : Ui.TEXT_SECONDARY;
            Ui.centered(g, font, tr("mode." + id), cx + cw / 2, cy + 5, color);
            if (hovered(cx, cy, cw, 18)) {
                g.setComponentTooltipForNextFrame(font, List.of(tr("mode." + id + ".hint")), cx, cy + 30);
            }
            if (!locked) clicks.add(cx, cy, cw, 18, () -> mode = id);
        }
    }

    /** The builder's creature; a click shows the next one. */
    private List<MenuState.BrainInfo> brains() {
        MenuState.View view = MenuState.view();
        return view != null ? view.brains() : List.of();
    }

    /** For the client smoke test: shows the open brain list. */
    void openBrainList() {
        brainOpen = true;
    }

    private MenuState.@Nullable BrainInfo brainInfo(String id) {
        return brains().stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }

    /** Which brain the companion thinks with: a field that opens the list of brains, and what the chosen one is made for. */
    private void brainRow(GuiGraphicsExtractor g, int x, int xr, int y) {
        Ui.section(g, font, tr("brain"), x, y);
        MenuState.BrainInfo current = brainInfo(brain);
        int fy = y + 10;
        int fw = xr - x;
        boolean hover = !brainOpen && hovered(x, fy, fw, 18);
        g.fill(x, fy, x + fw, fy + 18, brainOpen || hover ? Ui.CARD_BG_HOVER : Ui.CARD_BG);
        Ui.border(g, x, fy, x + fw, fy + 18, brainOpen ? Ui.ACCENT_SOFT_EDGE : Ui.CARD_BORDER);
        String name = current != null ? current.name() : brain;
        Ui.text(g, font, Component.literal(Ui.fit(font, name, fw - 30)), x + 6, fy + 5, Ui.TEXT_PRIMARY);
        Ui.textRight(g, font, Component.literal(brainOpen ? "▴" : "▾"), x + fw - 6, fy + 5, Ui.TEXT_ACCENT);
        if (hover && current != null) {
            List<Component> lines = new java.util.ArrayList<>();
            if (!current.description().isEmpty()) lines.add(Component.literal(current.description()));
            if (!current.models().isEmpty()) lines.add(tr("brain.models", current.models()));
            lines.add(tr("brain.hint"));
            g.setComponentTooltipForNextFrame(font, lines, x, fy + 30);
        }
        if (!brainOpen) clicks.add(x, fy, fw, 18, () -> brainOpen = true);
    }

    /** The open brain list, drawn over everything below the field; registered last, so it gets the clicks. */
    private void brainDropdown(GuiGraphicsExtractor g, int x, int xr) {
        if (!brainOpen || brainY < 0) return;
        List<MenuState.BrainInfo> list = brains();
        int fw = xr - x;
        int ly = brainY + 10 + 19;
        int rowH = 28;
        int lh = Math.max(1, list.size()) * rowH + 4;
        // A click anywhere else closes the list without passing through to what lies under it.
        clicks.add(0, 0, width, height, () -> brainOpen = false);
        g.fill(x - 1, ly - 1, x + fw + 1, ly + lh + 1, 0xFF000000);
        g.fill(x, ly, x + fw, ly + lh, Ui.PANEL_BG);
        Ui.border(g, x, ly, x + fw, ly + lh, Ui.ACCENT_SOFT_EDGE);
        int ry = ly + 2;
        for (MenuState.BrainInfo b : list) {
            boolean selected = b.id().equals(brain);
            boolean hover = hovered(x + 2, ry, fw - 4, rowH);
            if (selected || hover) g.fill(x + 2, ry, x + fw - 2, ry + rowH, selected ? Ui.ACCENT_SOFT_BG : Ui.NEUTRAL_FILL_HOVER);
            String title = b.name() + (b.version().isEmpty() ? "" : "  " + b.version()) + (b.builtin() ? "" : "  · " + tr("brain.own").getString());
            Ui.text(g, font, Component.literal(Ui.fit(font, title, fw - 16)), x + 7, ry + 4, selected ? Ui.TEXT_ACCENT : Ui.TEXT_PRIMARY);
            Ui.text(g, font, Component.literal(Ui.fit(font, b.description(), fw - 16)), x + 7, ry + 15, Ui.TEXT_MUTED);
            String id = b.id();
            clicks.add(x + 2, ry, fw - 4, rowH, () -> {
                brain = id;
                brainOpen = false;
            });
            ry += rowH;
        }
    }

    private void bodyCycle(GuiGraphicsExtractor g, int x, int y) {
        Ui.section(g, font, tr("body"), x, y);
        int cy = y + 10;
        boolean hover = hovered(x, cy, MODE_W, 18);
        g.fill(x, cy, x + MODE_W, cy + 18, hover ? Ui.NEUTRAL_FILL_HOVER : Ui.ACCENT_SOFT_BG);
        Ui.border(g, x, cy, x + MODE_W, cy + 18, Ui.ACCENT_SOFT_EDGE);
        Ui.centered(g, font, tr("body." + body).copy().append("  ▸"), x + MODE_W / 2, cy + 5, Ui.TEXT_ACCENT);
        if (hover) g.setComponentTooltipForNextFrame(font, List.of(tr("body.hint")), x, cy + 30);
        clicks.add(x, cy, MODE_W, 18, () -> body = next(BODIES, body));
    }

    /** How much mischief the god may cause, 1 to 5. */
    private void chaosChips(GuiGraphicsExtractor g, int x, int y) {
        Ui.section(g, font, tr("chaos"), x, y);
        int cw = (MODE_W - 4 * 3) / 5;
        for (int level = 1; level <= 5; level++) {
            int cx = x + (level - 1) * (cw + 3);
            int cy = y + 10;
            int width = level == 5 ? x + MODE_W - cx : cw;
            boolean selected = chaos == level;
            boolean hover = hovered(cx, cy, width, 18);
            int danger = level >= 4 ? 0x3DE05252 : Ui.ACCENT_SOFT_BG;
            g.fill(cx, cy, cx + width, cy + 18, selected ? danger : hover ? Ui.NEUTRAL_FILL_HOVER : 0x66232B33);
            Ui.border(g, cx, cy, cx + width, cy + 18, selected ? (level >= 4 ? 0xB0E05252 : Ui.ACCENT_SOFT_EDGE) : Ui.CARD_BORDER);
            Ui.centered(g, font, Component.literal(String.valueOf(level)), cx + width / 2, cy + 5,
                    selected ? (level >= 4 ? Ui.TEXT_DANGER : Ui.TEXT_ACCENT) : Ui.TEXT_SECONDARY);
            if (hover) g.setComponentTooltipForNextFrame(font, List.of(tr("chaos.hint", level, HostedTexts.chaosText(level))), cx, cy + 30);
            int chosen = level;
            clicks.add(cx, cy, width, 18, () -> chaos = chosen);
        }
    }

    /** The god's campaign frame and whether it sees the other players of the world. */
    private void campaignRow(GuiGraphicsExtractor g, int x, int xr, int y) {
        Ui.section(g, font, tr("campaign"), x, y);
        int cy = y + 10;
        int guestsW = 64;
        int cw = xr - x - guestsW - 4;
        boolean hover = hovered(x, cy, cw, 18);
        g.fill(x, cy, x + cw, cy + 18, hover ? Ui.NEUTRAL_FILL_HOVER : Ui.ACCENT_SOFT_BG);
        Ui.border(g, x, cy, x + cw, cy + 18, Ui.ACCENT_SOFT_EDGE);
        Ui.centered(g, font, Component.literal(Ui.fit(font, tr("campaign." + campaign).getString(), cw - 14) + "  ▸"), x + cw / 2, cy + 5, Ui.TEXT_ACCENT);
        if (hover) {
            HostedTexts.Campaign c = HostedTexts.CAMPAIGNS.get(campaign);
            g.setTooltipForNextFrame(font, font.split(Component.literal(c == null ? "" : c.text()), 260), x, cy + 30);
        }
        clicks.add(x, cy, cw, 18, () -> campaign = next(CAMPAIGNS, campaign));

        int gx = x + cw + 4;
        boolean ghover = hovered(gx, cy, guestsW, 18);
        g.fill(gx, cy, gx + guestsW, cy + 18, guests ? Ui.ACCENT_SOFT_BG : ghover ? Ui.NEUTRAL_FILL_HOVER : 0x66232B33);
        Ui.border(g, gx, cy, gx + guestsW, cy + 18, guests ? Ui.ACCENT_SOFT_EDGE : Ui.CARD_BORDER);
        Ui.centered(g, font, tr(guests ? "guests.on" : "guests.off"), gx + guestsW / 2, cy + 5, guests ? Ui.TEXT_ACCENT : Ui.TEXT_SECONDARY);
        if (ghover) g.setComponentTooltipForNextFrame(font, List.of(tr("guests.hint")), gx, cy + 30);
        clicks.add(gx, cy, guestsW, 18, () -> guests = !guests);
    }

    private static String next(String[] values, String current) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return values[(i + 1) % values.length];
        return values[0];
    }

    /** This world only, or along into all of the owner's worlds. */
    private void worldChips(GuiGraphicsExtractor g, int x, int y) {
        Ui.section(g, font, tr("worlds"), x, y);
        int cw = (MODE_W - 4) / 2;
        String[] scopes = {"world", "global"};
        String[] labels = {"worlds.this", "worlds.all"};
        for (int i = 0; i < 2; i++) {
            String id = scopes[i];
            int cx = x + i * (cw + 4);
            int cy = y + 10;
            boolean selected = scope.equals(id);
            boolean hover = hovered(cx, cy, cw, 18);
            g.fill(cx, cy, cx + cw, cy + 18, selected ? Ui.ACCENT_SOFT_BG : hover ? Ui.NEUTRAL_FILL_HOVER : 0x66232B33);
            Ui.border(g, cx, cy, cx + cw, cy + 18, selected ? Ui.ACCENT_SOFT_EDGE : Ui.CARD_BORDER);
            Ui.centered(g, font, tr(labels[i]), cx + cw / 2, cy + 5, selected ? Ui.TEXT_ACCENT : Ui.TEXT_SECONDARY);
            if (hover) g.setComponentTooltipForNextFrame(font, List.of(tr(labels[i] + ".hint")), cx, cy + 30);
            clicks.add(cx, cy, cw, 18, () -> scope = id);
        }
    }

    /** Commands travel as chat packets, which refuse control characters and the section sign. */
    static String clean(String text, int max) {
        StringBuilder out = new StringBuilder();
        for (char ch : text.toCharArray()) out.append(ch < 0x20 || ch == 0x7F || ch == '§' ? ' ' : ch);
        String s = out.toString().replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private void save() {
        String text = clean(persona, MAX_PERSONA);
        String skin = kind.equals("companion") ? account.trim() : "";
        if (!skin.isEmpty() && !SKIN.matcher(skin).matches()) {
            error = tr("error.skin");
            return;
        }
        String target;
        if (mate == null) {
            target = name.trim();
            if (!NAME.matcher(target).matches()) {
                error = tr("error.name");
                return;
            }
            MenuState.View view = MenuState.view();
            if (view != null) {
                for (MenuState.Mate m : view.mates()) {
                    if (m.name().toLowerCase(Locale.ROOT).equals(target.toLowerCase(Locale.ROOT))) {
                        error = tr("error.exists", m.name());
                        return;
                    }
                }
            }
            String verb = kind.equals("companion") ? "summon" : kind;
            command("vml " + verb + " " + target + (text.isEmpty() ? "" : " " + text));
            switch (kind) {
                case "builder" -> {
                    if (!body.equals("allay")) command("vml body " + target + " " + body);
                }
                case "god" -> {
                    if (!campaign.equals("sandbox")) command("vml campaign " + target + " " + campaign);
                    if (chaos != 3) command("vml chaos " + target + " " + chaos);
                    if (!guests) command("vml guests " + target + " off");
                }
                default -> {
                    boolean worldDefault = view != null && view.worldHardcore();
                    if (mode.equals("hardcore") != worldDefault) command("vml mode " + target + " " + mode);
                }
            }
            if (scope.equals("global")) command("vml worlds " + target + " all");
        } else {
            target = mate.name();
            if (!text.isEmpty() && !text.equals(clean(mate.persona(), MAX_PERSONA))) command("vml character " + target + " " + text);
            switch (kind) {
                case "builder" -> {
                    if (!body.equals(mate.bodyType())) command("vml body " + target + " " + body);
                }
                case "god" -> {
                    if (!campaign.equals(mate.campaign())) command("vml campaign " + target + " " + campaign);
                    if (chaos != mate.chaos()) command("vml chaos " + target + " " + chaos);
                    if (guests != mate.guests()) command("vml guests " + target + " " + (guests ? "on" : "off"));
                }
                default -> {
                    if (!mate.fallen() && !mode.equals(mate.mode())) command("vml mode " + target + " " + mode);
                }
            }
            if (!scope.equals(mate.scope())) command("vml worlds " + target + (scope.equals("global") ? " all" : " this"));
        }
        if (!skin.isEmpty() && (mate == null || !skin.equalsIgnoreCase(mate.skinSource()))) command("vml skin " + target + " " + skin);
        if (kind.equals("companion") && !brain.equals(mate == null ? "default" : mate.brain())) command("vml brain " + target + " " + brain);
        onClose();
    }
}
