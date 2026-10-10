package com.voxelmind.local.test;

import com.mojang.authlib.GameProfile;
import com.voxelmind.local.action.ActionContext;
import com.voxelmind.local.action.BodyAction;
import com.voxelmind.local.action.DigAction;
import com.voxelmind.local.action.GatherAction;
import com.voxelmind.local.action.GoToAction;
import com.voxelmind.local.action.MineBlockAction;
import com.voxelmind.local.action.PlaceBlockAction;
import com.voxelmind.local.body.CompanionConnection;
import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Hands;
import com.voxelmind.local.mind.Sensory;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Placed;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** The placed-blocks index in a real server world: recording through the hand, forgetting, routes, facts and the file. */
public class PlacedGameTests {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final UUID GASADE = UUID.nameUUIDFromBytes("Gasade".getBytes(StandardCharsets.UTF_8));
    private static final UUID LYSA = UUID.nameUUIDFromBytes("Lysa".getBytes(StandardCharsets.UTF_8));

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static CompanionPlayer spawn(GameTestHelper helper, BlockPos rel) {
        return spawn(helper, rel, UUID.randomUUID());
    }

    private static CompanionPlayer spawn(GameTestHelper helper, BlockPos rel, UUID id) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos abs = helper.absolutePos(rel);
        GameProfile profile = new GameProfile(id, "PlacedBody" + COUNTER.incrementAndGet());
        CompanionPlayer body = new CompanionPlayer(server, level, profile);
        body.snapTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0f, 0f);
        server.getPlayerList().placeNewPlayer(new CompanionConnection(), body, CommonListenerCookie.createInitial(profile, false));
        body.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        body.setGameMode(GameType.SURVIVAL);
        return body;
    }

    private static BodyAction run(GameTestHelper helper, CompanionPlayer body, BodyAction action) {
        action.start(new ActionContext(() -> body, UUID.randomUUID()));
        helper.onEachTick(action::tick);
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

    private static Placed index(GameTestHelper helper) {
        Placed index = Placed.of(helper.getLevel());
        helper.assertTrue(index != null, "the running server has a placed-blocks index");
        return index;
    }

    /** Sets a block and records it as placed by {@code name}, the way BlockItemMixin records a hand placement. */
    private static void placedBy(GameTestHelper helper, BlockPos rel, BlockState state, UUID id, String name) {
        helper.setBlock(rel, state);
        index(helper).record(helper.getLevel(), helper.absolutePos(rel), id, name);
    }

    /** A block set by the test is not a placement; an entry left at this spot by an earlier run must not count. */
    private static void natural(GameTestHelper helper, BlockPos rel, BlockState state) {
        helper.setBlock(rel, state);
        index(helper).forget(helper.getLevel(), helper.absolutePos(rel), state);
    }

    @GameTest(maxTicks = 400)
    public void handPlacementIsRecordedAndBreakingForgetsIt(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        body.getInventory().add(new ItemStack(Items.BRICKS, 2));
        body.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        String name = body.getGameProfile().name();
        BlockPos rel = new BlockPos(5, 1, 5);
        BlockPos at = helper.absolutePos(rel);
        BodyAction place = new PlaceBlockAction("place_block(bricks)", "bricks", abs(helper, 5, 1, 5));
        String[] placerAfterPlacing = {null};
        BodyAction mine = new MineBlockAction("mine_block(bricks)", abs(helper, 5, 1, 5));
        runSequence(helper, body, place, mine);
        helper.onEachTick(() -> {
            if (place.finished() && !mine.finished() && placerAfterPlacing[0] == null) placerAfterPlacing[0] = Placed.placer(helper.getLevel(), at);
        });
        finishWhen(helper, body, () -> {
            assertDone(helper, place);
            assertDone(helper, mine);
            helper.assertTrue(name.equals(placerAfterPlacing[0]), "placed bricks recorded under " + placerAfterPlacing[0] + ", expected " + name);
            helper.assertTrue(mine.result().startsWith("broke bricks at " + Names.pos(at) + ", placed by " + name), mine.result());
            helper.assertTrue(index(helper).entry(helper.getLevel().dimension(), at) == null, "the break did not forget the entry");
            // The same block set again without a hand is not a placement.
            helper.setBlock(rel, Blocks.BRICKS);
            helper.assertTrue(Placed.placer(helper.getLevel(), at) == null, "bricks set by the test count as placed");
        });
    }

    @GameTest(maxTicks = 300)
    public void aDoorIsRecordedWithItsUpperHalfAndForgottenAsAWhole(GameTestHelper helper) {
        floor(helper);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        body.getInventory().add(new ItemStack(Items.OAK_DOOR, 1));
        String name = body.getGameProfile().name();
        BlockPos lower = helper.absolutePos(new BlockPos(5, 1, 5));
        BodyAction place = run(helper, body, new PlaceBlockAction("place_block(oak_door)", "oak_door", abs(helper, 5, 1, 5)));
        boolean[] checkedPlaced = {false};
        helper.succeedWhen(() -> {
            assertDone(helper, place);
            ServerLevel level = helper.getLevel();
            if (!checkedPlaced[0]) {
                helper.assertTrue(name.equals(Placed.placer(level, lower)), "lower half placed by " + Placed.placer(level, lower));
                helper.assertTrue(name.equals(Placed.placer(level, lower.above())), "upper half placed by " + Placed.placer(level, lower.above()));
                checkedPlaced[0] = true;
                helper.assertTrue(body.gameMode.destroyBlock(lower), "the body could not break the door");
            }
            helper.assertTrue(level.getBlockState(lower.above()).isAir(), "upper half still there");
            helper.assertTrue(index(helper).entry(level.dimension(), lower) == null, "lower half still recorded");
            helper.assertTrue(index(helper).entry(level.dimension(), lower.above()) == null, "upper half still recorded");
            level.getServer().getPlayerList().remove(body);
        });
    }

    @GameTest
    public void aBedHeadIsRecordedWithItsFoot(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        // By name: 26.2 keeps the beds in a color collection, older versions as RED_BED.
        BlockState foot = Names.block("red_bed").defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT);
        helper.setBlock(new BlockPos(2, 1, 2), foot);
        helper.setBlock(new BlockPos(2, 1, 3), foot.setValue(BedBlock.PART, BedPart.HEAD));
        BlockPos footAt = helper.absolutePos(new BlockPos(2, 1, 2));
        index(helper).record(level, footAt, GASADE, "Gasade");
        helper.assertTrue("Gasade".equals(Placed.placer(level, footAt.south())), "bed head placed by " + Placed.placer(level, footAt.south()));
        index(helper).forget(level, footAt, foot);
        helper.assertTrue(index(helper).entry(level.dimension(), footAt.south()) == null, "bed head still recorded after the foot was broken");
        helper.succeed();
    }

    @GameTest
    public void staleEntriesAreIgnored(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(2, 1, 2);
        BlockPos at = helper.absolutePos(rel);
        placedBy(helper, rel, Blocks.SPRUCE_PLANKS.defaultBlockState(), GASADE, "Gasade");
        helper.assertTrue("Gasade".equals(Placed.placer(level, at)), "placer " + Placed.placer(level, at));
        helper.setBlock(rel, Blocks.GLASS);
        helper.assertTrue(Placed.placer(level, at) == null, "glass set by a command counts as Gasade's planks");
        helper.setBlock(rel, Blocks.SPRUCE_PLANKS);
        helper.assertTrue(Placed.placer(level, at) == null, "the stale entry came back with the same block");
        // Placed dirt that grass grew over is still the placed block.
        BlockPos dirtRel = new BlockPos(4, 1, 4);
        BlockPos dirt = helper.absolutePos(dirtRel);
        placedBy(helper, dirtRel, Blocks.DIRT.defaultBlockState(), GASADE, "Gasade");
        helper.setBlock(dirtRel, Blocks.GRASS_BLOCK);
        helper.assertTrue("Gasade".equals(Placed.placer(level, dirt)), "grass on placed dirt: placer " + Placed.placer(level, dirt));
        helper.setBlock(dirtRel, Blocks.STONE);
        helper.assertTrue(Placed.placer(level, dirt) == null, "stone where dirt was placed counts as placed");
        helper.succeed();
    }

    @GameTest(maxTicks = 400)
    public void walkingNeverDigsAPlacedDirtWall(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 4; y++) for (int z = 0; z < 8; z++) placedBy(helper, new BlockPos(4, y, z), Blocks.DIRT.defaultBlockState(), GASADE, "Gasade");
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 3));
        body.getInventory().add(new ItemStack(Items.STONE_SHOVEL));
        BodyAction action = run(helper, body, new GoToAction("go_to(behind wall)", abs(helper, 6, 1, 3)));
        BlockPos goal = helper.absolutePos(new BlockPos(6, 1, 3));
        finishWhen(helper, body, () -> {
            helper.assertTrue(action.finished(), "action still running: " + action.progress());
            for (int y = 1; y <= 4; y++) for (int z = 0; z < 8; z++) helper.assertBlockPresent(Blocks.DIRT, new BlockPos(4, y, z));
            // Walked around (there is no way around in the closed test box) or reported that it cannot get there.
            if (action.outcome() == BodyAction.Outcome.DONE) {
                helper.assertTrue(body.blockPosition().distSqr(goal) <= 2, "ended at " + body.blockPosition() + "; " + action.result());
            } else {
                helper.assertTrue(action.result().startsWith("could not reach"), action.result());
            }
        });
    }

    @GameTest(maxTicks = 900)
    public void walkingStillDigsANaturalDirtWall(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 4; y++) for (int z = 0; z < 8; z++) natural(helper, new BlockPos(4, y, z), Blocks.DIRT.defaultBlockState());
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 3));
        body.getInventory().add(new ItemStack(Items.STONE_SHOVEL));
        BodyAction action = run(helper, body, new GoToAction("go_to(behind wall)", abs(helper, 6, 1, 3)));
        BlockPos goal = helper.absolutePos(new BlockPos(6, 1, 3));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertTrue(body.blockPosition().distSqr(goal) <= 2, "ended at " + body.blockPosition() + "; " + action.result());
            helper.assertTrue(action.result().contains("mined"), action.result());
        });
    }

    @GameTest(maxTicks = 600)
    public void digReportsThePlacedBlocksItBroke(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 4; y++) for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            if (x == 3 && z == 3) placedBy(helper, new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), GASADE, "Gasade");
            else natural(helper, new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
        }
        CompanionPlayer body = spawn(helper, new BlockPos(3, 5, 3));
        body.getInventory().add(new ItemStack(Items.WOODEN_SHOVEL));
        BodyAction action = run(helper, body, new DigAction("dig(down,3)", "down", 3));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertTrue(action.result().contains("broke 3 blocks"), action.result());
            helper.assertTrue(action.result().contains("; 3 of the broken blocks were placed (Gasade 3): dirt ×3"), action.result());
        });
    }

    @GameTest(maxTicks = 1200)
    public void gatherLeavesPlacedLogsAndTakesNaturalOnes(GameTestHelper helper) {
        floor(helper);
        // A placed cherry log wall right next to the body, a natural cherry log column across the floor. Cherry logs, so
        // logs of neighbouring tests are no candidates.
        for (int z = 1; z <= 3; z++) for (int y = 1; y <= 2; y++) placedBy(helper, new BlockPos(2, y, z), Blocks.CHERRY_LOG.defaultBlockState(), GASADE, "Gasade");
        for (int y = 1; y <= 3; y++) natural(helper, new BlockPos(7, y, 7), Blocks.CHERRY_LOG.defaultBlockState());
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 2));
        BodyAction action = run(helper, body, new com.voxelmind.local.action.GatherAction("gather(cherry_log,2)", "cherry_log", 2));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            for (int z = 1; z <= 3; z++) for (int y = 1; y <= 2; y++) {
                helper.assertBlockPresent(Blocks.CHERRY_LOG, new BlockPos(2, y, z));
            }
            helper.assertTrue(body.getInventory().countItem(Items.CHERRY_LOG) >= 2, "two natural logs gathered: " + action.result());
        });
    }

    @GameTest(maxTicks = 200)
    public void mineBlockByTypeOnlyTakesBlocksNoPlayerPlaced(GameTestHelper helper) {
        floor(helper);
        placedBy(helper, new BlockPos(4, 1, 4), Blocks.MANGROVE_LOG.defaultBlockState(), GASADE, "Gasade");
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        BodyAction action = run(helper, body, new MineBlockAction("mine_block(mangrove_log)", "mangrove_log"));
        finishWhen(helper, body, () -> {
            helper.assertTrue(action.finished() && action.outcome() == BodyAction.Outcome.FAILED, "mine_block by type failed: " + action.result());
            helper.assertTrue(action.result().contains("that no player placed") && action.result().contains("was placed by Gasade")
                    && action.result().contains(abs(helper, 4, 1, 4).replace(' ', ',')), action.result());
            helper.assertBlockPresent(Blocks.MANGROVE_LOG, new BlockPos(4, 1, 4));
        });
    }

    @GameTest(maxTicks = 100, skyAccess = true)
    public void sensoryStatesThePlacedRoofFloorAndDoor(GameTestHelper helper) {
        floor(helper);
        for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) {
            placedBy(helper, new BlockPos(x, 1, z), Blocks.SPRUCE_PLANKS.defaultBlockState(), GASADE, "Gasade");
            placedBy(helper, new BlockPos(x, 5, z), Blocks.SPRUCE_PLANKS.defaultBlockState(), GASADE, "Gasade");
        }
        for (int z = 1; z <= 3; z++) placedBy(helper, new BlockPos(1, 2, z), Blocks.GLASS.defaultBlockState(), LYSA, "Lysa");
        BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST);
        helper.setBlock(new BlockPos(6, 1, 3), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(new BlockPos(6, 2, 3), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        CompanionPlayer body = spawn(helper, new BlockPos(3, 2, 3));
        int roof = helper.absolutePos(new BlockPos(3, 5, 3)).getY();
        finishWhen(helper, body, () -> {
            helper.assertTrue(body.onGround(), "body not on the floor yet");
            String text = Sensory.render(body, UUID.randomUUID(), "Owner", "", "");
            helper.assertTrue(text.contains("Above you: spruce_planks at y=" + roof + " (2 up, placed by Gasade); the highest block in this column is at y=" + roof + ".\n"), text);
            helper.assertTrue(text.contains("Standing on spruce_planks (placed by Gasade);"), text);
            helper.assertTrue(text.contains("Placed blocks within 8: 53 (Gasade 50, Lysa 3): spruce_planks ×50, glass ×3\n"), text);
            helper.assertTrue(text.contains("spruce_door at " + Names.pos(helper.absolutePos(new BlockPos(6, 1, 3))) + " 3m, closed"), text);
            helper.assertFalse(text.contains("spruce_door ×"), "a door is counted once: " + text);
            helper.assertFalse(text.contains("below the surface"), text);
        });
    }

    @GameTest(maxTicks = 100, skyAccess = true)
    public void sensoryNamesTheHighestBlockAboveACave(GameTestHelper helper) {
        floor(helper);
        natural(helper, new BlockPos(3, 3, 3), Blocks.STONE.defaultBlockState());
        placedBy(helper, new BlockPos(3, 6, 3), Blocks.SPRUCE_PLANKS.defaultBlockState(), GASADE, "Gasade");
        CompanionPlayer body = spawn(helper, new BlockPos(3, 1, 3));
        int stone = helper.absolutePos(new BlockPos(3, 3, 3)).getY();
        int top = helper.absolutePos(new BlockPos(3, 6, 3)).getY();
        finishWhen(helper, body, () -> {
            helper.assertTrue(body.onGround(), "body not on the floor yet");
            String text = Sensory.render(body, UUID.randomUUID(), "Owner", "", "");
            helper.assertTrue(text.contains("Above you: stone at y=" + stone + " (1 up); the highest block in this column is spruce_planks at y=" + top
                    + " (placed by Gasade).\n"), text);
        });
    }

    private static Placed.Entry planksOf(Placed index) {
        return index.entry(Level.OVERWORLD, new BlockPos(10, 64, -5));
    }

    private static void remove(GameTestHelper helper, CompanionPlayer body) {
        helper.getLevel().getServer().getPlayerList().remove(body);
    }

    @GameTest(maxTicks = 100)
    public void aPlacedBlockInAnotherFormStaysPlaced(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos logRel = new BlockPos(3, 1, 2);
        BlockPos potRel = new BlockPos(3, 1, 4);
        placedBy(helper, logRel, Blocks.CHERRY_LOG.defaultBlockState(), GASADE, "Gasade");
        placedBy(helper, potRel, Blocks.FLOWER_POT.defaultBlockState(), GASADE, "Gasade");
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 3));
        // A right-click turns them into other blocks where they lie: an axe strips the log, a poppy goes into the pot.
        body.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_AXE));
        Hands.use(body, level, Hands.aim(body, level, helper.absolutePos(logRel)));
        body.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.POPPY));
        Hands.use(body, level, Hands.aim(body, level, helper.absolutePos(potRel)));
        helper.assertBlockPresent(Blocks.STRIPPED_CHERRY_LOG, logRel);
        helper.assertBlockPresent(Blocks.POTTED_POPPY, potRel);
        helper.assertTrue("Gasade".equals(Placed.placer(level, helper.absolutePos(logRel))), "stripped log: placer " + Placed.placer(level, helper.absolutePos(logRel)));
        helper.assertTrue("Gasade".equals(Placed.placer(level, helper.absolutePos(potRel))), "potted poppy: placer " + Placed.placer(level, helper.absolutePos(potRel)));
        // The world changes copper and concrete powder where they lie: weathered, waxed and hardened, still placed.
        BlockPos copperRel = new BlockPos(5, 1, 2);
        BlockPos copper = helper.absolutePos(copperRel);
        placedBy(helper, copperRel, Names.block("copper_block").defaultBlockState(), GASADE, "Gasade");
        helper.setBlock(copperRel, Names.block("exposed_copper"));
        helper.assertTrue("Gasade".equals(Placed.placer(level, copper)), "weathered copper: placer " + Placed.placer(level, copper));
        helper.setBlock(copperRel, Names.block("waxed_exposed_copper"));
        helper.assertTrue("Gasade".equals(Placed.placer(level, copper)), "waxed copper: placer " + Placed.placer(level, copper));
        BlockPos powderRel = new BlockPos(5, 1, 4);
        BlockPos powder = helper.absolutePos(powderRel);
        placedBy(helper, powderRel, Names.block("white_concrete_powder").defaultBlockState(), GASADE, "Gasade");
        helper.setBlock(powderRel, Names.block("white_concrete"));
        helper.assertTrue("Gasade".equals(Placed.placer(level, powder)), "hardened concrete: placer " + Placed.placer(level, powder));
        // Another block in its place is not the placed block.
        helper.setBlock(copperRel, Blocks.GLASS);
        helper.assertTrue(Placed.placer(level, copper) == null, "glass where copper was placed counts as placed");
        remove(helper, body);
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aPistonCarriesAPlacedBlockAndPullsItBack(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos fromRel = new BlockPos(3, 1, 3);
        BlockPos toRel = new BlockPos(4, 1, 3);
        BlockPos from = helper.absolutePos(fromRel);
        BlockPos to = helper.absolutePos(toRel);
        natural(helper, toRel, Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 3), Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        placedBy(helper, fromRel, Blocks.DIRT.defaultBlockState(), GASADE, "Gasade");
        // Power behind the piston: it pushes the placed dirt one block east.
        helper.setBlock(new BlockPos(1, 1, 3), Blocks.REDSTONE_BLOCK);
        boolean[] pulled = {false};
        helper.succeedWhen(() -> {
            if (!pulled[0]) {
                helper.assertBlockPresent(Blocks.DIRT, toRel);
                helper.assertTrue("Gasade".equals(Placed.placer(level, to)), "pushed dirt: placer " + Placed.placer(level, to));
                helper.assertTrue(index(helper).entry(level.dimension(), from) == null, "the entry stayed where the dirt was");
                pulled[0] = true;
                // Without power the sticky piston pulls the dirt back.
                helper.setBlock(new BlockPos(1, 1, 3), Blocks.AIR);
            }
            helper.assertBlockPresent(Blocks.DIRT, fromRel);
            helper.assertTrue("Gasade".equals(Placed.placer(level, from)), "pulled dirt: placer " + Placed.placer(level, from));
            helper.assertTrue(index(helper).entry(level.dimension(), to) == null, "the entry stayed where the dirt was pushed to");
        });
    }

    @GameTest(maxTicks = 100)
    public void fallingSandIsPlacedWhereItLands(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        for (int y = 1; y <= 3; y++) natural(helper, new BlockPos(3, y, 3), Blocks.AIR.defaultBlockState());
        BlockPos high = helper.absolutePos(new BlockPos(3, 4, 3));
        BlockPos low = helper.absolutePos(new BlockPos(3, 1, 3));
        placedBy(helper, new BlockPos(3, 4, 3), Blocks.SAND.defaultBlockState(), GASADE, "Gasade");
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.SAND, new BlockPos(3, 1, 3));
            helper.assertTrue("Gasade".equals(Placed.placer(level, low)), "landed sand: placer " + Placed.placer(level, low));
            helper.assertTrue(index(helper).entry(level.dimension(), high) == null, "the entry stayed where the sand was placed");
        });
    }

    @GameTest(maxTicks = 100)
    public void scaffoldingIsRecordedWhereItGoes(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos baseRel = new BlockPos(3, 1, 3);
        BlockPos base = helper.absolutePos(baseRel);
        natural(helper, baseRel.above(), Blocks.AIR.defaultBlockState());
        placedBy(helper, baseRel, Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0), GASADE, "Gasade");
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 3));
        body.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SCAFFOLDING, 4));
        // A click on the side of a scaffold puts the new one on top of it, as a player extends a scaffolding column.
        body.gameMode.useItemOn(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(base).add(-0.5, 0, 0), Direction.WEST, base, false));
        helper.assertBlockPresent(Blocks.SCAFFOLDING, baseRel.above());
        String name = body.getGameProfile().name();
        helper.assertTrue(name.equals(Placed.placer(level, base.above())), "new scaffold: placer " + Placed.placer(level, base.above()));
        helper.assertTrue("Gasade".equals(Placed.placer(level, base)), "the clicked scaffold kept its placer: " + Placed.placer(level, base));
        remove(helper, body);
        helper.succeed();
    }

    @GameTest(maxTicks = 800)
    public void routesDigTheirOwnPillarStepsBackDown(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        UUID id = UUID.randomUUID();
        // A four-block dirt pillar the body's own route built, the body on top: four blocks are too far to drop.
        for (int y = 1; y <= 4; y++) {
            placedBy(helper, new BlockPos(3, y, 3), Blocks.DIRT.defaultBlockState(), id, "Pillar");
            Placed.markRouteStep(level, helper.absolutePos(new BlockPos(3, y, 3)), id);
        }
        CompanionPlayer body = spawn(helper, new BlockPos(3, 5, 3), id);
        body.getInventory().add(new ItemStack(Items.STONE_SHOVEL));
        BodyAction action = run(helper, body, new GoToAction("go_to(ground)", abs(helper, 6, 1, 3)));
        finishWhen(helper, body, () -> {
            assertDone(helper, action);
            helper.assertTrue(body.blockPosition().getY() == helper.absolutePos(new BlockPos(0, 1, 0)).getY(), "not back on the ground: " + action.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void routesNeverDigBlocksPlacedOnPurpose(GameTestHelper helper) {
        floor(helper);
        UUID id = UUID.randomUUID();
        // The same pillar, placed by the body on purpose (place_block): no route digs it.
        for (int y = 1; y <= 4; y++) placedBy(helper, new BlockPos(3, y, 3), Blocks.DIRT.defaultBlockState(), id, "Pillar");
        CompanionPlayer body = spawn(helper, new BlockPos(3, 5, 3), id);
        body.getInventory().add(new ItemStack(Items.STONE_SHOVEL));
        BodyAction action = run(helper, body, new GoToAction("go_to(ground)", abs(helper, 6, 1, 3)));
        finishWhen(helper, body, () -> {
            helper.assertTrue(action.finished() && action.outcome() == BodyAction.Outcome.FAILED, "go_to: " + action.outcome() + ": " + action.result());
            helper.assertTrue(action.result().contains("routes never dig blocks a player placed, such as the dirt at ")
                    && action.result().contains(" placed by Pillar"), action.result());
            for (int y = 1; y <= 4; y++) helper.assertBlockPresent(Blocks.DIRT, new BlockPos(3, y, 3));
        });
    }

    @GameTest(maxTicks = 300)
    public void miningNeverBreaksAPlacedBlockInTheWay(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 2; y++) placedBy(helper, new BlockPos(3, y, 3), Blocks.GLASS.defaultBlockState(), GASADE, "Gasade");
        natural(helper, new BlockPos(4, 1, 3), Blocks.STONE.defaultBlockState());
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 3));
        body.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        BodyAction action = run(helper, body, new MineBlockAction("mine_block(stone)", abs(helper, 4, 1, 3)));
        finishWhen(helper, body, () -> {
            helper.assertTrue(action.finished() && action.outcome() == BodyAction.Outcome.FAILED, "mine_block: " + action.outcome() + ": " + action.result());
            helper.assertTrue(action.result().startsWith("glass at ") && action.result().contains(", placed by Gasade, is in the way to stone at "), action.result());
            for (int y = 1; y <= 2; y++) helper.assertBlockPresent(Blocks.GLASS, new BlockPos(3, y, 3));
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, 1, 3));
        });
    }

    @GameTest(maxTicks = 1200, skyAccess = true)
    public void gatherTakesAPlantedFieldOnlyWithIncludePlaced(GameTestHelper helper) {
        // A crop counts its produce: gathering wheat counts wheat, not the seeds the wheat block stands for as an item.
        com.voxelmind.local.world.Resources.Resource wheat = com.voxelmind.local.world.Resources.parse("wheat");
        helper.assertTrue(wheat != null && wheat.counts().test(Items.WHEAT) && !wheat.counts().test(Items.WHEAT_SEEDS), "gather wheat counts wheat");
        floor(helper);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(new BlockPos(x, 0, 5), Blocks.FARMLAND);
            placedBy(helper, new BlockPos(x, 1, 5), ((CropBlock) Blocks.CARROTS).getStateForAge(7), GASADE, "Gasade");
        }
        CompanionPlayer body = spawn(helper, new BlockPos(3, 1, 2));
        BodyAction refused = new GatherAction("gather(carrots,2)", "carrots", 2);
        BodyAction taken = new GatherAction("gather(carrots,2,include_placed)", "carrots", 2, true);
        runSequence(helper, body, refused, taken);
        finishWhen(helper, body, () -> {
            helper.assertTrue(refused.finished() && refused.outcome() == BodyAction.Outcome.FAILED, "gather: " + refused.outcome() + ": " + refused.result());
            helper.assertTrue(refused.result().contains("that no player placed; 3 exposed carrots in range were placed by players (Gasade 3), "
                    + "which include_placed true also takes"), refused.result());
            assertDone(helper, taken);
            helper.assertTrue(taken.result().contains(" of the broken blocks were placed (Gasade "), taken.result());
            helper.assertTrue(body.getInventory().countItem(Items.CARROT) >= 2, "carrots gathered: " + taken.result());
        });
    }

    @GameTest(maxTicks = 100)
    public void sensoryCountsPlacedResourcesApart(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 3; y++) placedBy(helper, new BlockPos(1, y, 1), Blocks.ACACIA_LOG.defaultBlockState(), GASADE, "Gasade");
        natural(helper, new BlockPos(6, 1, 6), Blocks.ACACIA_LOG.defaultBlockState());
        CompanionPlayer body = spawn(helper, new BlockPos(4, 1, 4));
        finishWhen(helper, body, () -> {
            String text = Sensory.render(body, UUID.randomUUID(), "Owner", "", "");
            helper.assertTrue(text.contains("  - acacia_log ×1 (closest ") && text.contains("; 3 more placed by players (Gasade 3), closest "), text);
        });
    }

    @GameTest
    public void placedFileRoundTripsAndACorruptFileIsKeptAside(GameTestHelper helper) throws Exception {
        Path dir = Files.createTempDirectory("vml-placed");
        Path file = dir.resolve("placed.dat");
        try {
            Placed index = new Placed(file);
            index.put(Level.OVERWORLD, new BlockPos(10, 64, -5), Blocks.SPRUCE_PLANKS, GASADE, "Gasade", 1_000L);
            index.put(Level.OVERWORLD, new BlockPos(10, -60, 300_000), Blocks.OAK_DOOR, GASADE, "Gasade", 2_000L);
            index.put(Level.NETHER, new BlockPos(-3, 40, 7), Blocks.COBBLESTONE, LYSA, "Lysa", 3_000L);
            index.restore(Level.OVERWORLD, new BlockPos(1, 70, 1), new Placed.Entry(Blocks.DIRT, LYSA, 5_000L, true));
            index.save();
            helper.assertTrue(Files.exists(file) && !Files.exists(dir.resolve("placed.dat.tmp")), "placed.dat written via a temp file");

            Placed loaded = new Placed(file);
            loaded.load();
            helper.assertTrue(loaded.size() == 4, "loaded " + loaded.size() + " entries");
            Placed.Entry step = loaded.entry(Level.OVERWORLD, new BlockPos(1, 70, 1));
            helper.assertTrue(step != null && step.route() && step.block() == Blocks.DIRT, "route step entry " + step);
            helper.assertFalse(planksOf(loaded).route(), "a plain placement is no route step");
            Placed.Entry planks = loaded.entry(Level.OVERWORLD, new BlockPos(10, 64, -5));
            helper.assertTrue(planks != null && planks.block() == Blocks.SPRUCE_PLANKS && planks.placer().equals(GASADE) && planks.at() == 1_000L,
                    "planks entry " + planks);
            Placed.Entry door = loaded.entry(Level.OVERWORLD, new BlockPos(10, -60, 300_000));
            helper.assertTrue(door != null && door.block() == Blocks.OAK_DOOR && door.at() == 2_000L, "door entry " + door);
            Placed.Entry nether = loaded.entry(Level.NETHER, new BlockPos(-3, 40, 7));
            helper.assertTrue(nether != null && nether.block() == Blocks.COBBLESTONE && nether.placer().equals(LYSA) && nether.at() == 3_000L,
                    "nether entry " + nether);
            helper.assertTrue("Gasade".equals(loaded.name(GASADE)) && "Lysa".equals(loaded.name(LYSA)), "placer names");
            helper.assertTrue(loaded.entry(Level.NETHER, new BlockPos(10, 64, -5)) == null, "dimensions stay apart");

            // A file of version 1 (no route flags) still loads.
            java.io.ByteArrayOutputStream v1 = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(v1))) {
                out.writeInt(0x564D4C50);
                out.writeInt(1);
                out.writeInt(1);
                out.writeUTF("minecraft:glass");
                out.writeInt(1);
                out.writeLong(GASADE.getMostSignificantBits());
                out.writeLong(GASADE.getLeastSignificantBits());
                out.writeUTF("Gasade");
                out.writeInt(1);
                out.writeUTF("minecraft:overworld");
                out.writeInt(1);
                out.writeInt(2);
                out.writeInt(65);
                out.writeInt(2);
                out.writeInt(0);
                out.writeInt(0);
                out.writeLong(7_000L);
            }
            Path old = dir.resolve("placed-v1.dat");
            Files.write(old, v1.toByteArray());
            Placed fromV1 = new Placed(old);
            fromV1.load();
            Placed.Entry glass = fromV1.entry(Level.OVERWORLD, new BlockPos(2, 65, 2));
            helper.assertTrue(fromV1.size() == 1 && glass != null && glass.block() == Blocks.GLASS && !glass.route() && glass.at() == 7_000L,
                    "version 1 entry " + glass);

            // A file cut off in the middle, a file that is not ours and a damaged palette count with nothing behind it:
            // kept aside, the index starts empty, and saving works again.
            byte[] whole = Files.readAllBytes(file);
            java.io.ByteArrayOutputStream damaged = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(damaged))) {
                out.writeInt(0x564D4C50);
                out.writeInt(1);
                out.writeInt(100_000_000);
            }
            for (byte[] bad : new byte[][]{java.util.Arrays.copyOf(whole, whole.length / 2), "not a placed file".getBytes(StandardCharsets.UTF_8),
                    damaged.toByteArray()}) {
                Files.write(file, bad);
                Placed broken = new Placed(file);
                broken.load();
                helper.assertTrue(broken.size() == 0, "a corrupt file gave " + broken.size() + " entries");
                helper.assertFalse(Files.exists(file), "the corrupt file was not moved aside");
                broken.put(Level.OVERWORLD, BlockPos.ZERO, Blocks.GLASS, LYSA, "Lysa", 4_000L);
                broken.save();
                Placed again = new Placed(file);
                again.load();
                helper.assertTrue(again.size() == 1, "after a corrupt file the index saves and loads again");
            }
            try (Stream<Path> files = Files.list(dir)) {
                long aside = files.filter(p -> p.getFileName().toString().startsWith("placed.dat.broken-")).count();
                helper.assertTrue(aside >= 1, "no placed.dat.broken-<time> kept");
            }
        } finally {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        }
        helper.succeed();
    }
}
