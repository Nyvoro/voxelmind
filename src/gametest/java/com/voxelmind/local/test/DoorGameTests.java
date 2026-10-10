package com.voxelmind.local.test;

import com.mojang.authlib.GameProfile;
import com.voxelmind.local.action.ActionContext;
import com.voxelmind.local.action.BodyAction;
import com.voxelmind.local.action.ChestAction;
import com.voxelmind.local.action.FollowAction;
import com.voxelmind.local.action.GoToAction;
import com.voxelmind.local.action.UseBlockAction;
import com.voxelmind.local.body.CompanionConnection;
import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.mind.Sensory;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

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
        runSequence(helper, body, () -> {}, actions);
    }

    /**
     * Runs actions one after another from a single tick handler; {@code between} runs when an action that has a successor
     * ends, before the next one starts. (The tick handlers of a test run in no fixed order, so another handler could miss it.)
     */
    private static void runSequence(GameTestHelper helper, CompanionPlayer body, Runnable between, BodyAction... actions) {
        ActionContext ctx = new ActionContext(() -> body, UUID.randomUUID());
        int[] index = {0};
        actions[0].start(ctx);
        helper.onEachTick(() -> {
            BodyAction current = actions[index[0]];
            current.tick();
            if (current.finished() && index[0] + 1 < actions.length) {
                between.run();
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

    /**
     * Starts {@code walk} through DOOR (west to east) and preempts it once the body stands in {@code cell} with its x, from
     * the door's west edge (where the closed door's panel stands), in [from, to); then runs {@code next}. Returns {stopped
     * there, door open right then}.
     */
    private static boolean[] stopAt(GameTestHelper helper, CompanionPlayer body, BlockPos cell, double from, double to, BodyAction walk,
            BodyAction next) {
        ActionContext ctx = new ActionContext(() -> body, UUID.randomUUID());
        double edge = helper.absolutePos(DOOR).getX();
        boolean[] seen = {false, false};
        walk.start(ctx);
        helper.onEachTick(() -> {
            if (!walk.finished()) {
                walk.tick();
                if (!walk.finished() && at(helper, body).equals(cell) && body.getX() - edge >= from && body.getX() - edge < to) {
                    walk.preempt("stopped by the test");
                    seen[0] = true;
                    seen[1] = isOpen(helper, DOOR);
                    next.start(ctx);
                }
            } else if (seen[0]) {
                next.tick();
            }
        });
        return seen;
    }

    @GameTest(maxTicks = 400)
    public void goingToADoorwayEndsInItAndSaysSo(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(7, 1, 2));
        BodyAction action = run(helper, body, new GoToAction("go_to(doorway)", abs(helper, DOOR.getX(), DOOR.getY(), DOOR.getZ())));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            helper.assertTrue(at(helper, body).equals(DOOR), "a doorway target ends in the doorway, not next to it: " + at(helper, body) + "; " + action.result());
            helper.assertTrue(action.result().startsWith("arrived at "), action.result());
            helper.assertTrue(action.result().contains("you stand in its doorway (east side " + Names.pos(helper.absolutePos(DOOR.east())) + ": under ")
                    && action.result().contains("; west side " + Names.pos(helper.absolutePos(DOOR.west())) + ": under oak_planks at y="), action.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void goingToABlockSaysHowNearTheWalkEnded(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.CHEST);
        CompanionPlayer body = spawn(helper, new BlockPos(7, 1, 2));
        BodyAction action = run(helper, body, new GoToAction("go_to(chest)", abs(helper, 2, 1, 2)));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            helper.assertTrue(action.result().startsWith("stopped ") && action.result().contains(" blocks from "),
                    "a walk that ends next to a block does not claim to have arrived at it: " + action.result());
        });
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
        runSequence(helper, body, () -> openAfterView[0] = isOpen(helper, door), view, back);
        finishWhen(helper, body, () -> {
            assertEnded(helper, view, BodyAction.Outcome.DONE);
            assertEnded(helper, back, BodyAction.Outcome.DONE);
            helper.assertTrue(openAfterView[0], "the door was not open while the body stood in it; " + view.result());
            helper.assertFalse(isOpen(helper, door), "the door was left open; " + back.result());
        });
    }

    @GameTest(maxTicks = 800)
    public void forgetsADoorItWasCarriedAwayFrom(GameTestHelper helper) {
        floor(helper);
        BlockPos door = new BlockPos(2, 1, 3);
        for (int z = 0; z < 8; z++) for (int y = 1; y <= 2; y++) if (z != door.getZ()) helper.setBlock(new BlockPos(2, y, z), Blocks.OAK_PLANKS);
        door(helper, door, Blocks.OAK_DOOR);
        helper.setBlock(new BlockPos(6, 1, 3), Blocks.CHEST);
        CompanionPlayer body = spawn(helper, new BlockPos(0, 1, 3));
        // use_chest leaves the door open around the body in the doorway; then the body is carried out of reach (as follow
        // teleports it), walks there, is carried back and walks on two blocks past the door.
        BodyAction view = new ChestAction("use_chest(view)", "view", "", 1, "");
        BodyAction away = new GoToAction("go_to(away)", abs(helper, 7, 1, 5));
        BodyAction past = new GoToAction("go_to(past the door)", abs(helper, 5, 1, 5));
        BlockPos[] stops = {helper.absolutePos(new BlockPos(7, 1, 7)), helper.absolutePos(new BlockPos(4, 1, 3))};
        int[] carried = {0};
        boolean[] openAfterView = {false};
        runSequence(helper, body, () -> {
            if (carried[0] == 0) openAfterView[0] = isOpen(helper, door);
            BlockPos to = stops[carried[0]++];
            body.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            body.setDeltaMovement(Vec3.ZERO);
        }, view, away, past);
        finishWhen(helper, body, () -> {
            assertEnded(helper, view, BodyAction.Outcome.DONE);
            assertEnded(helper, away, BodyAction.Outcome.DONE);
            assertEnded(helper, past, BodyAction.Outcome.DONE);
            helper.assertTrue(openAfterView[0], "the door was not open while the body stood in it; " + view.result());
            helper.assertTrue(isOpen(helper, door), "the door was closed on a later walk after the body had been out of reach; " + past.result());
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

    @GameTest(maxTicks = 500)
    public void leavesTheDoorOpenWhileStandingInIt(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        BodyAction walk = new GoToAction("go_to(outside)", abs(helper, 7, 1, 2));
        BodyAction on = new GoToAction("go_to(outside, again)", abs(helper, 7, 1, 2));
        // Stopped in the doorway, past the middle: the closed panel would not even touch the body.
        boolean[] seen = stopAt(helper, body, DOOR, 0.5, 1.0, walk, on);
        finishWhen(helper, body, () -> {
            helper.assertTrue(seen[0], "the walk was never stopped in the doorway; " + walk.result());
            helper.assertTrue(seen[1], "the door was closed while the body still stood in the doorway");
            assertEnded(helper, on, BodyAction.Outcome.DONE);
            helper.assertTrue(at(helper, body).getX() >= 5, "ended at " + at(helper, body) + "; " + on.result());
            helper.assertFalse(isOpen(helper, DOOR), "the door was left open; " + on.result());
        });
    }

    @GameTest(maxTicks = 500)
    public void closesTheDoorWhenStoppedJustPastIt(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        BodyAction walk = new GoToAction("go_to(outside)", abs(helper, 7, 1, 2));
        BodyAction on = new GoToAction("go_to(outside, again)", abs(helper, 7, 1, 2));
        // Stopped with its middle just out of the doorway: its back still reaches into the door's cell, but not to the
        // west edge where the closed panel stands.
        boolean[] seen = stopAt(helper, body, DOOR.east(), 1.0, 1.25, walk, on);
        finishWhen(helper, body, () -> {
            helper.assertTrue(seen[0], "the walk was never stopped just past the door; " + walk.result());
            helper.assertFalse(seen[1], "the door stayed open after the body had stepped out of the doorway");
            assertEnded(helper, on, BodyAction.Outcome.DONE);
            helper.assertFalse(isOpen(helper, DOOR), "the door was left open; " + on.result());
        });
    }

    @GameTest(maxTicks = 500)
    public void useBlockClosesADoorLeftOpen(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        BodyAction walk = new GoToAction("go_to(outside)", abs(helper, 7, 1, 2));
        BodyAction close = new UseBlockAction("use_block(door)", helper.absolutePos(DOOR));
        boolean[] seen = stopAt(helper, body, DOOR, 0.5, 1.0, walk, close);
        String doorAt = Names.pos(helper.absolutePos(DOOR));
        finishWhen(helper, body, () -> {
            helper.assertTrue(seen[0] && seen[1], "the walk did not stop in the open doorway; " + walk.result());
            assertEnded(helper, close, BodyAction.Outcome.DONE);
            helper.assertTrue(close.result().equals("used oak_door at " + doorAt + ": open true -> false"), close.result());
            helper.assertFalse(isOpen(helper, DOOR), "the door is open; " + close.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void walksThroughADoubleDoor(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        BlockPos pair = DOOR.north();
        door(helper, pair, Blocks.OAK_DOOR);
        helper.setBlock(pair, helper.getBlockState(pair).setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT));
        helper.setBlock(pair.above(), helper.getBlockState(pair.above()).setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT));
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        Map<Item, Integer> carried = carry(body);
        boolean[] openedPair = watchOpen(helper, pair);
        boolean[] openedDoor = watchOpen(helper, DOOR);
        BodyAction action = run(helper, body, new GoToAction("go_to(outside)", abs(helper, 7, 1, 1)));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            helper.assertTrue(at(helper, body).getX() >= 5, "ended at " + at(helper, body) + "; " + action.result());
            helper.assertTrue(openedPair[0] || openedDoor[0], "neither door was opened; " + action.result());
            helper.assertFalse(isOpen(helper, pair) || isOpen(helper, DOOR), "a door was left open; " + action.result());
            helper.assertBlockPresent(Blocks.OAK_DOOR, pair);
            helper.assertBlockPresent(Blocks.OAK_DOOR, DOOR);
            for (int y = 1; y <= 2; y++) helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(4, y, 3));
            for (int y = 1; y <= 2; y++) helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(4, y, 0));
            helper.assertTrue(Inv.snapshot(body).equals(carried), "inventory changed to " + Inv.snapshot(body) + "; " + action.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void walksInThroughTheDoorFromTheSide(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        // From the north-east the body reaches the cell in front of the door on a diagonal, offset toward the side where
        // the open door's panel lies, and turns into the doorway.
        CompanionPlayer body = spawn(helper, new BlockPos(7, 1, 0));
        Map<Item, Integer> carried = carry(body);
        boolean[] opened = watchOpen(helper, DOOR);
        BodyAction action = run(helper, body, new GoToAction("go_to(inside)", abs(helper, 1, 1, 3)));
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

    @GameTest(maxTicks = 600)
    public void stepsUpIntoADoorAndBackDown(GameTestHelper helper) {
        floor(helper);
        // The room stands on a one-block foundation: its door is one step above the ground outside.
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            if (wall(x, z)) for (int y = 2; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
            helper.setBlock(new BlockPos(x, 4, z), Blocks.OAK_PLANKS);
        }
        BlockPos door = DOOR.above();
        door(helper, door, Blocks.OAK_DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(6, 1, 2));
        Map<Item, Integer> carried = carry(body);
        boolean[] opened = watchOpen(helper, door);
        BodyAction in = new GoToAction("go_to(inside)", abs(helper, 2, 2, 2));
        BodyAction out = new GoToAction("go_to(outside)", abs(helper, 6, 1, 2));
        boolean[] closedBetween = {false};
        runSequence(helper, body, () -> closedBetween[0] = !isOpen(helper, door), in, out);
        finishWhen(helper, body, () -> {
            assertEnded(helper, in, BodyAction.Outcome.DONE);
            assertEnded(helper, out, BodyAction.Outcome.DONE);
            helper.assertTrue(opened[0], "the door was never opened; " + in.result());
            helper.assertTrue(closedBetween[0], "the door stood open after walking in; " + in.result());
            helper.assertTrue(at(helper, body).getX() >= 5 && at(helper, body).getY() == 1, "ended at " + at(helper, body) + "; " + out.result());
            helper.assertFalse(isOpen(helper, door), "the door was left open; " + out.result());
            for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
                helper.assertBlockPresent(Blocks.STONE, new BlockPos(x, 1, z));
                if (wall(x, z)) for (int y = 2; y <= 3; y++) helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(x, y, z));
                helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(x, 4, z));
            }
            helper.assertTrue(Inv.snapshot(body).equals(carried), "inventory changed to " + Inv.snapshot(body) + "; " + out.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void walksOutOfAClosedDoorwayItStandsIn(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        // The body starts in the doorway with the door shut: the panel stands between it and the room.
        CompanionPlayer body = spawn(helper, DOOR);
        boolean[] opened = watchOpen(helper, DOOR);
        BodyAction action = run(helper, body, new GoToAction("go_to(inside)", abs(helper, 1, 1, 2)));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            helper.assertTrue(at(helper, body).getX() <= 3, "ended at " + at(helper, body) + "; " + action.result());
            helper.assertTrue(opened[0], "the door was never opened; " + action.result());
            helper.assertFalse(isOpen(helper, DOOR), "the door was left open; " + action.result());
            assertRoomIntact(helper);
        });
    }

    @GameTest(maxTicks = 400)
    public void leavesTheDoorOpenWhileSomeoneStandsInIt(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        helper.spawn(EntityTypes.ARMOR_STAND, DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        BodyAction action = run(helper, body, new GoToAction("go_to(outside)", abs(helper, 7, 1, 2)));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            helper.assertTrue(at(helper, body).getX() >= 5, "ended at " + at(helper, body) + "; " + action.result());
            helper.assertTrue(isOpen(helper, DOOR), "the door was closed on the armor stand in the doorway; " + action.result());
            // The result names the door it left open, and why.
            helper.assertTrue(action.result().contains("; oak_door at " + Names.pos(helper.absolutePos(DOOR)) + " stays open: armor_stand stands in it"),
                    action.result());
        });
    }

    @GameTest(maxTicks = 400)
    public void goingToSomeoneInsideClosesTheDoorBehind(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        // Someone two blocks past the door's middle: close enough already while the body stands in the doorway.
        Entity stand = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(2, 1, 2));
        CompanionPlayer body = spawn(helper, new BlockPos(7, 1, 2));
        boolean[] opened = watchOpen(helper, DOOR);
        BodyAction action = run(helper, body, new GoToAction("go_to(stand)", "#" + stand.getId()));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            helper.assertTrue(opened[0], "the door was never opened; " + action.result());
            helper.assertTrue(at(helper, body).getX() <= 3, "ended at " + at(helper, body) + ", not inside; " + action.result());
            helper.assertFalse(isOpen(helper, DOOR), "the door was left open; " + action.result());
            assertRoomIntact(helper);
        });
    }

    @GameTest(maxTicks = 100, skyAccess = true)
    public void sensoryNamesWhatIsAboveEachSideOfADoor(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(7, 1, 2));
        String east = Names.pos(helper.absolutePos(DOOR.east()));
        String west = Names.pos(helper.absolutePos(DOOR.west()));
        int roof = helper.absolutePos(new BlockPos(3, 3, 2)).getY();
        finishWhen(helper, body, () -> {
            String text = Sensory.render(body, UUID.randomUUID(), "Owner", "", "");
            helper.assertTrue(text.contains(", closed (east side " + east + ": under open sky; west side " + west + ": under oak_planks at y=" + roof + ")"), text);
        });
    }

    @GameTest(maxTicks = 600)
    public void followingSomeoneInsideGoesThroughTheDoorAndClosesIt(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        Entity stand = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(1, 1, 2));
        CompanionPlayer body = spawn(helper, new BlockPos(7, 1, 2));
        boolean[] opened = watchOpen(helper, DOOR);
        BodyAction action = run(helper, body, new FollowAction("follow(stand)", "#" + stand.getId()));
        finishWhen(helper, body, () -> {
            helper.assertTrue(opened[0], "the door was never opened; " + action.progress());
            helper.assertTrue(at(helper, body).getX() <= 3, "still not inside at " + at(helper, body) + "; " + action.progress());
            helper.assertFalse(isOpen(helper, DOOR), "the door was left open behind the body; " + action.progress());
            assertRoomIntact(helper);
        });
    }

    @GameTest(maxTicks = 400)
    public void walksThroughAnIronDoorWithAButton(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.IRON_DOOR);
        // A stone button inside on the wall block beside the door: it powers the wall block, which opens the door.
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        Map<Item, Integer> carried = carry(body);
        boolean[] opened = watchOpen(helper, DOOR);
        BodyAction action = run(helper, body, new GoToAction("go_to(outside)", abs(helper, 7, 1, 2)));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.DONE);
            helper.assertTrue(at(helper, body).getX() >= 5, "ended at " + at(helper, body) + "; " + action.result());
            helper.assertTrue(opened[0], "the iron door was never opened; " + action.result());
            // The button springs back and the iron door closes by itself.
            helper.assertFalse(isOpen(helper, DOOR), "the iron door is still open; " + action.result());
            assertRoomIntact(helper);
            helper.assertTrue(Inv.snapshot(body).equals(carried), "inventory changed to " + Inv.snapshot(body) + "; " + action.result());
        });
    }

    @GameTest(maxTicks = 500)
    public void leavingTheWorldClosesTheDoorItStandsIn(GameTestHelper helper) {
        floor(helper);
        room(helper, Blocks.OAK_DOOR);
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        BodyAction walk = new GoToAction("go_to(outside)", abs(helper, 7, 1, 2));
        // Dismissed, its owner gone or the world closing: the companion leaves while it stands in the doorway.
        BodyAction leave = new BodyAction("leave", "leave()") {
            @Override
            protected void onStart(ActionContext ctx) {
                Navigator.leave(ctx.body());
                done("left");
            }

            @Override
            protected void onTick(ActionContext ctx) {
            }
        };
        boolean[] seen = stopAt(helper, body, DOOR, 0.5, 1.0, walk, leave);
        finishWhen(helper, body, () -> {
            helper.assertTrue(seen[0] && seen[1], "the walk did not stop in the open doorway; " + walk.result());
            assertEnded(helper, leave, BodyAction.Outcome.DONE);
            helper.assertFalse(isOpen(helper, DOOR), "the door the body stood in stayed open when it left");
        });
    }

    @GameTest(maxTicks = 300)
    public void useBlockOnABedNamesTheNewRespawnPoint(GameTestHelper helper) {
        floor(helper);
        BlockState foot = Names.block("red_bed").defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT);
        helper.setBlock(new BlockPos(4, 1, 2), foot);
        helper.setBlock(new BlockPos(4, 1, 3), foot.setValue(BedBlock.PART, BedPart.HEAD));
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        BodyAction action = run(helper, body, new UseBlockAction("use_block(bed)", helper.absolutePos(new BlockPos(4, 1, 2))));
        finishWhen(helper, body, () -> {
            helper.assertTrue(action.finished(), "action still running: " + action.progress());
            // By day nothing else changes; by night the body lies down and gets up again: either way the point moved.
            helper.assertTrue(action.result().contains("your respawn point is now (") && action.result().contains(" in overworld (was the world spawn)"),
                    action.result());
        });
    }

    @GameTest(maxTicks = 300)
    public void useBlockClosesTheScreenItOpens(GameTestHelper helper) {
        floor(helper);
        BlockPos table = new BlockPos(4, 1, 2);
        helper.setBlock(table, Blocks.CRAFTING_TABLE);
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        BodyAction action = run(helper, body, new UseBlockAction("use_block(table)", helper.absolutePos(table)));
        String at = Names.pos(helper.absolutePos(table));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.FAILED);
            helper.assertTrue(action.result().startsWith("used crafting_table at " + at + ": it opened a screen, which was closed again at once"),
                    action.result());
            helper.assertTrue(body.containerMenu == body.inventoryMenu, "a menu is still open: " + body.containerMenu);
        });
    }

    @GameTest(maxTicks = 300)
    public void useBlockClosesTheSignEditor(GameTestHelper helper) {
        floor(helper);
        BlockPos sign = new BlockPos(4, 1, 2);
        helper.setBlock(sign, Blocks.OAK_SIGN);
        CompanionPlayer body = spawn(helper, new BlockPos(2, 1, 2));
        BodyAction action = run(helper, body, new UseBlockAction("use_block(sign)", helper.absolutePos(sign)));
        String at = Names.pos(helper.absolutePos(sign));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.FAILED);
            helper.assertTrue(action.result().startsWith("used oak_sign at " + at + ": it opened the sign's text editor, which was closed again at once"),
                    action.result());
            // While the sign is reserved for the body, nobody else can edit it.
            SignBlockEntity entity = (SignBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(sign));
            helper.assertTrue(entity != null && entity.getPlayerWhoMayEdit() == null, "the sign is still reserved for the body");
        });
    }

    @GameTest(maxTicks = 400)
    public void useBlockNeverDigsToABlock(GameTestHelper helper) {
        floor(helper);
        BlockPos note = new BlockPos(5, 1, 5);
        for (int x = 4; x <= 6; x++) for (int y = 1; y <= 2; y++) for (int z = 4; z <= 6; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        helper.setBlock(note, Blocks.NOTE_BLOCK);
        CompanionPlayer body = spawn(helper, new BlockPos(1, 1, 1));
        Map<Item, Integer> carried = carry(body);
        BodyAction action = run(helper, body, new UseBlockAction("use_block(note)", helper.absolutePos(note)));
        String at = Names.pos(helper.absolutePos(note));
        finishWhen(helper, body, () -> {
            assertEnded(helper, action, BodyAction.Outcome.FAILED);
            helper.assertTrue(action.result().startsWith("could not get within reach and sight of note_block at " + at), action.result());
            helper.assertTrue(helper.getBlockState(note).getValue(NoteBlock.NOTE) == 0, "the note block was played");
            for (int x = 4; x <= 6; x++) for (int y = 1; y <= 2; y++) for (int z = 4; z <= 6; z++) {
                if (x != note.getX() || y != note.getY() || z != note.getZ()) helper.assertBlockPresent(Blocks.STONE, new BlockPos(x, y, z));
            }
            helper.assertTrue(Inv.snapshot(body).equals(carried), "inventory changed to " + Inv.snapshot(body) + "; " + action.result());
        });
    }
}
