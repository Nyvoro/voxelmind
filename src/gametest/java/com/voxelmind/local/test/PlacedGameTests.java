package com.voxelmind.local.test;

import com.mojang.authlib.GameProfile;
import com.voxelmind.local.action.ActionContext;
import com.voxelmind.local.action.BodyAction;
import com.voxelmind.local.action.DigAction;
import com.voxelmind.local.action.GoToAction;
import com.voxelmind.local.action.MineBlockAction;
import com.voxelmind.local.action.PlaceBlockAction;
import com.voxelmind.local.body.CompanionConnection;
import com.voxelmind.local.body.CompanionPlayer;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

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
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos abs = helper.absolutePos(rel);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "PlacedBody" + COUNTER.incrementAndGet());
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

    @GameTest
    public void placedFileRoundTripsAndACorruptFileIsKeptAside(GameTestHelper helper) throws Exception {
        Path dir = Files.createTempDirectory("vml-placed");
        Path file = dir.resolve("placed.dat");
        try {
            Placed index = new Placed(file);
            index.put(Level.OVERWORLD, new BlockPos(10, 64, -5), Blocks.SPRUCE_PLANKS, GASADE, "Gasade", 1_000L);
            index.put(Level.OVERWORLD, new BlockPos(10, -60, 300_000), Blocks.OAK_DOOR, GASADE, "Gasade", 2_000L);
            index.put(Level.NETHER, new BlockPos(-3, 40, 7), Blocks.COBBLESTONE, LYSA, "Lysa", 3_000L);
            index.save();
            helper.assertTrue(Files.exists(file) && !Files.exists(dir.resolve("placed.dat.tmp")), "placed.dat written via a temp file");

            Placed loaded = new Placed(file);
            loaded.load();
            helper.assertTrue(loaded.size() == 3, "loaded " + loaded.size() + " entries");
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
