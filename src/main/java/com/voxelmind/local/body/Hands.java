package com.voxelmind.local.body;

import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/** What a player's hands do: break a block in reach, place a carried block, use a block, look at things. */
public final class Hands {
    private Hands() {}

    /** Natural terrain the body may dig through on its way; never player builds or containers. */
    private static final Set<String> EXTRA_NATURAL = Set.of("gravel", "clay", "snow_block", "snow", "netherrack", "end_stone",
            "sandstone", "red_sandstone", "terracotta", "calcite", "dripstone_block", "moss_block", "soul_sand", "soul_soil",
            "basalt", "smooth_basalt", "magma_block", "obsidian", "mud", "packed_mud", "coarse_dirt", "rooted_dirt");

    /** Blocks used to build upward when the route needs a step that is not there. */
    private static final Set<String> SCAFFOLD = Set.of("dirt", "cobblestone", "cobbled_deepslate", "netherrack", "stone", "deepslate",
            "andesite", "diorite", "granite", "tuff", "sand", "gravel", "blackstone", "coarse_dirt");

    public static boolean isNaturalTerrain(BlockState state) {
        if (state.isAir() || state.hasBlockEntity()) return false;
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND) || state.is(BlockTags.LEAVES) || state.is(BlockTags.TERRACOTTA)) return true;
        String id = Names.id(state.getBlock());
        return id.endsWith("_ore") || EXTRA_NATURAL.contains(id) || state.is(Blocks.GRASS_BLOCK);
    }

    public static boolean isScaffold(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem && SCAFFOLD.contains(Names.id(stack.getItem()));
    }

    public static int scaffoldCount(CompanionPlayer body) {
        return Inv.count(body, Hands::isScaffold);
    }

    /** True when breaking this cell would not let water or lava flow in. */
    public static boolean dryToBreak(ServerLevel level, BlockPos pos) {
        for (Direction d : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos n = pos.relative(d);
            if (!level.getFluidState(n).isEmpty()) return false;
        }
        return level.getFluidState(pos).isEmpty();
    }

    /** Estimated ticks to break with the best carried tool; Float.MAX_VALUE when impossible. */
    public static float breakTicks(CompanionPlayer body, ServerLevel level, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return Float.MAX_VALUE;
        if (hardness == 0) return 1;
        float bestSpeed = 1;
        boolean correct = !state.requiresCorrectToolForDrops();
        for (int i = 0; i < Inv.MAIN_SIZE; i++) {
            ItemStack s = body.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            bestSpeed = Math.max(bestSpeed, s.getDestroySpeed(state));
            correct |= s.isCorrectToolForDrops(state);
        }
        return hardness * (correct ? 30f : 100f) / bestSpeed;
    }

    /** Holds the carried item that mines this block fastest, preferring one that makes it drop. */
    public static void holdBestTool(CompanionPlayer body, BlockState state) {
        float best = body.getMainHandItem().getDestroySpeed(state);
        boolean bestCorrect = body.getMainHandItem().isCorrectToolForDrops(state);
        int bestSlot = -1;
        for (int i = 0; i < Inv.MAIN_SIZE; i++) {
            ItemStack s = body.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            float speed = s.getDestroySpeed(state);
            boolean correct = s.isCorrectToolForDrops(state);
            if ((correct && !bestCorrect) || (correct == bestCorrect && speed > best + 0.01f)) {
                best = speed;
                bestCorrect = correct;
                bestSlot = i;
            }
        }
        if (bestSlot >= 0) Inv.holdSlot(body, bestSlot);
    }

    public static void lookAt(CompanionPlayer body, Vec3 point) {
        Vec3 eye = body.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.3) body.motor.targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        body.motor.targetPitch = (float) -(Mth.atan2(dy, horizontal) * (180.0 / Math.PI));
    }

    public static boolean facing(CompanionPlayer body, Vec3 point) {
        Vec3 eye = body.getEyePosition();
        double dx = point.x - eye.x;
        double dz = point.z - eye.z;
        if (dx * dx + dz * dz < 0.36) return true;
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        return Math.abs(Mth.wrapDegrees(yaw - body.getYRot())) < 20;
    }

    /** Breaks one block that is already within reach, tick by tick, like holding the attack key. */
    public static final class Breaker {
        private final CompanionPlayer body;
        private final BlockPos pos;
        private float progress;
        private boolean finished;
        private String failure = "";
        private String brokenName = "";
        private boolean dropped;

        public Breaker(CompanionPlayer body, BlockPos pos) {
            this.body = body;
            this.pos = pos.immutable();
        }

        public BlockPos pos() {
            return pos;
        }

        public boolean failed() {
            return !failure.isEmpty();
        }

        public String failure() {
            return failure;
        }

        public String brokenName() {
            return brokenName;
        }

        /** Whether the broken block was mined with something that makes it drop. */
        public boolean dropped() {
            return dropped;
        }

        /** Returns true once the block is gone or breaking failed. */
        public boolean tick() {
            if (finished) return true;
            ServerLevel level = Compat.level(body);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getCollisionShape(level, pos).isEmpty() && !state.getFluidState().isEmpty()) {
                finished = true;
                return true;
            }
            Vec3 center = Vec3.atCenterOf(pos);
            lookAt(body, center);
            holdBestTool(body, state);
            if (!facing(body, center)) return false;
            float step = state.getDestroyProgress(body, level, pos);
            if (step <= 0) {
                failure = Names.id(state.getBlock()) + " at " + Names.pos(pos) + " cannot be broken by hand or by any tool you carry";
                finished = true;
                return true;
            }
            progress += step;
            if (body.tickCount % 4 == 0) body.swing(InteractionHand.MAIN_HAND);
            level.destroyBlockProgress(body.getId(), pos, Math.min(9, (int) (progress * 10)));
            if (progress < 1.0f) return false;
            level.destroyBlockProgress(body.getId(), pos, -1);
            dropped = body.hasCorrectToolForDrops(state);
            brokenName = Names.id(state.getBlock());
            if (!body.gameMode.destroyBlock(pos)) failure = "the game did not let you break " + brokenName + " at " + Names.pos(pos);
            finished = true;
            return true;
        }
    }

    /** Right-click places one carried {@code item} at {@code target} against a solid neighbour (see {@link #placeClick}). */
    public static boolean place(CompanionPlayer body, ServerLevel level, Item item, BlockPos target) {
        if (!level.getBlockState(target).canBeReplaced()) return false;
        BlockPos support = null;
        Direction face = null;
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
            BlockPos n = target.relative(d);
            BlockState ns = level.getBlockState(n);
            if (!ns.canBeReplaced() && !ns.getCollisionShape(level, n).isEmpty()) {
                support = n;
                face = d.getOpposite();
                break;
            }
        }
        if (support == null) return false;
        if (!Inv.hold(body, s -> s.is(item))) return false;
        BlockState before = level.getBlockState(target);
        Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        lookAt(body, hit);
        placeClick(body, level, new BlockHitResult(hit, face, support, false));
        return level.getBlockState(target) != before;
    }

    /**
     * The right-click that places the held block against the clicked block. A player sneaks for it when the clicked block
     * reacts to a plain click (a door would open, a chest or crafting table its screen, a bed would set the respawn point);
     * so does the body, only then, so a chest placed against the floor still joins a chest beside it.
     */
    public static void placeClick(CompanionPlayer body, ServerLevel level, BlockHitResult hit) {
        boolean sneak = reactsToUse(level, hit.getBlockPos(), level.getBlockState(hit.getBlockPos()));
        boolean sneaking = body.isShiftKeyDown();
        if (sneak) body.setShiftKeyDown(true);
        try {
            body.gameMode.useItemOn(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        } finally {
            if (sneak) body.setShiftKeyDown(sneaking);
        }
        body.swing(InteractionHand.MAIN_HAND);
    }

    /** Whether a plain right-click on this block does something of its own: opens, toggles, plays, eats or teleports it. */
    public static boolean reactsToUse(ServerLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        return level.getBlockEntity(pos) != null || state.getMenuProvider(level, pos) != null
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.CAULDRONS) || state.is(BlockTags.FLOWER_POTS)
                || block instanceof NoteBlock || block instanceof DiodeBlock || block instanceof CakeBlock || block instanceof CandleCakeBlock
                || block instanceof ComposterBlock || block instanceof RespawnAnchorBlock || block instanceof DragonEggBlock;
    }

    /**
     * Right-click on a block in reach with the main hand, the way a player's use key does: the body looks at the hit
     * point and the game's own item-on-block use runs, in which doors, levers and other blocks react before the held
     * item does.
     */
    public static InteractionResult use(CompanionPlayer body, ServerLevel level, BlockHitResult hit) {
        turnTo(body, hit.getLocation());
        InteractionResult result = body.gameMode.useItemOn(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) body.swing(InteractionHand.MAIN_HAND);
        return result;
    }

    /** Where a player looking at {@code pos} from the body's eyes clicks it: the middle of its outline, on the side facing the eyes. */
    public static BlockHitResult aim(CompanionPlayer body, BlockGetter level, BlockPos pos) {
        Vec3 eye = body.getEyePosition();
        Vec3 point = aimPoint(level, pos);
        BlockHitResult hit = level.getBlockState(pos).getShape(level, pos).clip(eye, point, pos);
        if (hit != null) return hit;
        Vec3 d = eye.subtract(point);
        Direction face = Math.abs(d.y) > Math.max(Math.abs(d.x), Math.abs(d.z)) ? (d.y > 0 ? Direction.UP : Direction.DOWN)
                : Math.abs(d.x) > Math.abs(d.z) ? (d.x > 0 ? Direction.EAST : Direction.WEST) : (d.z > 0 ? Direction.SOUTH : Direction.NORTH);
        return new BlockHitResult(point, face, pos, false);
    }

    /** The middle of a block's outline: a door's panel, a lever, a button; the centre for a full block. */
    public static Vec3 aimPoint(BlockGetter level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        return shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Within a player's block interaction range of the body's eyes. */
    public static boolean inReach(CompanionPlayer body, BlockPos pos) {
        double range = body.blockInteractionRange();
        return new AABB(pos).distanceToSqr(body.getEyePosition()) <= range * range;
    }

    /** Turns head and body to {@code point} at once, as a player flicks the mouse before a click. */
    private static void turnTo(CompanionPlayer body, Vec3 point) {
        Vec3 eye = body.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.3) {
            float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            body.setYRot(yaw);
            body.setYHeadRot(yaw);
        }
        body.setXRot((float) -(Mth.atan2(dy, horizontal) * (180.0 / Math.PI)));
    }

    /** The scaffold item the body would build with, or null. */
    public static @Nullable Item scaffoldItem(CompanionPlayer body) {
        int slot = Inv.find(body, Hands::isScaffold);
        return slot < 0 ? null : body.getInventory().getItem(slot).getItem();
    }

    public static boolean isLava(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.LAVA);
    }

    public static Block block(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock();
    }
}
