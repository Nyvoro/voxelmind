package com.voxelmind.local.client;

import com.voxelmind.local.companion.CompanionManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Dev-only (never packaged): drives a real client through the mod once it sits in a world. It summons a
 * companion with the command, opens every menu screen (a render error crashes the client, which the
 * smoke script reports), lets {@link ServerSmoke} run the body actions while the monitor is open, then
 * dismisses the companion and quits. Every step logs a "VML-SMOKE" line.
 */
public final class ClientSmoke implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("VML-SMOKE");
    private static final int SETTLE_TICKS = 100;
    private static final int MAX_WORLD_TICKS = 20 * 60 * 9;
    private static final String NAME = "Smoky";
    private static final int SHOT_TICKS_BEFORE_NEXT = 8;
    private static final int BODY_SHOT_TICK = 160;

    private record Step(String name, int waitTicks, Consumer<Minecraft> action) {}

    private final List<Step> steps = new ArrayList<>();
    private int worldTicks;
    private int index = -1;
    private int ticksLeft;
    private boolean waitingForBody;
    private int bodyWaitTicks;
    private int failures;
    private UUID mateId;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            worldTicks = 0;
            return;
        }
        worldTicks++;
        if (worldTicks < SETTLE_TICKS) return;
        if (worldTicks > MAX_WORLD_TICKS) {
            LOG.error("VML-SMOKE FAIL timeout after {} ticks in the world (step {})", worldTicks, index);
            mc.stop();
            return;
        }
        if (steps.isEmpty()) build();
        if (waitingForBody) {
            if (ServerSmoke.phase < 3) {
                if (++bodyWaitTicks == BODY_SHOT_TICK) LOG.info("VML-SMOKE shot monitor while the body works");
                return;
            }
            waitingForBody = false;
        }
        if (ticksLeft > 0) {
            // One tick-count before the next step, so tools/capture-shots.ps1 can photograph the open screen.
            if (ticksLeft == SHOT_TICKS_BEFORE_NEXT && index >= 0 && steps.get(index).waitTicks() >= 20) {
                LOG.info("VML-SMOKE shot {}", steps.get(index).name());
            }
            ticksLeft--;
            return;
        }
        index++;
        if (index >= steps.size()) {
            LOG.info("VML-SMOKE done failures={}", failures);
            mc.stop();
            return;
        }
        Step step = steps.get(index);
        try {
            step.action().accept(mc);
            LOG.info("VML-SMOKE ok {}", step.name());
        } catch (Throwable t) {
            failures++;
            LOG.error("VML-SMOKE FAIL {}", step.name(), t);
        }
        ticksLeft = step.waitTicks();
    }

    private void build() {
        steps.add(new Step("summon", 140, mc -> mc.player.connection.sendCommand("vml summon " + NAME + " friendly smoke test persona")));
        steps.add(new Step("companions screen", 60, mc -> {
            mc.gui.setScreen(new CompanionsScreen(null));
        }));
        steps.add(new Step("snapshot has the companion", 1, mc -> {
            MenuState.View view = MenuState.view();
            if (view == null) throw new IllegalStateException("no snapshot arrived");
            for (MenuState.Mate m : view.mates()) {
                if (m.name().equals(NAME)) {
                    mateId = m.id();
                    LOG.info("VML-SMOKE mate {} present={} hp={} kind={}", m.name(), m.present(), m.hp(), m.kind());
                }
            }
            if (mateId == null) throw new IllegalStateException("snapshot has no " + NAME + " in " + view.mates());
        }));
        steps.add(new Step("server has a companion body", 1, mc -> {
            MinecraftServer server = mc.getSingleplayerServer();
            List<String> players = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) players.add(p.getName().getString());
            LOG.info("VML-SMOKE server players {}", players);
            if (!players.contains(NAME)) throw new IllegalStateException("no player body named " + NAME);
        }));
        steps.add(new Step("edit screen (existing companion)", 40, mc -> {
            MenuState.Mate mate = MenuState.view().find(mateId);
            mc.gui.setScreen(new CompanionEditScreen(mc.gui.screen(), mate));
        }));
        steps.add(new Step("edit screen (new companion)", 40, mc -> mc.gui.setScreen(new CompanionEditScreen(mc.gui.screen(), null))));
        steps.add(new Step("settings screen", 40, mc -> mc.gui.setScreen(new SettingsScreen(mc.gui.screen()))));
        steps.add(new Step("model picker", 80, mc -> mc.gui.setScreen(new ModelPickerScreen(mc.gui.screen(),
                "https://openrouter.ai/api/v1", "", "deepseek/deepseek-v4-flash-0731", id -> { }))));
        steps.add(new Step("monitor screen stays open during the body tests", 5, mc -> {
            mc.gui.setScreen(new MonitorScreen(null, mateId));
            ServerSmoke.phase = 2;
            waitingForBody = true;
        }));
        steps.add(new Step("memories screen", 40, mc -> mc.gui.setScreen(new MemoriesScreen(mc.gui.screen(), mateId))));
        steps.add(new Step("pause screen with the VoxelMind button", 40, mc -> {
            Screen pause = new PauseScreen(true);
            mc.gui.setScreen(pause);
        }));
        steps.add(new Step("close screens", 5, mc -> mc.gui.setScreen(null)));
        steps.add(new Step("dismiss", 60, mc -> mc.player.connection.sendCommand("vml dismiss " + NAME)));
        steps.add(new Step("companion is gone from the server", 1, mc -> {
            CompanionManager manager = CompanionManager.get();
            boolean stillThere = false;
            for (ServerPlayer p : mc.getSingleplayerServer().getPlayerList().getPlayers()) {
                if (p.getName().getString().equals(NAME)) stillThere = true;
            }
            LOG.info("VML-SMOKE after dismiss: body in player list={} companions={}", stillThere, manager == null ? -1 : manager.all().size());
            if (stillThere) throw new IllegalStateException("body still in the player list after dismiss");
        }));
    }
}
