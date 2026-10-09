package com.voxelmind.local.client;

import com.voxelmind.local.action.BodyAction;
import com.voxelmind.local.action.ChestAction;
import com.voxelmind.local.action.CollectFurnaceAction;
import com.voxelmind.local.action.CraftAction;
import com.voxelmind.local.action.DigAction;
import com.voxelmind.local.action.DropAction;
import com.voxelmind.local.action.GoToAction;
import com.voxelmind.local.action.MineBlockAction;
import com.voxelmind.local.action.PlaceBlockAction;
import com.voxelmind.local.action.SmeltAction;
import com.voxelmind.local.action.SurveyAction;
import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.companion.CompanionManager;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Dev-only (never packaged): the server half of the client smoke run. Once the client has summoned a
 * companion and walked through the menus (phase 2), this drives the real body actions of the companion
 * - walk, craft, smelt, mine, place, dig, chest, drop - without any language model and logs one
 * "VML-SMOKE body" line per step. The same lines on every Minecraft version are the evidence that
 * the body and its recipe / furnace / block code behave the same everywhere.
 */
public final class ServerSmoke implements ModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("VML-SMOKE");
    /** 0 = client prepares, 2 = body tests may run, 3 = body tests finished. */
    static volatile int phase = 0;

    private static final int STEP_TIMEOUT_TICKS = 20 * 90;

    private record Step(String name, Supplier<BodyAction> action) {}

    private final List<Step> steps = new ArrayList<>();
    private int index = -1;
    private BodyAction running;
    private int runningTicks;
    private int settleTicks;
    private boolean prepared;
    private int failures;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
    }

    private void tick(MinecraftServer server) {
        if (phase != 2) return;
        CompanionManager manager = CompanionManager.get();
        if (manager == null || manager.all().isEmpty()) return;
        Companion c = manager.all().iterator().next();
        CompanionPlayer body = c.body();
        if (body == null) return;

        if (!prepared) {
            prepared = true;
            prepare(server, c, body);
            settleTicks = 40;
            return;
        }
        if (settleTicks > 0) {
            settleTicks--;
            return;
        }
        if (running != null) {
            runningTicks++;
            if (!running.finished() && runningTicks < STEP_TIMEOUT_TICKS) return;
            Step step = steps.get(index);
            if (!running.finished()) {
                LOG.error("VML-SMOKE body {} FAIL still running after {} ticks: {}", step.name(), runningTicks, running.progress());
                failures++;
                c.stopAction("smoke timeout");
            } else {
                LOG.info("VML-SMOKE body {} outcome={} result={}", step.name(), running.outcome() == null ? "?" : running.outcome().id,
                        clip(running.result()));
            }
            running = null;
            settleTicks = 10;
            return;
        }
        index++;
        if (index >= steps.size()) {
            LOG.info("VML-SMOKE body done failures={} pos={} inventory={}", failures, body.blockPosition().toShortString(), inventory(body));
            phase = 3;
            return;
        }
        Step step = steps.get(index);
        try {
            running = step.action().get();
            runningTicks = 0;
            c.startAction(running);
        } catch (Throwable t) {
            LOG.error("VML-SMOKE body {} FAIL could not start", step.name(), t);
            failures++;
            running = null;
        }
    }

    private void prepare(MinecraftServer server, Companion c, CompanionPlayer body) {
        run(server, "difficulty peaceful");
        run(server, "time set day");
        BlockPos at = body.blockPosition();
        LOG.info("VML-SMOKE body start at {} in {} (survival={})", at.toShortString(), body.level().dimension(), !body.isCreative());
        body.getInventory().add(new ItemStack(Items.OAK_LOG, 4));
        body.getInventory().add(new ItemStack(Items.RAW_IRON, 2));
        body.getInventory().add(new ItemStack(Items.COAL, 2));
        body.getInventory().add(new ItemStack(Items.FURNACE, 1));
        body.getInventory().add(new ItemStack(Items.DIRT, 6));
        body.getInventory().add(new ItemStack(Items.CHEST, 1));
        int x = at.getX();
        int y = at.getY();
        int z = at.getZ();
        steps.add(new Step("go_to", () -> new GoToAction("go_to(target=\"" + (x + 9) + " " + y + " " + z + "\")", (x + 9) + " " + y + " " + z)));
        steps.add(new Step("craft planks", () -> new CraftAction("craft(item=\"oak_planks\", amount=12)", "oak_planks", 12)));
        steps.add(new Step("craft sticks", () -> new CraftAction("craft(item=\"stick\", amount=4)", "stick", 4)));
        steps.add(new Step("craft crafting_table", () -> new CraftAction("craft(item=\"crafting_table\")", "crafting_table", 1)));
        steps.add(new Step("craft wooden_pickaxe", () -> new CraftAction("craft(item=\"wooden_pickaxe\")", "wooden_pickaxe", 1)));
        steps.add(new Step("craft furnace_missing_input", () -> new CraftAction("craft(item=\"golden_apple\")", "golden_apple", 1)));
        steps.add(new Step("smelt iron_ingot", () -> new SmeltAction("smelt(item=\"iron_ingot\", amount=1, fuel=\"coal\")", "iron_ingot", 1, "coal")));
        steps.add(new Step("collect_furnace", () -> new CollectFurnaceAction("collect_furnace()", "")));
        steps.add(new Step("mine_block grass_block", () -> new MineBlockAction("mine_block(target=\"grass_block\")", "grass_block")));
        steps.add(new Step("place_block dirt", () -> new PlaceBlockAction("place_block(block=\"dirt\")", "dirt", (x + 9) + " " + y + " " + (z + 2))));
        steps.add(new Step("use_chest store", () -> {
            BlockPos chest = new BlockPos(x + 9, y, z - 2);
            body.level().setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            return new ChestAction("use_chest(action=\"store\", item=\"dirt\")", "store", "dirt", 2, chest.getX() + " " + chest.getY() + " " + chest.getZ());
        }));
        steps.add(new Step("dig down", () -> new DigAction("dig(direction=\"down\", distance=3)", "down", 3)));
        steps.add(new Step("survey", () -> new SurveyAction("survey()", "", 6)));
        steps.add(new Step("drop to owner", () -> new DropAction("drop(item=\"dirt\", count=1, target=\"owner\")", "dirt", 1, "owner")));
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    private static String inventory(ServerPlayer player) {
        StringBuilder out = new StringBuilder();
        new java.util.TreeMap<String, Integer>(Inv.snapshot(player).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(e -> Names.id(e.getKey()), java.util.Map.Entry::getValue)))
                .forEach((name, count) -> out.append(count).append('x').append(name).append(' '));
        return out.toString().trim();
    }

    private static String clip(String text) {
        String flat = text.replace('\n', ' ');
        return flat.length() > 140 ? flat.substring(0, 140) + "..." : flat;
    }
}
