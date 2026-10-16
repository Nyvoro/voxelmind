package com.voxelmind.local.test;

import com.mojang.authlib.GameProfile;
import com.voxelmind.local.action.ActionContext;
import com.voxelmind.local.action.AttackAction;
import com.voxelmind.local.action.BodyAction;
import com.voxelmind.local.action.BuildShelterAction;
import com.voxelmind.local.action.ChestAction;
import com.voxelmind.local.action.CraftAction;
import com.voxelmind.local.action.DigAction;
import com.voxelmind.local.action.DropAction;
import com.voxelmind.local.action.EatAction;
import com.voxelmind.local.action.EquipAction;
import com.voxelmind.local.action.ExploreAction;
import com.voxelmind.local.action.GatherAction;
import com.voxelmind.local.action.GoToAction;
import com.voxelmind.local.action.MineBlockAction;
import com.voxelmind.local.action.PlaceBlockAction;
import com.voxelmind.local.action.PlaceBlocksAction;
import com.voxelmind.local.action.SmeltAction;
import com.voxelmind.local.body.CompanionConnection;
import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless checks that the companion body moves, mines, crafts, places, fights and eats in a real server world. */
public class BodyGameTests {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static CompanionPlayer spawn(GameTestHelper helper, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos abs = helper.absolutePos(rel);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "TestBody" + COUNTER.incrementAndGet());
        CompanionPlayer body = new CompanionPlayer(server, level, profile);
        body.snapTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0f, 0f);
        server.getPlayerList().placeNewPlayer(new CompanionConnection(body), body, CommonListenerCookie.createInitial(profile, false));
        body.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        body.setGameMode(GameType.SURVIVAL);
        return body;
    }

    private static BodyAction run(GameTestHelper helper, CompanionPlayer body, BodyAction action) {
        action.start(new ActionContext(() -> body, UUID.randomUUID()));
        int[] t = {0};
        helper.onEachTick(() -> {
            action.tick();
            if (Boolean.getBoolean("vml.trace") && t[0]++ % 20 == 0) {
                System.out.println("TRACE " + action.call() + " t=" + t[0] + " pos=" + helper.relativePos(body.blockPosition())
                        + " y=" + String.format("%.2f", body.getY()) + " ground=" + body.onGround() + " | " + action.progress()
                        + (action.finished() ? " | " + action.outcome() + ": " + action.result() : ""));
            }
        });
        return action;
    }

    /** Runs actions one after another from a single tick handler. */
    private static void runSequence(GameTestHelper helper, CompanionPlayer body, BodyAction... actions) {
        ActionContext ctx = new ActionContext(() -> body, UUID.randomUUID());
        int[] index = {0};
        actions[0].start(ctx);
        helper.onEachTick(() -> {
            BodyAction current = actions[index[0]];
            current.tick();
            if (current.finished() && index[0] + 1 < actions.length) {
                index[0]++;
                actions[index[0]].start(ctx);
            }
        });
    }

    private static void finishWhen(GameTestHelper helper, CompanionPlayer body, Runnable assertion) {
        helper.succeedWhen(() -> {
            assertion.run();
            helper.getLevel().getServer().getPlayerList().remove(body);
        });
    }

    private static String abs(GameTestHelper helper, int x, int y, int z) {
        BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static void assertDone(GameTestHelper helper, BodyAction action) {
        helper.assertTrue(action.finished(), "action still running: " + action.progress());
        helper.assertTrue(action.outcome() == BodyAction.Outcome.DONE, action.call() + " ended " + action.outcome() + ": " + action.result());
    }

    @GameTest(maxTicks = 100)
    public void bodyFallsOntoFloor(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(3, 5, 3));
        double floorTop = helper.absolutePos(new BlockPos(3, 1, 3)).getY();
        finishWhen(helper, body, () -> {
            helper.assertTrue(body.onGround(), "body is not on the ground");
            helper.assertTrue(Math.abs(body.getY() - floorTop) < 0.01, "body stands at y=" + body.getY() + ", expected " + floorTop);
        });
    }

    @GameTest(maxTicks = 300)
    public void walksOverAStepToCoordinates(GameTestHelper helper) {
        floor(helper);
        for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
        for (int x = 5; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        BodyAction action = run(helper, body, new GoToAction("go_to(test)", abs(helper, 6, 2, 6)));
        BlockPos goal = helper.absolutePos(new BlockPos(6, 2, 6));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            BlockPos at = body.blockPosition();
            helper.assertTrue(at.getY() == goal.getY() && Math.abs(at.getX() - goal.getX()) <= 1 && Math.abs(at.getZ() - goal.getZ()) <= 1,
                    "ended at " + at + ", goal " + goal + "; " + action.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void minesALogAndPicksItUp(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.OAK_LOG);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        BodyAction action = run(helper, body, new MineBlockAction("mine_block(oak_log)", "oak_log"));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertTrue(Inv.count(body, Items.OAK_LOG) == 1, "inventory has " + Inv.count(body, Items.OAK_LOG) + " oak_log; " + action.result());
            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(5, 1, 5))).isAir(), "log still there");
        });
    }

    @GameTest(maxTicks = 900)
    public void gathersThreeWood(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(6, 1, 1), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(6, 1, 6), Blocks.BIRCH_LOG);
        helper.setBlock(new BlockPos(1, 1, 6), Blocks.OAK_LOG);
        CompanionPlayer body = spawn(helper, new BlockPos(3, 1, 3));
        body.getInventory().add(new ItemStack(Items.WOODEN_AXE));
        BodyAction action = run(helper, body, new GatherAction("gather(wood,3)", "wood", 3));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            int logs = Inv.count(body, Items.OAK_LOG) + Inv.count(body, Items.BIRCH_LOG);
            helper.assertTrue(logs >= 3, "gathered " + logs + " logs; " + action.result());
        });
    }

    @GameTest(maxTicks = 200)
    public void craftsPlanksSticksAndATable(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(3, 1, 3));
        body.getInventory().add(new ItemStack(Items.OAK_LOG, 3));
        BodyAction planks = new CraftAction("craft(oak_planks)", "oak_planks", 12);
        BodyAction table = new CraftAction("craft(crafting_table)", "crafting_table", 1);
        BodyAction sticks = new CraftAction("craft(stick)", "stick", 4);
        runSequence(helper, body, planks, table, sticks);
        finishWhen(helper, body, () -> {
            assertDone(helper, planks);
            assertDone(helper, table);
            assertDone(helper, sticks);
            helper.assertTrue(Inv.count(body, Items.CRAFTING_TABLE) == 1, "no crafting table");
            helper.assertTrue(Inv.count(body, Items.STICK) == 4, "sticks: " + Inv.count(body, Items.STICK));
            helper.assertTrue(Inv.count(body, Items.OAK_PLANKS) == 6, "planks left: " + Inv.count(body, Items.OAK_PLANKS));
        });
    }

    @GameTest(maxTicks = 300)
    public void craftsAPickaxePlacingItsOwnTable(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(3, 1, 3));
        body.getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
        body.getInventory().add(new ItemStack(Items.STICK, 2));
        body.getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
        BodyAction action = run(helper, body, new CraftAction("craft(wooden_pickaxe)", "wooden_pickaxe", 1));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertTrue(Inv.count(body, Items.WOODEN_PICKAXE) == 1, "no pickaxe; " + action.result());
            helper.assertTrue(Inv.count(body, Items.CRAFTING_TABLE) == 0, "table was not placed; " + action.result());
            helper.assertTrue(action.result().contains("placed your crafting_table"), action.result());
        });
    }

    @GameTest(maxTicks = 200)
    public void placesABlockAtCoordinates(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        body.getInventory().add(new ItemStack(Items.COBBLESTONE, 5));
        BodyAction action = run(helper, body, new PlaceBlockAction("place_block(test)", "cobblestone", abs(helper, 5, 1, 5)));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(5, 1, 5));
            helper.assertTrue(Inv.count(body, Items.COBBLESTONE) == 4, "cobblestone left " + Inv.count(body, Items.COBBLESTONE));
        });
    }

    @GameTest(maxTicks = 600)
    public void killsAZombie(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        body.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(6, 1, 6));
        new EquipAction("equip(diamond_sword)", "diamond_sword").start(new ActionContext(() -> body, UUID.randomUUID()));
        BodyAction action = run(helper, body, new AttackAction("attack(zombie)", "zombie"));
        finishWhen(helper, body, () -> {
            helper.assertTrue(!zombie.isAlive(), "zombie still alive with HP " + zombie.getHealth());
            assertDone(helper, action);
        });
    }

    @GameTest(maxTicks = 200)
    public void eatsBreadWhenHungry(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(3, 1, 3));
        body.getInventory().add(new ItemStack(Items.BREAD, 2));
        body.getFoodData().setFoodLevel(10);
        BodyAction action = run(helper, body, new EatAction("eat(bread)", "bread"));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertTrue(body.getFoodData().getFoodLevel() > 10, "food still " + body.getFoodData().getFoodLevel());
            helper.assertTrue(Inv.count(body, Items.BREAD) == 1, "bread left " + Inv.count(body, Items.BREAD));
        });
    }

    @GameTest(maxTicks = 900, skyAccess = true)
    public void climbsOutOfAPit(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 4; y++) for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            if (!(x == 3 && z == 3)) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        }
        CompanionPlayer body = spawn(helper, new BlockPos(3, 1, 3));
        body.getInventory().add(new ItemStack(Items.COBBLESTONE, 10));
        body.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        BodyAction action = run(helper, body, new GoToAction("go_to(surface)", abs(helper, 6, 5, 6)));
        BlockPos goal = helper.absolutePos(new BlockPos(6, 5, 6));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertTrue(body.blockPosition().getY() == goal.getY(), "ended at " + body.blockPosition() + "; " + action.result());
        });
    }

    @GameTest(maxTicks = 900)
    public void digsThroughAWall(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 4; y++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(4, y, z), Blocks.STONE);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 3));
        body.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        BodyAction action = run(helper, body, new GoToAction("go_to(behind wall)", abs(helper, 6, 1, 3)));
        BlockPos goal = helper.absolutePos(new BlockPos(6, 1, 3));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertTrue(body.blockPosition().distSqr(goal) <= 2, "ended at " + body.blockPosition() + "; " + action.result());
            helper.assertTrue(action.result().contains("mined"), action.result());
        });
    }

    @GameTest(maxTicks = 1200, skyAccess = true)
    public void tunnelsForwardThroughStone(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 3; y++) for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            if (!(x == 1 && z == 1 && y <= 2)) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        }
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        body.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        body.setYRot(0f); // facing south (+z)
        BlockPos start = body.blockPosition();
        BodyAction action = run(helper, body, new DigAction("dig(forward,4)", "forward", 4));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            int moved = (int) Math.round(Math.sqrt(start.distSqr(body.blockPosition())));
            helper.assertTrue(moved == 4, "moved " + moved + "; " + action.result());
            helper.assertTrue(action.result().startsWith("dug south 4 of 4"), action.result());
        });
    }

    @GameTest(maxTicks = 600)
    public void buildsAWall(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        body.getInventory().add(new ItemStack(Items.COBBLESTONE, 8));
        java.util.List<PlaceBlocksAction.Entry> wall = new java.util.ArrayList<>();
        for (int y = 1; y <= 2; y++) for (int z = 2; z <= 4; z++) wall.add(new PlaceBlocksAction.Entry(helper.absolutePos(new BlockPos(5, y, z)), "cobblestone"));
        BodyAction action = run(helper, body, new PlaceBlocksAction("place_blocks(wall)", wall));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            for (int y = 1; y <= 2; y++) for (int z = 2; z <= 4; z++) helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(5, y, z));
            helper.assertTrue(Inv.count(body, Items.COBBLESTONE) == 2, "cobblestone left " + Inv.count(body, Items.COBBLESTONE) + "; " + action.result());
        });
    }

    @GameTest(maxTicks = 1000)
    public void smeltsRawIron(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.FURNACE);
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        body.getInventory().add(new ItemStack(Items.RAW_IRON, 3));
        body.getInventory().add(new ItemStack(Items.COAL, 2));
        BodyAction action = run(helper, body, new SmeltAction("smelt(raw_iron)", "raw_iron", 3, "coal"));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertTrue(Inv.count(body, Items.IRON_INGOT) == 3, "ingots " + Inv.count(body, Items.IRON_INGOT) + "; " + action.result());
            helper.assertTrue(Inv.count(body, Items.COAL) == 1, "coal left " + Inv.count(body, Items.COAL));
        });
    }

    @GameTest(maxTicks = 400)
    public void storesAndRetrievesFromAChest(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.CHEST);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        body.getInventory().add(new ItemStack(Items.DIRT, 10));
        BodyAction store = new ChestAction("use_chest(store)", "store", "dirt", -1, "");
        BodyAction retrieve = new ChestAction("use_chest(retrieve)", "retrieve", "dirt", 4, "");
        runSequence(helper, body, store, retrieve);
        finishWhen(helper, body, () -> {
            assertDone(helper, store);
            assertDone(helper, retrieve);
            helper.assertTrue(Inv.count(body, Items.DIRT) == 4, "dirt carried " + Inv.count(body, Items.DIRT) + "; " + retrieve.result());
        });
    }

    @GameTest(maxTicks = 900, skyAccess = true)
    public void climbsDownFromACanopy(GameTestHelper helper) {
        floor(helper);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            helper.setBlock(new BlockPos(x, 4, z), Blocks.OAK_LEAVES);
            helper.setBlock(new BlockPos(x, 5, z), Blocks.OAK_LEAVES);
        }
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(2, 3, 2), Blocks.OAK_LOG);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 6, 1));
        BodyAction action = run(helper, body, new GoToAction("go_to(ground)", abs(helper, 6, 1, 6)));
        BlockPos goal = helper.absolutePos(new BlockPos(6, 1, 6));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertTrue(body.blockPosition().getY() == goal.getY(), "ended at " + body.blockPosition() + "; " + action.result());
        });
    }

    @GameTest(maxTicks = 2400, skyAccess = true)
    public void buildsAShelter(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        body.getInventory().add(new ItemStack(Items.OAK_PLANKS, 64));
        BlockPos center = helper.absolutePos(new BlockPos(4, 0, 4));
        BodyAction action = run(helper, body, new BuildShelterAction("build_shelter(test)", "oak_planks", center.getX() + " " + center.getZ()));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            int walls = 0;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 && Math.abs(dz) != 2) {
                    helper.assertTrue(helper.getLevel().getBlockState(center.offset(dx, 1, dz)).isAir(), "inside not hollow at " + dx + "," + dz);
                    continue;
                }
                if (helper.getLevel().getBlockState(center.offset(dx, 1, dz)).is(Blocks.OAK_PLANKS)) walls++;
            }
            helper.assertTrue(walls == 15, "wall ring at foot level has " + walls + " planks (expected 15 with one door); " + action.result());
            helper.assertTrue(helper.getLevel().getBlockState(center.offset(0, 3, 0)).is(Blocks.OAK_PLANKS), "no roof; " + action.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void handsBreadToAnotherPlayer(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer giver = spawn(helper, new BlockPos(1, 1, 1));
        CompanionPlayer taker = spawn(helper, new BlockPos(5, 1, 5));
        giver.getInventory().add(new ItemStack(Items.BREAD, 5));
        BodyAction action = run(helper, giver, new DropAction("drop(bread,3)", "bread", 3, taker.getGameProfile().name()));
        helper.onEachTick(() -> {
            if (Boolean.getBoolean("vml.trace") && helper.getTick() % 10 == 0) {
                for (var item : helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, giver.getBoundingBox().inflate(8))) {
                    System.out.println("TRACE item " + item.getItem() + " at " + item.position() + " taker at " + taker.position() + " giver at " + giver.position());
                }
            }
        });
        finishWhen(helper, giver, () -> {
            assertDone(helper, action);
            helper.assertTrue(Inv.count(taker, Items.BREAD) == 3, "taker has " + Inv.count(taker, Items.BREAD) + " bread; giver has "
                    + Inv.count(giver, Items.BREAD) + "; " + action.result());
            helper.getLevel().getServer().getPlayerList().remove(taker);
        });
    }

    @GameTest(maxTicks = 600)
    public void digsAShaftDown(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 4; y++) for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.DIRT);
        CompanionPlayer body = spawn(helper, new BlockPos(3, 5, 3));
        body.getInventory().add(new ItemStack(Items.WOODEN_SHOVEL));
        BodyAction action = run(helper, body, new DigAction("dig(down,3)", "down", 3));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            int y = helper.relativePos(body.blockPosition()).getY();
            helper.assertTrue(y == 2, "body at relative y=" + y + "; " + action.result());
            helper.assertTrue(Inv.count(body, Items.DIRT) >= 3, "dirt picked up: " + Inv.count(body, Items.DIRT));
        });
    }

    // ---- 0.4.3: building in a forest, cut-short walks under water, knockback ----

    /** Fills the test area with stone from y=1 to {@code top}, leaving out the given cells. */
    private static void stone(GameTestHelper helper, int top, java.util.function.Predicate<BlockPos> leaveOut) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) for (int y = 1; y <= top; y++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!leaveOut.test(p)) helper.setBlock(p, Blocks.STONE);
        }
    }

    @GameTest(maxTicks = 2400, skyAccess = true)
    public void shelterStandsOnTheGroundUnderABranch(GameTestHelper helper) {
        floor(helper);
        // A dark-oak-like branch above a corner of the footprint, open air below it: no ground to build on.
        helper.setBlock(new BlockPos(6, 5, 6), Blocks.DARK_OAK_LOG);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        body.getInventory().add(new ItemStack(Items.OAK_PLANKS, 64));
        BlockPos center = helper.absolutePos(new BlockPos(4, 0, 4));
        BodyAction action = run(helper, body, new BuildShelterAction("build_shelter(branch)", "oak_planks", center.getX() + " " + center.getZ()));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            int walls = 0;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if ((Math.abs(dx) == 2 || Math.abs(dz) == 2) && helper.getLevel().getBlockState(center.offset(dx, 1, dz)).is(Blocks.OAK_PLANKS)) walls++;
            }
            helper.assertTrue(walls == 15, "wall ring at foot level has " + walls + " planks; " + action.result());
            helper.assertTrue(helper.getLevel().getBlockState(center.offset(0, 3, 0)).is(Blocks.OAK_PLANKS), "no roof on the ground; " + action.result());
        });
    }

    @GameTest(maxTicks = 2400, skyAccess = true)
    public void shelterNamesTheTreesInItsFootprint(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(5, y, 5), Blocks.DARK_OAK_LOG);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        body.getInventory().add(new ItemStack(Items.OAK_PLANKS, 64));
        BlockPos center = helper.absolutePos(new BlockPos(4, 0, 4));
        BodyAction action = run(helper, body, new BuildShelterAction("build_shelter(trunk)", "oak_planks", center.getX() + " " + center.getZ()));
        BlockPos trunk = helper.absolutePos(new BlockPos(5, 1, 5));
        finishWhen(helper, body, () -> {
            helper.assertTrue(action.finished(), "still running: " + action.progress());
            helper.assertTrue(action.outcome() == BodyAction.Outcome.FAILED && action.result().contains("tree trunks or branches stand in the cells")
                    && action.result().contains("dark_oak_log at " + Names.pos(trunk)), "the trunk is not named: " + action.outcome() + ": " + action.result());
            helper.assertTrue(Inv.count(body, Items.OAK_PLANKS) == 64, "planks were placed: " + action.result());
        });
    }

    @GameTest(maxTicks = 200, skyAccess = true)
    public void shelterOverADeepTrenchIsBuiltBeyondTheCallLimit(GameTestHelper helper) {
        floor(helper);
        // Ground at y=5; the wall ring of the footprint stands in a trench down to the floor, so the walls take 7 blocks each.
        stone(helper, 5, p -> p.getX() >= 2 && p.getX() <= 6 && p.getZ() >= 2 && p.getZ() <= 6
                && (p.getX() == 2 || p.getX() == 6 || p.getZ() == 2 || p.getZ() == 6));
        CompanionPlayer body = spawn(helper, new BlockPos(0, 6, 0));
        for (int i = 0; i < 3; i++) body.getInventory().add(new ItemStack(Items.OAK_PLANKS, 64));
        BlockPos center = helper.absolutePos(new BlockPos(4, 0, 4));
        BodyAction action = run(helper, body, new BuildShelterAction("build_shelter(trench)", "oak_planks", center.getX() + " " + center.getZ()));
        helper.runAtTickTime(40, () -> {
            helper.assertTrue(!action.finished() || action.outcome() == BodyAction.Outcome.DONE, "ended: " + action.outcome() + ": " + action.result());
            helper.assertTrue(action.progress().contains(" of 135 placed"), "plan: " + action.progress() + " " + action.result());
            action.preempt("test over");
            helper.getLevel().getServer().getPlayerList().remove(body);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 600)
    public void placeBlocksNamesTheOccupiedCellAfterTheSecondPass(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE);
        CompanionPlayer body = spawn(helper, new BlockPos(7, 1, 2));
        body.getInventory().add(new ItemStack(Items.COBBLESTONE, 4));
        java.util.List<PlaceBlocksAction.Entry> cells = new java.util.ArrayList<>();
        for (BlockPos rel : new BlockPos[]{new BlockPos(3, 1, 2), new BlockPos(5, 1, 2), new BlockPos(5, 2, 2), new BlockPos(6, 2, 2)}) {
            cells.add(new PlaceBlocksAction.Entry(helper.absolutePos(rel), "cobblestone"));
        }
        BodyAction action = run(helper, body, new PlaceBlocksAction("place_blocks(two passes)", cells));
        BlockPos occupied = helper.absolutePos(new BlockPos(3, 1, 2));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(6, 2, 2));
            helper.assertTrue(action.result().contains("placed 3 of 4"), action.result());
            helper.assertTrue(action.result().contains("1× occupied by stone at " + Names.pos(occupied)), "the occupied cell is not named: " + action.result());
        });
    }

    /** Walks toward a cell and ends itself a few ticks into the walk: an action cut short mid-route. */
    private static final class CutShortWalk extends BodyAction {
        private final BlockPos to;

        CutShortWalk(BlockPos to) {
            super("cut_short", "cut_short()");
            this.to = to;
        }

        @Override
        protected void onStart(ActionContext ctx) {
            nav.allowDig(false);
            nav.navigate(to, p -> p.equals(to));
        }

        @Override
        protected void onTick(ActionContext ctx) {
            nav.tick();
            if (ticks >= 3) done("ended early");
        }
    }

    /** Stone up to y=5 with a 3×3 pool of water (x and z 2..4) down to the floor. */
    private static boolean pool(BlockPos p) {
        return p.getX() >= 2 && p.getX() <= 4 && p.getZ() >= 2 && p.getZ() <= 4;
    }

    @GameTest(maxTicks = 600, skyAccess = true)
    public void anActionThatEndsItsWalkUnderWaterSwimsUpToAirFirst(GameTestHelper helper) {
        floor(helper);
        stone(helper, 5, BodyGameTests::pool);
        for (int x = 2; x <= 4; x++) for (int z = 2; z <= 4; z++) for (int y = 1; y <= 5; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.WATER);
        CompanionPlayer body = spawn(helper, new BlockPos(4, 1, 4));
        BodyAction action = run(helper, body, new CutShortWalk(helper.absolutePos(new BlockPos(2, 1, 2))));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertTrue(!body.isUnderWater(), "the head is under water at " + body.blockPosition() + "; " + action.result());
            helper.assertTrue(action.result().startsWith("ended early; then you swam up to air and stand at "), action.result());
            helper.assertTrue(helper.relativePos(body.blockPosition()).getY() == 6, "not on the rim: " + helper.relativePos(body.blockPosition()));
        });
    }

    @GameTest(maxTicks = 200, skyAccess = true)
    public void exploreSaysHowDeepAndUnreachableTheFindIs(GameTestHelper helper) {
        floor(helper);
        stone(helper, 5, p -> p.equals(new BlockPos(6, 1, 6)));
        helper.setBlock(new BlockPos(6, 1, 6), Blocks.WATER);
        helper.spawn(EntityTypes.COD, new BlockPos(6, 1, 6));
        CompanionPlayer body = spawn(helper, new BlockPos(1, 6, 1));
        BodyAction action = run(helper, body, new ExploreAction("explore(cod)", "cod", "north", "nearby"));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            String r = action.result();
            helper.assertTrue(r.contains("5 blocks below you") && r.contains("under water 1 block deep")
                    && r.contains("no walking or swimming route to within reach of it was found"), "where it is is not said: " + r);
        });
    }

    @GameTest(maxTicks = 300)
    public void aPlayersHitKnocksTheBodyBack(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(3, 1, 4));
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Hitter" + COUNTER.incrementAndGet());
        ServerPlayer hitter = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        BlockPos at = helper.absolutePos(new BlockPos(2, 1, 4));
        hitter.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, -90f, 0f);
        level.getServer().getPlayerList().placeNewPlayer(new CompanionConnection(hitter), hitter, CommonListenerCookie.createInitial(profile, false));
        hitter.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        hitter.setGameMode(GameType.SURVIVAL);
        double[] before = new double[2];
        // Past the 60 ticks a freshly joined player cannot be hurt.
        helper.runAtTickTime(80, () -> {
            before[0] = body.getX();
            float health = body.getHealth();
            hitter.attack(body);
            // Read at once: a well-fed body heals a light hit within ticks.
            before[1] = health - body.getHealth();
        });
        helper.runAtTickTime(100, () -> {
            double moved = body.getX() - before[0];
            level.getServer().getPlayerList().remove(hitter);
            level.getServer().getPlayerList().remove(body);
            helper.assertTrue(before[1] > 0, "the hit did no damage");
            helper.assertTrue(moved > 0.3, "the body was knocked back only " + moved + " blocks");
            helper.succeed();
        });
    }

    /**
     * A sword fight against a zombie on open ground, from tick 70 on (a fresh body is unhurtable for 60 ticks): the
     * damage the body takes, and the ticks it ends inside the zombie's melee range while its own attack recharges.
     */
    @GameTest(maxTicks = 1500, skyAccess = true)
    public void fightsAZombieFromReachAndStepsBackWhileRecharging(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 4));
        body.getInventory().add(new ItemStack(Items.IRON_SWORD));
        ActionContext ctx = new ActionContext(() -> body, UUID.randomUUID());
        new EquipAction("equip(iron_sword)", "iron_sword").start(ctx);
        float[] damage = {0};
        int[] inItsRange = {0};
        body.setListener(new CompanionPlayer.BodyListener() {
            @Override
            public void onHurt(net.minecraft.world.damagesource.DamageSource source, float healthLost) {
                damage[0] += healthLost;
            }

            @Override
            public void onDeath(net.minecraft.world.damagesource.DamageSource source, net.minecraft.network.chat.Component deathMessage) {
            }

            @Override
            public void onDirectMessage(net.minecraft.network.chat.Component sender, net.minecraft.network.chat.Component content,
                                        net.minecraft.network.chat.ChatType.Bound chatType) {
            }
        });
        Zombie[] zombie = {null};
        BodyAction action = new AttackAction("attack(zombie)", "zombie");
        double reach = Math.sqrt(2.04) - 0.6;
        helper.onEachTick(() -> {
            if (helper.getTick() == 70) {
                zombie[0] = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(6, 1, 4));
                // A helmet keeps the zombie from burning under the open sky.
                zombie[0].setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                action.start(ctx);
            }
            if (zombie[0] == null || action.finished()) return;
            action.tick();
            if (zombie[0].isAlive() && body.getAttackStrengthScale(0.5f) < 0.95f
                    && zombie[0].getBoundingBox().inflate(reach, 0, reach).intersects(body.getBoundingBox())) inItsRange[0]++;
        });
        finishWhen(helper, body, () -> {
            helper.assertTrue(zombie[0] != null && !zombie[0].isAlive(), "zombie still alive");
            assertDone(helper, action);
            helper.assertTrue(damage[0] < FIGHT_DAMAGE, "the body took " + damage[0] + " damage; " + action.result());
            helper.assertTrue(inItsRange[0] <= FIGHT_TICKS_IN_RANGE, inItsRange[0] + " ticks inside the zombie's melee range while recharging");
        });
    }

    /** One zombie hit (3 on normal difficulty) fails it: 0.4.2 took one in this fight and stood 2 ticks in its range; 0.4.3 none. */
    private static final float FIGHT_DAMAGE = 3f;
    private static final int FIGHT_TICKS_IN_RANGE = 1;
}
