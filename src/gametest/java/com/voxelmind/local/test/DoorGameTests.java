package com.voxelmind.local.test;

import com.mojang.authlib.GameProfile;
import com.voxelmind.local.action.ActionContext;
import com.voxelmind.local.action.BodyAction;
import com.voxelmind.local.action.ChestAction;
import com.voxelmind.local.action.GoToAction;
import com.voxelmind.local.action.UseBlockAction;
import com.voxelmind.local.body.CompanionConnection;
import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless checks that the body walks through doors and fence gates like a player, closes them again, and uses blocks. */
public class DoorGameTests {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    /** The door of the test room: the middle of its east wall, opening along x. */
    private static final BlockPos DOOR = new BlockPos(4, 1, 2);

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    /** A closed 5×5 plank room at x 0..4, z 0..4: walls two blocks high, a plank roof, and {@code door} in the east wall. */
    private static void room(GameTestHelper helper, Block door) {
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
            if (wall(x, z)) for (int y = 1; y <= 2; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
            helper.setBlock(new BlockPos(x, 3, z), Blocks.OAK_PLANKS);
        }
        door(helper, DOOR, door);
    }

    private static boolean wall(int x, int z) {
        return (x == 0 || x == 4 || z == 0 || z == 4) && !(x == DOOR.getX() && z == DOOR.getZ());
    }

    /** A closed door at {@code pos}, facing east: walked through along x. */
    private static void door(GameTestHelper helper, BlockPos pos, Block door) {
        BlockState lower = door.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(pos, lower);
        helper.setBlock(pos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void assertRoomIntact(GameTestHelper helper) {
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
            if (wall(x, z)) for (int y = 1; y <= 2; y++) helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(x, y, z));
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(x, 3, z));
        }
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.assertBlockPresent(Blocks.STONE, new BlockPos(x, 0, z));
    }

    /** Things that digging or building up would use: they must still be there afterwards. */
    private static Map<Item, Integer> carry(CompanionPlayer body) {
        body.getInventory().add(new ItemStack(Items.COBBLESTONE, 10));
        body.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        return Inv.snapshot(body);
    }

    /** Remembers whether the door or gate at {@code pos} stood open at any tick of the test. */
    private static boolean[] watchOpen(GameTestHelper helper, BlockPos pos) {
        boolean[] seen = {false};
        helper.onEachTick(() -> {
            if (isOpen(helper, pos)) seen[0] = true;
        });
        return seen;
    }

    private static boolean isOpen(GameTestHelper helper, BlockPos pos) {
        BlockState state = helper.getBlockState(pos);
        return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
    }

    private static CompanionPlayer spawn(GameTestHelper helper, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos abs = helper.absolutePos(rel);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "DoorBody" + COUNTER.incrementAndGet());
        CompanionPlayer body = new CompanionPlayer(server, level, profile);
        body.snapTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0f, 0f);
        server.getPlayerList().placeNewPlayer(new CompanionConnection(), body, CommonListenerCookie.createInitial(profile, false));
        body.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        body.setGameMode(GameType.SURVIVAL);
        return body;
    }

    private static BodyAction run(GameTestHelper helper, CompanionPlayer body, BodyAction action) {
        action.start(new ActionContext(() -> body, UUID.randomUUID()));
        int[] t = {0};
        helper.onEachTick(() -> {
            action.tick();
            if (Boolean.getBoolean("vml.trace") && t[0]++ % 10 == 0) {
                System.out.println("TRACE " + action.call() + " t=" + t[0] + " pos=" + at(helper, body)
                        + " x=" + String.format("%.2f", body.getX()) + " z=" + String.format("%.2f", body.getZ()) + " | " + action.progress()
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

    /** Where the body stands in test coordinates (GameTestHelper.relativePos turns x and z around for unrotated tests). */
    private static BlockPos at(GameTestHelper helper, CompanionPlayer body) {
        return body.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO));
    }

    private static void assertEnded(GameTestHelper helper, BodyAction action, BodyAction.Outcome outcome) {
        helper.assertTrue(action.finished(), "action still running: " + action.progress());
        helper.assertTrue(action.outcome() == outcome, action.call() + " ended " + action.outcome() + ": " + action.result());
    }

    @GameTest(maxTicks = 400)
    public void walksOutOfAClosedRoomThroughItsDoor(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        Map<Item, Integer> carried = carry(body);
        boolean[] opened = watchOpen(helper, DOOR);
        BodyAction action = run(helper, body, new GoToAction("go_to(outside)", abs(helper, 7, 1, 2)));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            BlockPos at = at(helper, body);
            helper.assertTrue(at.getX() >= 5, "ended inside at " + at + "; " + action.result());
            helper.assertTrue(opened[0], "the door was never opened; " + action.result());
            helper.assertFalse(isOpen(helper, DOOR), "the door was left open; " + action.result());
            assertRoomIntact(helper);
            helper.assertTrue(Inv.snapshot(body).equals(carried), "inventory changed to " + Inv.snapshot(body) + "; " + action.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void walksIntoAClosedRoomThroughItsDoor(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(6, 1, 2));
        Map<Item, Integer> carried = carry(body);
        boolean[] opened = watchOpen(helper, DOOR);
        BodyAction action = run(helper, body, new GoToAction("go_to(inside)", abs(helper, 1, 1, 2)));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            BlockPos at = at(helper, body);
            helper.assertTrue(at.getX() >= 1 && at.getX() <= 3 && at.getZ() >= 1 && at.getZ() <= 3, "ended at " + at + "; " + action.result());
            helper.assertTrue(opened[0], "the door was never opened; " + action.result());
            helper.assertFalse(isOpen(helper, DOOR), "the door was left open; " + action.result());
            assertRoomIntact(helper);
            helper.assertTrue(Inv.snapshot(body).equals(carried), "inventory changed to " + Inv.snapshot(body) + "; " + action.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void walksThroughAFenceGate(GameTestHelper helper) {
        floor(helper);
        BlockPos gate = new BlockPos(4, 1, 3);
        for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(4, 1, z), Blocks.OAK_FENCE);
        helper.setBlock(gate, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST));
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 3));
        Map<Item, Integer> carried = carry(body);
        boolean[] opened = watchOpen(helper, gate);
        BodyAction action = run(helper, body, new GoToAction("go_to(behind the fence)", abs(helper, 7, 1, 3)));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            BlockPos at = at(helper, body);
            helper.assertTrue(at.getX() >= 5, "ended at " + at + "; " + action.result());
            helper.assertTrue(opened[0], "the gate was never opened; " + action.result());
            helper.assertFalse(isOpen(helper, gate), "the gate was left open; " + action.result());
            for (int z = 0; z < 8; z++) if (z != gate.getZ()) helper.assertBlockPresent(Blocks.OAK_FENCE, new BlockPos(4, 1, z));
            helper.assertTrue(Inv.snapshot(body).equals(carried), "inventory changed to " + Inv.snapshot(body) + "; " + action.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void staysBehindAClosedIronDoor(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.IRON_DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        Map<Item, Integer> carried = carry(body);
        BodyAction action = run(helper, body, new GoToAction("go_to(outside)", abs(helper, 7, 1, 2)));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.FAILED);
            BlockPos at = at(helper, body);
            helper.assertTrue(at.getX() >= 1 && at.getX() <= 3, "ended at " + at + "; " + action.result());
            helper.assertBlockPresent(Blocks.IRON_DOOR, DOOR);
            helper.assertFalse(isOpen(helper, DOOR), "the iron door is open; " + action.result());
            assertRoomIntact(helper);
            helper.assertTrue(Inv.snapshot(body).equals(carried), "inventory changed to " + Inv.snapshot(body) + "; " + action.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void closesTheDoorWhenStoppingRightBehindIt(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 3));
        boolean[] opened = watchOpen(helper, DOOR);
        BodyAction action = run(helper, body, new GoToAction("go_to(just outside)", abs(helper, 5, 1, 2)));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            helper.assertTrue(at(helper, body).equals(new BlockPos(5, 1, 2)), "ended at " + at(helper, body) + ", not right behind the door; " + action.result());
            helper.assertTrue(opened[0], "the door was never opened; " + action.result());
            helper.assertFalse(isOpen(helper, DOOR), "the door was left open; " + action.result());
        });
    }

    @GameTest(maxTicks = 600)
    public void closesADoorLeftOpenOnTheNextWalk(GameTestHelper helper) {
        floor(helper);
        BlockPos door = new BlockPos(2, 1, 3);
        for (int z = 0; z < 8; z++) for (int y = 1; y <= 2; y++) if (z != door.getZ()) helper.setBlock(new BlockPos(2, y, z), Blocks.OAK_PLANKS);
        door(helper, door, Blocks.OAK_DOOR);
        helper.setBlock(new BlockPos(6, 1, 3), Blocks.CHEST);
        CompanionPlayer body = spawn(helper, new BlockPos(0, 1, 3));
        // use_chest stops as soon as the chest is in reach, which is in the doorway: the door cannot close yet.
        BodyAction view = new ChestAction("use_chest(view)", "view", "", 1, "");
        BodyAction back = new GoToAction("go_to(back)", abs(helper, 0, 1, 6));
        boolean[] openAfterView = {false};
        helper.onEachTick(() -> {
            if (view.finished() && back.ticksRunning() == 0) openAfterView[0] = isOpen(helper, door);
        });
        runSequence(helper, body, view, back);
        finishWhen(helper, body, () -> {
            assertEnded(helper, view, BodyAction.Outcome.DONE);
            assertEnded(helper, back, BodyAction.Outcome.DONE);
            helper.assertTrue(openAfterView[0], "the door was not open while the body stood in it; " + view.result());
            helper.assertFalse(isOpen(helper, door), "the door was left open; " + back.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void leavesAnOpenIronDoorOpen(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.IRON_DOOR);
        helper.setBlock(DOOR, helper.getBlockState(DOOR).setValue(DoorBlock.OPEN, true));
        helper.setBlock(DOOR.above(), helper.getBlockState(DOOR.above()).setValue(DoorBlock.OPEN, true));
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        BodyAction action = run(helper, body, new GoToAction("go_to(outside)", abs(helper, 7, 1, 2)));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            helper.assertTrue(at(helper, body).getX() >= 5, "ended at " + at(helper, body) + "; " + action.result());
            helper.assertTrue(isOpen(helper, DOOR), "the iron door it found open was closed; " + action.result());
            assertRoomIntact(helper);
        });
    }

    @GameTest(maxTicks = 600)
    public void usesADoorAndALever(GameTestHelper helper) {
        floor(helper);
        BlockPos door = new BlockPos(4, 1, 2);
        BlockPos lever = new BlockPos(6, 1, 6);
        door(helper, door, Blocks.OAK_DOOR);
        helper.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        // A block in the hand: doors and levers react before the held item does.
        body.getInventory().add(new ItemStack(Items.COBBLESTONE, 5));
        BodyAction open = new UseBlockAction("use_block(door)", helper.absolutePos(door));
        BodyAction close = new UseBlockAction("use_block(door)", helper.absolutePos(door));
        BodyAction flip = new UseBlockAction("use_block(lever)", helper.absolutePos(lever));
        runSequence(helper, body, open, close, flip);
        String doorAt = Names.pos(helper.absolutePos(door));
        String leverAt = Names.pos(helper.absolutePos(lever));
        finishWhen(helper, body, () -> {
            assertEnded(helper, open, BodyAction.Outcome.DONE);
            assertEnded(helper, close, BodyAction.Outcome.DONE);
            assertEnded(helper, flip, BodyAction.Outcome.DONE);
            helper.assertTrue(open.result().equals("used oak_door at " + doorAt + ": open false -> true"), open.result());
            helper.assertTrue(close.result().equals("used oak_door at " + doorAt + ": open true -> false"), close.result());
            helper.assertTrue(flip.result().equals("used lever at " + leverAt + ": powered false -> true"), flip.result());
            helper.assertFalse(isOpen(helper, door), "the door is open");
            helper.assertTrue(helper.getBlockState(lever).getValue(LeverBlock.POWERED), "the lever is off");
            helper.assertTrue(body.getMainHandItem().is(Items.COBBLESTONE) && body.getMainHandItem().getCount() == 5,
                    "main hand holds " + Names.stack(body.getMainHandItem()));
        });
    }
}
