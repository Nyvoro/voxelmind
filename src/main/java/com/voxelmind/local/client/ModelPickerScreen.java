package com.voxelmind.local.client;

import com.voxelmind.local.provider.ModelCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Searchable list of the endpoint's models with an hourly cost estimate; picking one only fills the settings screen. */
public final class ModelPickerScreen extends Screen {
    private enum Sort { PRICE, NAME }

    private static final int MUTED = 0xFFA0A0A0;

    private final Screen parent;
    private final String baseUrl;
    private final String key;
    private final Consumer<String> onPick;
    private String selectedId;
    private String query = "";
    private boolean toolsOnly = true;
    private Sort sort = Sort.PRICE;
    private List<ModelCatalog.Model> all = List.of();
    private Component message = SettingsScreen.tr("models.loading");
    private ModelList list;
    private Button use;

    public ModelPickerScreen(Screen parent, String baseUrl, String key, String current, Consumer<String> onPick) {
        super(SettingsScreen.tr("models.title"));
        this.parent = parent;
        this.baseUrl = baseUrl;
        this.key = key;
        this.selectedId = current;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        int x = width / 2 - 160;
        EditBox search = new EditBox(font, x, 26, 320, 20, SettingsScreen.tr("models.search"));
        search.setHint(SettingsScreen.tr("models.search.hint"));
        search.setValue(query);
        search.setResponder(v -> {
            query = v;
            refilter();
        });
        addRenderableWidget(search);
        addRenderableWidget(CycleButton.onOffBuilder(toolsOnly)
                .create(x, 50, 158, 20, SettingsScreen.tr("models.tools_only"), (b, v) -> {
                    toolsOnly = v;
                    refilter();
                }));
        addRenderableWidget(CycleButton.<Sort>builder(s -> SettingsScreen.tr("models.sort." + s.name().toLowerCase(Locale.ROOT)), sort)
                .withValues(Sort.values())
                .create(x + 162, 50, 158, 20, SettingsScreen.tr("models.sort"), (b, v) -> {
                    sort = v;
                    refilter();
                }));
        list = addRenderableWidget(new ModelList(minecraft, width, height - 74 - 52, 74, 24));
        int bottom = height - 28;
        use = addRenderableWidget(Button.builder(SettingsScreen.tr("models.use"), b -> pick())
                .bounds(width / 2 - 154, bottom, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(width / 2 + 4, bottom, 150, 20).build());
        setInitialFocus(search);
        if (all.isEmpty()) load(false);
        else refilter();
    }

    private void load(boolean refresh) {
        ModelCatalog.fetch(baseUrl, key, refresh).whenComplete((models, error) -> minecraft.execute(() -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                message = SettingsScreen.tr("models.error", String.valueOf(cause.getMessage()));
                return;
            }
            all = models;
            refilter();
        }));
    }

    private void refilter() {
        if (list == null) return;
        List<ModelCatalog.Model> hits = new ArrayList<>();
        for (ModelCatalog.Model m : all) {
            if ((!toolsOnly || m.tools()) && ModelCatalog.matches(m, query)) hits.add(m);
        }
        Comparator<ModelCatalog.Model> order = sort == Sort.NAME
                ? Comparator.comparing(m -> m.name().toLowerCase(Locale.ROOT))
                // Priced models by cost; free ones (daily request limits) and unpriced ones after them.
                : Comparator.<ModelCatalog.Model>comparingInt(m -> m.free() ? 1 : m.priced() ? 0 : 2)
                        .thenComparingDouble(m -> m.priced() ? m.dollarsPerHour() : 0)
                        .thenComparing(ModelCatalog.Model::id);
        hits.sort(Comparator.comparing(ModelCatalog.Model::tested).reversed()
                .thenComparingDouble(m -> ModelCatalog.TESTED.getOrDefault(m.id(), 0.0))
                .thenComparing(order));
        List<ModelEntry> entries = new ArrayList<>();
        for (ModelCatalog.Model m : hits) entries.add(new ModelEntry(m));
        list.replace(entries);
        for (ModelEntry e : entries) {
            if (e.model.id().equals(selectedId)) {
                list.setSelected(e);
                list.scrollTo(e);
            }
        }
        message = all.isEmpty() ? SettingsScreen.tr("models.empty")
                : SettingsScreen.tr("models.count", hits.size(), all.size());
        if (use != null) use.active = list.getSelected() != null;
    }

    private void pick() {
        ModelEntry chosen = list.getSelected();
        if (chosen == null) return;
        onPick.accept(chosen.model.id());
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        g.centeredText(font, message, width / 2, height - 44, MUTED);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private final class ModelList extends ObjectSelectionList<ModelEntry> {
        ModelList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return 320;
        }

        void replace(List<ModelEntry> entries) {
            replaceEntries(entries);
        }

        void scrollTo(ModelEntry entry) {
            centerScrollOn(entry);
        }

        @Override
        public void setSelected(ModelEntry entry) {
            super.setSelected(entry);
            if (entry != null) selectedId = entry.model.id();
            if (use != null) use.active = entry != null;
        }
    }

    private final class ModelEntry extends ObjectSelectionList.Entry<ModelEntry> {
        final ModelCatalog.Model model;

        ModelEntry(ModelCatalog.Model model) {
            this.model = model;
        }

        //? if >=1.21.9 {
        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
            paintRow(g, getContentX(), getContentY(), getContentWidth());
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            list.setSelected(this);
            if (doubleClick) pick();
            return true;
        }
        //?} else {
        /*private static final long DOUBLE_CLICK_MILLIS = 250;
        private long lastClickMillis;

        // Before 1.21.9 rows are drawn with the row box (index, top, left, width, height) and clicks carry no double-click flag.
        @Override
        public void render(GuiGraphicsExtractor g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float delta) {
            paintRow(g, left, top, width - 4);
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            list.setSelected(this);
            long now = System.currentTimeMillis();
            boolean doubleClick = now - lastClickMillis < DOUBLE_CLICK_MILLIS;
            lastClickMillis = now;
            if (doubleClick) pick();
            return true;
        }
        *///?}

        private void paintRow(GuiGraphicsExtractor g, int contentX, int contentY, int contentWidth) {
            String name = model.name() + (model.tested() ? "  ✔" : "");
            g.text(font, font.plainSubstrByWidth(name, contentWidth), contentX, contentY + 1, 0xFFFFFFFF);
            String context = model.contextLength() > 0 ? " · " + (model.contextLength() / 1000) + "k" : "";
            String tools = model.tools() ? "" : " · no tool calls";
            String detail = ModelCatalog.priceLine(model) + context + tools + " · " + model.id();
            g.text(font, font.plainSubstrByWidth(detail, contentWidth), contentX, contentY + 12, MUTED);
        }

        @Override
        public Component getNarration() {
            return Component.literal(model.name() + ", " + ModelCatalog.priceLine(model));
        }
    }
}
