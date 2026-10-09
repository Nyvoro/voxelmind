package com.voxelmind.local.client;

import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.client.ui.Ui;
import com.voxelmind.local.command.VmlCommand;
import com.voxelmind.local.config.LocalConfig;
import com.voxelmind.local.mind.LlmClient;
import com.voxelmind.local.provider.ModelCatalog;
import com.voxelmind.local.provider.OpenRouterAccount;
import com.voxelmind.local.provider.Player2;
import com.voxelmind.local.provider.VoxelMindApi;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Where the companions of the hosted world think: OpenRouter with the player's own account or key,
 * or a local OpenAI-compatible server. Nothing is stored before "Done", except a key from a sign-in.
 */
public final class SettingsScreen extends Screen {
    enum Endpoint {
        VOXELMIND(VoxelMindApi.BASE),
        PLAYER2(Player2.WEB),
        OPENROUTER(LocalConfig.OPENROUTER_URL),
        OLLAMA("http://localhost:11434/v1"),
        LMSTUDIO("http://localhost:1234/v1"),
        CUSTOM("");

        final String url;

        Endpoint(String url) {
            this.url = url;
        }

        static Endpoint of(String url) {
            for (Endpoint e : values()) if (e != CUSTOM && e.url.equals(url)) return e;
            return CUSTOM;
        }
    }

    private static final int WIDTH = 260;
    private static final int MUTED = 0xFFA0A0A0;
    private static final int GOOD = 0xFF55FF55;
    private static final int BAD = 0xFFFF6060;
    private static final int KEY_VISIBLE_CHARS = 9;

    private final @Nullable Screen parent;
    private Endpoint endpoint;
    private String customUrl;
    private String openRouterKey;
    private String endpointKey;
    private String player2Key;
    private String voxelmindKey;
    private boolean voxelmindStarting;
    private String model;
    private Component account = Component.empty();
    private Component status = Component.empty();
    private int statusColor = MUTED;
    private OpenRouterAccount.@Nullable Login login;
    private Player2.@Nullable DeviceLogin player2Login;
    private boolean player2Starting;
    private int accountY;
    private int modelInfoY;
    private int statusY;

    public SettingsScreen(@Nullable Screen parent) {
        super(tr("title"));
        this.parent = parent;
        LocalConfig config = LocalConfig.get();
        endpoint = Endpoint.of(config.baseUrl);
        customUrl = endpoint == Endpoint.CUSTOM ? config.baseUrl : "";
        openRouterKey = config.apiKey == null ? "" : config.apiKey;
        endpointKey = config.endpointKey == null ? "" : config.endpointKey;
        player2Key = config.player2Key == null ? "" : config.player2Key;
        voxelmindKey = config.voxelmindKey == null ? "" : config.voxelmindKey;
        model = config.model;
        refreshAccount();
    }

    static Component tr(String key, Object... args) {
        return Component.translatable("voxelmind_local.settings." + key, args);
    }

    private String baseUrl() {
        String url = endpoint == Endpoint.CUSTOM ? customUrl.trim() : endpoint.url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private String key() {
        if (endpoint == Endpoint.PLAYER2) return player2Key.trim();
        if (endpoint == Endpoint.VOXELMIND) return voxelmindKey.trim();
        if (endpoint != Endpoint.OPENROUTER) return endpointKey.trim();
        if (!openRouterKey.isBlank()) return openRouterKey.trim();
        String env = System.getenv("VOXELMIND_LOCAL_API_KEY");
        return env == null ? "" : env.trim();
    }

    @Override
    protected void init() {
        int x = width / 2 - WIDTH / 2;
        int y = Math.max(30, height / 2 - 110);
        addRenderableWidget(CycleButton.<Endpoint>builder(e -> tr("endpoint." + e.name().toLowerCase(Locale.ROOT)), endpoint)
                .withValues(Endpoint.values())
                .create(x, y, WIDTH, 20, tr("endpoint"), (button, value) -> {
                    endpoint = value;
                    status = Component.empty();
                    refreshAccount();
                    rebuildWidgets();
                }));
        y += 24;

        if (endpoint == Endpoint.OPENROUTER) {
            if (login == null) {
                Button signIn = Button.builder(tr("sign_in"), b -> startLogin()).bounds(x, y, WIDTH, 20).build();
                signIn.setTooltip(Tooltip.create(tr("sign_in.tooltip")));
                addRenderableWidget(signIn);
            } else {
                OpenRouterAccount.Login running = login;
                addRenderableWidget(Button.builder(tr("sign_in.cancel"), b -> running.cancel().run())
                        .bounds(x, y, WIDTH / 2 - 2, 20).build());
                addRenderableWidget(Button.builder(tr("sign_in.copy"), b -> {
                    minecraft.keyboardHandler.setClipboard(running.url().toString());
                    setStatus(tr("sign_in.copied"), MUTED);
                }).bounds(x + WIDTH / 2 + 2, y, WIDTH / 2 - 2, 20).build());
            }
            y += 24;
        }
        if (endpoint == Endpoint.PLAYER2) {
            // Until the mod ships with its Client ID, the developer can enter one here (Player2 dashboard, "AI Mods").
            if (Player2.CLIENT_ID.isEmpty()) {
                EditBox clientId = new EditBox(font, x, y, WIDTH, 18, tr("player2.client_id"));
                clientId.setMaxLength(200);
                clientId.setHint(tr("player2.client_id.hint"));
                String configured = LocalConfig.get().player2ClientId;
                clientId.setValue(configured == null ? "" : configured);
                clientId.setResponder(v -> {
                    LocalConfig.get().player2ClientId = v.trim();
                    LocalConfig.save();
                });
                addRenderableWidget(clientId);
                y += 22;
            }
            Player2.DeviceLogin running = player2Login;
            if (running == null) {
                Button connect = Button.builder(tr(player2Starting ? "player2.starting" : player2Key.isBlank() ? "player2.connect" : "player2.reconnect"),
                        b -> startPlayer2()).bounds(x, y, WIDTH, 20).build();
                connect.active = !player2Starting;
                connect.setTooltip(Tooltip.create(tr("player2.tooltip")));
                addRenderableWidget(connect);
            } else {
                addRenderableWidget(Button.builder(tr("sign_in.cancel"), b -> running.cancel().run())
                        .bounds(x, y, WIDTH / 2 - 2, 20).build());
                addRenderableWidget(Button.builder(tr("player2.open"), b -> Util.getPlatform().openUri(running.url()))
                        .bounds(x + WIDTH / 2 + 2, y, WIDTH / 2 - 2, 20).build());
            }
            y += 22;
            accountY = y;
            y += 24;
            Button pick = Button.builder(tr("model.player2"), b -> {}).bounds(x, y, WIDTH, 20).build();
            pick.active = false;
            addRenderableWidget(pick);
            y += 22;
            modelInfoY = y;
            y += 14;
            testAndFooter(x, y);
            return;
        }
        if (endpoint == Endpoint.VOXELMIND) {
            Button signIn = Button.builder(tr(voxelmindStarting ? "voxelmind.starting" : voxelmindKey.isBlank() ? "voxelmind.sign_in" : "voxelmind.reconnect"),
                    b -> startVoxelMind()).bounds(x, y, WIDTH, 20).build();
            signIn.active = !voxelmindStarting;
            signIn.setTooltip(Tooltip.create(tr("voxelmind.tooltip")));
            addRenderableWidget(signIn);
            y += 22;
            accountY = y;
            y += 24;
            String name = minecraft.getUser().getName();
            // Signed in: the private account page (balance, spending, buying); otherwise the shop.
            boolean signedIn = !voxelmindKey.isBlank();
            addRenderableWidget(Button.builder(tr(signedIn ? "voxelmind.account" : "voxelmind.buy"), b -> Util.getPlatform().openUri(
                            signedIn ? VoxelMindApi.accountUrl(voxelmindKey.trim()) : VoxelMindApi.buyUrl(name)))
                    .bounds(x, y, WIDTH / 2 - 2, 20).build());
            Button copy = Button.builder(tr("voxelmind.copy"), b -> {
                minecraft.keyboardHandler.setClipboard(voxelmindKey.trim());
                setStatus(tr("voxelmind.copied"), MUTED);
            }).bounds(x + WIDTH / 2 + 2, y, WIDTH / 2 - 2, 20).build();
            copy.active = !voxelmindKey.isBlank();
            copy.setTooltip(Tooltip.create(tr("voxelmind.copy.tooltip")));
            addRenderableWidget(copy);
            y += 22;
            modelInfoY = y;
            y += 14;
            testAndFooter(x, y);
            return;
        }
        if (endpoint == Endpoint.CUSTOM) {
            EditBox url = new EditBox(font, x, y, WIDTH, 20, tr("url"));
            url.setMaxLength(300);
            url.setValue(customUrl);
            url.setHint(tr("url.hint"));
            url.setResponder(v -> customUrl = v);
            addRenderableWidget(url);
            y += 24;
        }
        EditBox keyBox = new EditBox(font, x, y, WIDTH, 20, tr("key"));
        keyBox.setMaxLength(400);
        keyBox.setValue(endpoint == Endpoint.OPENROUTER ? openRouterKey : endpointKey);
        keyBox.setHint(tr(endpoint == Endpoint.OPENROUTER ? "key.hint" : "key.hint.optional"));
        keyBox.addFormatter((text, firstIndex) -> FormattedCharSequence.forward(mask(text, firstIndex), Style.EMPTY));
        keyBox.setResponder(v -> {
            if (endpoint != Endpoint.OPENROUTER) {
                endpointKey = v;
                return;
            }
            openRouterKey = v;
            // A pasted key is checked right away; partial typing is not sent anywhere.
            if (v.isBlank() || v.trim().startsWith("sk-or-") && v.trim().length() >= 60) refreshAccount();
        });
        addRenderableWidget(keyBox);
        y += 22;
        accountY = y;
        y += 24;

        addRenderableWidget(Button.builder(tr("model", model), b -> minecraft.gui.setScreen(
                        new ModelPickerScreen(this, baseUrl(), endpoint == Endpoint.OPENROUTER ? "" : key(), model, id -> model = id)))
                .bounds(x, y, WIDTH, 20).build());
        y += 22;
        modelInfoY = y;
        y += 14;
        if (endpoint != Endpoint.PLAYER2) ModelCatalog.fetch(baseUrl(), endpoint == Endpoint.OPENROUTER ? "" : key(), false);

        testAndFooter(x, y);
    }

    private void testAndFooter(int x, int y) {
        Button test = Button.builder(tr("test"), b -> test()).bounds(x, y, WIDTH, 20).build();
        test.setTooltip(Tooltip.create(tr("test.tooltip")));
        addRenderableWidget(test);
        y += 24;
        statusY = y;

        int bottom = height - 28;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> save()).bounds(width / 2 - 154, bottom, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 + 4, bottom, 150, 20).build());
    }

    /** Shows the start of a key and hides the rest; {@code firstIndex} is where the visible part begins. */
    private static String mask(String text, int firstIndex) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) out.append(firstIndex + i < KEY_VISIBLE_CHARS ? text.charAt(i) : '*');
        return out.toString();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int x = width / 2 - WIDTH / 2;
        g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        Ui.wordWrap(g, font, account, x, accountY, WIDTH, MUTED);
        g.text(font, modelInfo(), x, modelInfoY, MUTED);
        Ui.wordWrap(g, font, status, x, statusY, WIDTH, statusColor);
    }

    private Component modelInfo() {
        if (endpoint == Endpoint.PLAYER2) return tr("model.player2.info");
        if (endpoint == Endpoint.VOXELMIND) return tr("model.voxelmind.info");
        ModelCatalog.Model listed = null;
        for (ModelCatalog.Model m : ModelCatalog.cached(baseUrl())) if (m.id().equals(model)) listed = m;
        if (listed == null) return ModelCatalog.cached(baseUrl()).isEmpty() ? Component.empty() : tr("model.unlisted");
        Component line = Component.literal(ModelCatalog.priceLine(listed));
        if (!listed.tools()) return tr("model.no_tools").copy().withStyle(ChatFormatting.RED);
        return listed.tested() ? line.copy().append(" · ").append(tr("model.tested")) : line;
    }

    private void setStatus(Component text, int color) {
        status = text;
        statusColor = color;
    }

    private void refreshAccount() {
        if (endpoint == Endpoint.VOXELMIND) {
            String key = key();
            if (key.isEmpty()) {
                account = tr("account.voxelmind.none");
                return;
            }
            account = tr("account.checking");
            VoxelMindApi.balance(key).whenComplete((b, error) -> minecraft.execute(() -> {
                if (endpoint != Endpoint.VOXELMIND || !key.equals(key())) return;
                account = error != null
                        ? failed(error, "account.voxelmind.rejected")
                        : tr("account.voxelmind.ok", b.name(), VmlCommand.sparks(b));
            }));
            return;
        }
        if (endpoint == Endpoint.PLAYER2) {
            String key = key();
            if (key.isEmpty()) {
                account = tr("account.player2.none");
                return;
            }
            account = tr("account.checking");
            Player2.balance(key).whenComplete((b, error) -> minecraft.execute(() -> {
                if (endpoint != Endpoint.PLAYER2 || !key.equals(key())) return;
                account = error != null
                        ? failed(error, "account.player2.rejected")
                        : tr("account.player2.ok", VmlCommand.balance(b));
            }));
            return;
        }
        if (endpoint != Endpoint.OPENROUTER) {
            account = tr(endpoint == Endpoint.CUSTOM ? "account.custom" : "account.local");
            return;
        }
        String key = key();
        if (key.isEmpty()) {
            account = tr("account.none");
            return;
        }
        account = tr("account.checking");
        OpenRouterAccount.status(key).whenComplete((st, error) -> minecraft.execute(() -> {
            if (endpoint != Endpoint.OPENROUTER || !key.equals(key())) return;
            account = error != null
                    ? failed(error, "account.rejected")
                    : Component.literal(VmlCommand.credit(st));
        }));
    }

    /** "Rejected" only when the service turned the key down; a service that did not answer is not a reason to sign in again. */
    private static Component failed(Throwable error, String rejectedKey) {
        return (com.voxelmind.local.provider.Rejected.is(error)
                ? tr(rejectedKey)
                : tr("account.unreachable", com.voxelmind.local.provider.Rejected.message(error))).copy().withStyle(ChatFormatting.RED);
    }

    private void startLogin() {
        try {
            OpenRouterAccount.Login started = OpenRouterAccount.startLocalLogin();
            login = started;
            Util.getPlatform().openUri(started.url());
            setStatus(tr("sign_in.waiting"), MUTED);
            started.key().whenComplete((key, error) -> minecraft.execute(() -> {
                if (login != started) return;
                login = null;
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    String reason = cause instanceof java.util.concurrent.TimeoutException ? "timed out" : String.valueOf(cause.getMessage());
                    setStatus(tr("sign_in.failed", reason), BAD);
                } else {
                    OpenRouterAccount.adopt(key);
                    openRouterKey = key;
                    setStatus(tr("sign_in.done"), GOOD);
                    refreshAccount();
                }
                rebuildWidgets();
            }));
        } catch (Exception e) {
            VoxelMindLocal.LOG.warn("Could not start the OpenRouter sign-in", e);
            setStatus(tr("sign_in.failed", e.getMessage()), BAD);
        }
        rebuildWidgets();
    }

    /** The Player2 app gives a key with one click; without the app the player confirms a code in the browser. */
    private void startPlayer2() {
        player2Starting = true;
        setStatus(tr("player2.starting"), MUTED);
        rebuildWidgets();
        Player2.appRunning().thenCompose(app -> {
            if (app) return Player2.loginWithApp().thenApply(key -> (Object) key);
            return Player2.startDeviceLogin().thenApply(login -> (Object) login);
        }).whenComplete((result, error) -> minecraft.execute(() -> {
            player2Starting = false;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                setStatus(tr("player2.failed", String.valueOf(cause.getMessage())), BAD);
            } else if (result instanceof String key) {
                connectedPlayer2(key);
            } else if (result instanceof Player2.DeviceLogin started) {
                player2Login = started;
                Util.getPlatform().openUri(started.url());
                setStatus(tr("player2.code", started.userCode()), MUTED);
                started.key().whenComplete((key, failed) -> minecraft.execute(() -> {
                    if (player2Login != started) return;
                    player2Login = null;
                    if (failed != null) {
                        Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
                        setStatus(tr("player2.failed", String.valueOf(cause.getMessage())), BAD);
                    } else {
                        connectedPlayer2(key);
                    }
                    rebuildWidgets();
                }));
            }
            rebuildWidgets();
        }));
    }

    /** Signs in with the Minecraft account this game runs with; new accounts get welcome Sparks. */
    private void startVoxelMind() {
        voxelmindStarting = true;
        setStatus(tr("voxelmind.starting"), MUTED);
        rebuildWidgets();
        VoxelMindSignIn.start().whenComplete((login, error) -> minecraft.execute(() -> {
            voxelmindStarting = false;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                setStatus(tr("voxelmind.failed", String.valueOf(cause.getMessage())), BAD);
            } else {
                voxelmindKey = login.token();
                setStatus(login.welcome() > 0
                        ? tr("voxelmind.done.welcome", login.name(), String.format(Locale.ROOT, "%.0f", login.welcome()))
                        : tr("voxelmind.done", login.name()), GOOD);
                refreshAccount();
            }
            rebuildWidgets();
        }));
    }

    private void connectedPlayer2(String key) {
        Player2.adopt(key);
        player2Key = key;
        setStatus(tr("player2.done"), GOOD);
        refreshAccount();
    }

    private void test() {
        String base = baseUrl();
        String key = key();
        if ((endpoint == Endpoint.OPENROUTER || endpoint == Endpoint.PLAYER2 || endpoint == Endpoint.VOXELMIND) && key.isEmpty()) {
            setStatus(tr(endpoint == Endpoint.PLAYER2 ? "test.no_player2" : endpoint == Endpoint.VOXELMIND ? "test.no_voxelmind" : "test.no_key"), BAD);
            return;
        }
        setStatus(tr("test.running", endpoint == Endpoint.PLAYER2 ? "Player2" : endpoint == Endpoint.VOXELMIND ? "VoxelMind" : model), MUTED);
        String tested = model;
        LlmClient.probe(base, key, tested).whenComplete((response, error) -> minecraft.execute(() -> {
            if (!tested.equals(model)) return;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                setStatus(Component.literal(LlmClient.explain(cause)), BAD);
                return;
            }
            boolean waved = response.toolCalls().stream().anyMatch(call -> call.name().equals("wave"));
            String seconds = String.format(Locale.ROOT, "%.1f", response.latencyMs() / 1000.0);
            if (waved) setStatus(tr("test.ok", seconds), GOOD);
            else setStatus(tr("test.no_tool", seconds), BAD);
        }));
        if (endpoint == Endpoint.OPENROUTER || endpoint == Endpoint.PLAYER2 || endpoint == Endpoint.VOXELMIND) refreshAccount();
    }

    private void save() {
        String base = baseUrl();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            setStatus(tr("url.invalid"), BAD);
            return;
        }
        LocalConfig config = LocalConfig.get();
        config.apiKey = openRouterKey.trim();
        config.endpointKey = endpointKey.trim();
        config.player2Key = player2Key.trim();
        config.voxelmindKey = voxelmindKey.trim();
        if (!model.equals(config.model)) config.selectModel(model);
        config.selectEndpoint(base);
        LocalConfig.save();
        onClose();
    }

    @Override
    public void onClose() {
        if (login != null) login.cancel().run();
        login = null;
        if (player2Login != null) player2Login.cancel().run();
        player2Login = null;
        minecraft.gui.setScreen(parent);
    }
}
