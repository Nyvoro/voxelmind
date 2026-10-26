package com.voxelmind.local.test;

import com.voxelmind.local.client.CompanionEditScreen;
import com.voxelmind.local.client.CompanionsScreen;
import com.voxelmind.local.client.MemoriesScreen;
import com.voxelmind.local.client.MenuState;
import com.voxelmind.local.client.ModelPickerScreen;
import com.voxelmind.local.client.MonitorScreen;
import com.voxelmind.local.client.SettingsScreen;
import com.voxelmind.local.client.SparksEndedScreen;
import com.voxelmind.local.config.LocalConfig;
import com.voxelmind.local.provider.ModelCatalog;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * A real client in a singleplayer world: summon a companion, see it rendered,
 * talk to it, dismiss it. Screenshots land in build/run/clientGameTest/screenshots.
 */
public class SingleplayerClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        // Companions for all worlds live in the game directory and would survive into the next test run.
        deleteTree(GLOBAL_DIR.get());
        sparksEnded(context);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(60);
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml summon Lysa"));
            singleplayer.getServer().waitFor(server -> server.getPlayerList().getPlayerByName("Lysa") != null, 200);
            context.waitFor(mc -> companion(mc) != null, 200);
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml skin Lysa Notch"));
            context.waitTicks(100);
            context.waitFor(mc -> companion(mc) != null, 200);
            context.waitTicks(40);
            context.runOnClient(SingleplayerClientTest::lookAtCompanion);
            context.waitTicks(10);
            context.takeScreenshot("vml-01-companion-arrived");

            context.runOnClient(mc -> mc.player.connection.sendChat("Hallo Lysa! Wie gefällt dir die Welt?"));
            context.waitTicks(200);
            context.runOnClient(SingleplayerClientTest::lookAtCompanion);
            context.waitTicks(5);
            context.takeScreenshot("vml-02-after-chat");

            context.runOnClient(mc -> mc.player.connection.sendCommand("vml dismiss Lysa"));
            singleplayer.getServer().waitFor(server -> server.getPlayerList().getPlayerByName("Lysa") == null, 100);
            context.waitTicks(20);
            context.takeScreenshot("vml-03-dismissed");

            // AI settings: opened by the command the chat links run, then the model list (needs network for OpenRouter's list).
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml setup"));
            context.waitForScreen(SettingsScreen.class);
            context.waitTicks(40);
            context.takeScreenshot("vml-04-settings");
            context.setScreen(() -> new ModelPickerScreen(Minecraft.getInstance().gui.screen(), LocalConfig.OPENROUTER_URL, "",
                    LocalConfig.get().model, id -> {}));
            context.waitForScreen(ModelPickerScreen.class);
            context.waitFor(mc -> !ModelCatalog.cached(LocalConfig.OPENROUTER_URL).isEmpty(), 600);
            context.waitTicks(10);
            context.takeScreenshot("vml-05-models");
            context.getInput().typeChars("haiku");
            context.waitTicks(10);
            context.takeScreenshot("vml-06-models-search");
            context.clickScreenButton("gui.back");
            context.waitForScreen(SettingsScreen.class);
            context.clickScreenButton("gui.cancel");
            context.waitFor(mc -> mc.gui.screen() == null, 100);
            // Player2 as the AI source: one connect button, the model chosen by Player2.
            String endpointBefore = LocalConfig.get().baseUrl;
            context.runOnClient(mc -> LocalConfig.get().baseUrl = com.voxelmind.local.provider.Player2.WEB);
            context.setScreen(() -> new SettingsScreen(null));
            context.waitTicks(20);
            context.takeScreenshot("vml-04b-settings-player2");
            context.setScreen(() -> null);
            context.runOnClient(mc -> LocalConfig.get().baseUrl = endpointBefore);

            // Companion menu: summon again, open it with /vml like the V key does, then monitor, memories, create.
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml summon Lysa"));
            singleplayer.getServer().waitFor(server -> server.getPlayerList().getPlayerByName("Lysa") != null, 200);
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml"));
            context.waitForScreen(CompanionsScreen.class);
            context.waitFor(mc -> MenuState.view() != null && !MenuState.view().mates().isEmpty(), 200);
            context.waitTicks(20);
            context.takeScreenshot("vml-07-menu");
            UUID lysa = context.computeOnClient(mc -> MenuState.view().mates().getFirst().id());
            context.setScreen(() -> new MonitorScreen(Minecraft.getInstance().gui.screen(), lysa));
            context.waitTicks(50);
            context.takeScreenshot("vml-08-monitor");
            context.setScreen(() -> new MemoriesScreen(Minecraft.getInstance().gui.screen(), lysa));
            context.waitTicks(10);
            context.takeScreenshot("vml-09-memories");
            context.setScreen(() -> new CompanionEditScreen(new CompanionsScreen(null), null));
            context.getInput().typeChars("Ember");
            context.waitTicks(10);
            context.takeScreenshot("vml-10-create");
            // Real clicks on painted controls: the first character chip, then "Create & summon".
            click(context, 20, 160, true, CREATE_H);
            context.waitTicks(5);
            click(context, 80, -20, false, CREATE_H);
            singleplayer.getServer().waitFor(server -> server.getPlayerList().getPlayerByName("Ember") != null, 200);
            UUID me = context.computeOnClient(mc -> mc.player.getUUID());
            String persona = singleplayer.getServer().computeOnServer(server ->
                    com.voxelmind.local.companion.CompanionManager.get().find(me, "Ember").state.persona);
            if (!persona.startsWith("Warm")) throw new AssertionError("chip text did not become the character: " + persona);
            // All worlds: the file moves to the game directory.
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml worlds Ember all"));
            context.waitTicks(10);
            UUID emberId = singleplayer.getServer().computeOnServer(server ->
                    com.voxelmind.local.companion.CompanionManager.get().find(me, "Ember").state.id);
            if (!Files.exists(GLOBAL_DIR.get().resolve(emberId + ".json"))) throw new AssertionError("Ember was not stored for all worlds");

            // Builder through the menu: the kind tab, then create. It gets a fairy body at the owner's shoulder.
            context.setScreen(() -> new CompanionEditScreen(new CompanionsScreen(null), null));
            context.getInput().typeChars("Pixie");
            context.waitTicks(5);
            click(context, 160, 27, true, CREATE_H);
            context.waitTicks(10);
            context.takeScreenshot("vml-15-create-builder");
            click(context, 80, -20, false, HOSTED_H);
            singleplayer.getServer().waitFor(server -> {
                var c = com.voxelmind.local.companion.CompanionManager.get().find(me, "Pixie");
                return c != null && c.hosted instanceof com.voxelmind.local.hosted.BuilderMind b && b.active() && b.bodyEntity() != null;
            }, 200);
            context.setScreen(() -> null);
            context.waitTicks(40);
            context.takeScreenshot("vml-16-builder-fairy");
            // God through the menu: kind tab, chaos 5, guests off, create.
            context.setScreen(() -> new CompanionEditScreen(new CompanionsScreen(null), null));
            context.getInput().typeChars("Zeus");
            context.waitTicks(5);
            click(context, 320, 27, true, CREATE_H);
            context.waitTicks(5);
            click(context, 380, 59, true, HOSTED_H);
            context.waitTicks(5);
            context.takeScreenshot("vml-17-create-god");
            click(context, 80, -20, false, HOSTED_H);
            singleplayer.getServer().waitFor(server -> {
                var c = com.voxelmind.local.companion.CompanionManager.get().find(me, "Zeus");
                return c != null && c.hosted != null && c.hosted.active() && c.state.chaos == 5;
            }, 200);
            context.setScreen(() -> new CompanionsScreen(null));
            context.waitFor(mc -> MenuState.view() != null && MenuState.view().mates().stream().anyMatch(m -> m.kind().equals("god") && m.present()), 200);
            context.waitTicks(10);
            context.takeScreenshot("vml-18-menu-kinds");
            context.getInput().scroll(-1);
            context.getInput().scroll(-1);
            context.waitTicks(5);
            context.takeScreenshot("vml-18b-menu-kinds-scrolled");
            UUID zeus = context.computeOnClient(mc -> MenuState.view().mates().stream().filter(m -> m.kind().equals("god")).findFirst().get().id());
            context.setScreen(() -> new MonitorScreen(Minecraft.getInstance().gui.screen(), zeus));
            context.waitTicks(10);
            context.takeScreenshot("vml-19-monitor-god");
            context.setScreen(() -> new CompanionEditScreen(new CompanionsScreen(null), MenuState.view().find(zeus)));
            context.waitTicks(10);
            context.takeScreenshot("vml-20-edit-god");
            context.setScreen(() -> null);
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml dismiss Zeus"));
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml dismiss Pixie"));
            singleplayer.getServer().waitFor(server -> com.voxelmind.local.companion.CompanionManager.get().find(me, "Pixie").hosted.active() == false
                    && !com.voxelmind.local.companion.CompanionManager.get().find(me, "Zeus").hosted.active(), 100);

            // Normal mode: a death ends in a respawn.
            singleplayer.getServer().runOnServer(server -> {
                var ember = server.getPlayerList().getPlayerByName("Ember");
                ember.kill(ember.level());
            });
            singleplayer.getServer().waitFor(server -> {
                var ember = server.getPlayerList().getPlayerByName("Ember");
                return ember != null && !ember.isDeadOrDying();
            }, 200);
            // Hardcore: one life; the body leaves for good and summoning does not bring it back.
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml mode Lysa hardcore"));
            context.waitTicks(10);
            singleplayer.getServer().runOnServer(server -> {
                var body = server.getPlayerList().getPlayerByName("Lysa");
                body.kill(body.level());
            });
            singleplayer.getServer().waitFor(server -> server.getPlayerList().getPlayerByName("Lysa") == null, 200);
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml summon Lysa"));
            context.waitTicks(60);
            boolean fallen = singleplayer.getServer().computeOnServer(server ->
                    server.getPlayerList().getPlayerByName("Lysa") == null
                            && com.voxelmind.local.companion.CompanionManager.get().find(me, "Lysa").state.fallen());
            if (!fallen) throw new AssertionError("a hardcore companion came back after its death");
            context.setScreen(() -> new CompanionsScreen(null));
            context.waitFor(mc -> MenuState.view() != null && MenuState.view().mates().stream().anyMatch(MenuState.Mate::fallen), 200);
            context.waitTicks(10);
            context.takeScreenshot("vml-13-menu-hardcore");
            context.setScreen(() -> new CompanionEditScreen(new CompanionsScreen(null), MenuState.view().mates().getFirst()));
            context.waitTicks(10);
            context.takeScreenshot("vml-11-edit");
            context.setScreen(() -> null);
            context.waitTicks(5);
            context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
            context.waitTicks(10);
            context.takeScreenshot("vml-12-pause");
            context.setScreen(() -> null);
        }

        // A second world: only the companion for all worlds is there, with its memories and an empty inventory.
        try (TestSingleplayerContext second = context.worldBuilder().create()) {
            context.waitTicks(60);
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml"));
            context.waitForScreen(CompanionsScreen.class);
            context.waitFor(mc -> MenuState.view() != null, 200);
            List<String> names = context.computeOnClient(mc -> MenuState.view().mates().stream().map(MenuState.Mate::name).toList());
            if (!names.equals(List.of("Ember"))) throw new AssertionError("second world shows " + names + " instead of [Ember]");
            context.runOnClient(mc -> mc.player.connection.sendCommand("vml summon Ember"));
            second.getServer().waitFor(server -> server.getPlayerList().getPlayerByName("Ember") != null, 200);
            String remembered = second.getServer().computeOnServer(server -> {
                var c = com.voxelmind.local.companion.CompanionManager.get().all().iterator().next();
                return c.state.persona;
            });
            if (!remembered.startsWith("Warm")) throw new AssertionError("Ember lost its character between worlds: " + remembered);
            context.setScreen(() -> new CompanionsScreen(null));
            context.waitTicks(30);
            context.takeScreenshot("vml-14-second-world");
            context.setScreen(() -> null);
        }
    }

    /**
     * An update from a 0.5.0 config that thought with VoxelMind Sparks: the title screen says once that Sparks ended and
     * leads to the AI settings; the other settings stay and the old sign-in leaves the file. The test's own config comes back after.
     */
    private static void sparksEnded(ClientGameTestContext context) {
        Path file = LocalConfig.path();
        try {
            String before = Files.exists(file) ? Files.readString(file) : null;
            Files.writeString(file, """
                    {"apiKey": "sk-or-v1-kept", "baseUrl": "https://api.voxel-mind.com/v1", "voxelmindKey": "vm_test_token",
                     "voxelmindName": "Steve", "model": "acme/kept-model"}""");
            context.runOnClient(mc -> LocalConfig.load());
            context.waitForScreen(SparksEndedScreen.class);
            context.waitTicks(5);
            context.takeScreenshot("vml-00-sparks-ended");
            String migrated = Files.readString(file);
            if (migrated.contains("vm_test_token") || !migrated.contains("sk-or-v1-kept") || !migrated.contains("acme/kept-model")
                    || !migrated.contains("api.player2.game")) {
                throw new AssertionError("config after the Sparks migration: " + migrated);
            }
            context.clickScreenButton("voxelmind_local.sparks_ended.choose");
            context.waitForScreen(SettingsScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("vml-00b-settings-after-sparks");
            context.clickScreenButton("gui.cancel");
            context.waitForScreen(TitleScreen.class);
            if (context.computeOnClient(mc -> LocalConfig.get().sparksEndedNotice)) throw new AssertionError("the Sparks notice came twice");
            if (before == null) Files.deleteIfExists(file);
            else Files.writeString(file, before);
            context.runOnClient(mc -> LocalConfig.load());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static final java.util.function.Supplier<Path> GLOBAL_DIR = () ->
            FabricLoader.getInstance().getGameDir().resolve("voxelmind-local").resolve("companions");

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Clicks inside a panel of the given height: {@code dx} from the panel's left edge (or from the screen
     * centre when {@code fromLeft} is false), {@code dy} from its top (or, negative, from its bottom).
     */
    private static final int CREATE_H = 268;
    /** The editor's height once Builder or God is chosen, and its width (CompanionEditScreen since 0.5.0). */
    private static final int HOSTED_H = 240;
    private static final int EDIT_W = 400;

    private static void click(ClientGameTestContext context, int dx, int dy, boolean fromLeft, int panelHeight) {
        double[] pos = context.computeOnClient(mc -> {
            var s = mc.gui.screen();
            int h = Math.min(panelHeight, s.height - 8);
            int top = Math.max(4, (s.height - h) / 2);
            int left = s.width / 2 - Math.min(EDIT_W, s.width - 8) / 2;
            double x = fromLeft ? left + dx : s.width / 2.0 + dx;
            double y = dy >= 0 ? top + dy : top + h + dy;
            double scale = mc.getWindow().getGuiScale();
            return new double[]{x * scale, y * scale};
        });
        context.getInput().setCursorPos(pos[0], pos[1]);
        context.getInput().pressMouse(0);
    }

    private static Player companion(Minecraft mc) {
        if (mc.level == null) return null;
        for (Player p : mc.level.players()) if (p.getGameProfile().name().equals("Lysa")) return p;
        return null;
    }

    private static void lookAtCompanion(Minecraft mc) {
        Player lysa = companion(mc);
        if (lysa == null || mc.player == null) return;
        double dx = lysa.getX() - mc.player.getX();
        double dz = lysa.getZ() - mc.player.getZ();
        double dy = lysa.getEyeY() - mc.player.getEyeY();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
    }
}
