package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Hands;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.body.PathFinder;
import com.voxelmind.local.world.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * use_block: walks into reach of one block and right-clicks it with whatever the main hand holds, the way a player's
 * use key does. The result is read from the world: the block's state before and after, the cell in front of the
 * clicked face and the held item. A screen the block opens is closed again at once.
 */
public final class UseBlockAction extends BodyAction {
    private static final int MAX_APPROACHES = 3;

    private final BlockPos target;
    private boolean walking;
    private int approaches;
    private int aimTicks;

    public UseBlockAction(String call, BlockPos target) {
        super("use_block", call);
        this.target = target.immutable();
    }

    @Override
    protected void onStart(ActionContext ctx) {
        if (ctx.level().getBlockState(target).isAir()) failed("there is no block at " + Names.pos(target) + " (air)");
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        ServerLevel level = ctx.level();
        BlockState before = level.getBlockState(target);
        String name = Names.id(before.getBlock());
        if (before.isAir()) {
            failed("the block at " + Names.pos(target) + " is gone (air)");
            return;
        }
        // Once walking, the walk ends where the route ends: never halfway through a doorway, whose door then stays open.
        if (walking) {
            Navigator.Status s = nav.tick();
            if (s == Navigator.Status.FAILED) {
                failed("could not get within reach and sight of " + name + " at " + Names.pos(target) + ": " + nav.failure());
                return;
            }
            if (s == Navigator.Status.MOVING) return;
            walking = false;
        }
        if (!inPosition(level, body, nav.feet(), BlockBreaker.REACH)) {
            if (++approaches > MAX_APPROACHES) {
                failed("could not get a clear look at " + name + " at " + Names.pos(target) + " from within reach");
                return;
            }
            // Walking only: a use never digs its way to the block.
            walking = true;
            nav.allowDig(false);
            nav.navigate(target, f -> !f.equals(target) && !f.above().equals(target) && inPosition(level, body, f, BlockBreaker.REACH - 0.3));
            return;
        }
        nav.stop();
        BlockHitResult hit = Hands.aim(body, level, target);
        Moves.lookAt(body, hit.getLocation());
        if (!Moves.facing(body, hit.getLocation()) && ++aimTicks < 20) return;
        use(ctx, before, hit);
    }

    private void use(ActionContext ctx, BlockState before, BlockHitResult hit) {
        CompanionPlayer body = ctx.body();
        ServerLevel level = ctx.level();
        BlockPos front = hit.getBlockPos().relative(hit.getDirection());
        BlockState frontBefore = level.getBlockState(front);
        ItemStack handBefore = body.getMainHandItem().copy();
        AbstractContainerMenu menuBefore = body.containerMenu;
        boolean sleepingBefore = body.isSleeping();
        InteractionResult result = Hands.use(body, level, hit);
        String used = "used " + Names.id(before.getBlock()) + " at " + Names.pos(target) + ": ";
        if (body.containerMenu != menuBefore) {
            body.closeContainer();
            failed(used + "it opened a screen, which was closed again at once; use_chest, craft and smelt work with chests, "
                    + "crafting tables and furnaces");
            return;
        }
        if (!sleepingBefore && body.isSleeping()) {
            body.stopSleepInBed(true, true);
            failed(used + "you lay down in it and got up again at once; sleep lies down in a bed until morning");
            return;
        }
        List<String> changes = new ArrayList<>();
        String change = change(before, level.getBlockState(target));
        if (!change.isEmpty()) changes.add(change);
        BlockState frontAfter = level.getBlockState(front);
        if (!front.equals(target) && frontAfter.getBlock() != frontBefore.getBlock()) {
            changes.add(Names.pos(front) + " in front of it became " + Names.id(frontAfter.getBlock()));
        }
        ItemStack handAfter = body.getMainHandItem();
        if (!ItemStack.matches(handBefore, handAfter)) changes.add("main hand " + Names.stack(handBefore) + " -> " + Names.stack(handAfter));
        if (!changes.isEmpty()) done(used + String.join("; ", changes));
        else if (result.consumesAction()) done(used + "no state change; the game accepted the use");
        else failed(used + "nothing changed");
    }

    /** "open false -> true" for every property that differs; "it became farmland" when the block itself changed. */
    private static String change(BlockState before, BlockState after) {
        if (before.getBlock() != after.getBlock()) return "it became " + Names.id(after.getBlock());
        List<String> out = new ArrayList<>();
        for (Property<?> property : before.getProperties()) {
            if (before.getValue(property).equals(after.getValue(property))) continue;
            out.add(property.getName() + " " + value(before, property) + " -> " + value(after, property));
        }
        return String.join(", ", out);
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** Within {@code reach} of the block from {@code feet}, outside any doorway, with nothing solid between the eyes and it. */
    private boolean inPosition(ServerLevel level, CompanionPlayer body, BlockPos feet, double reach) {
        if (Moves.eyeDistance(feet, target) > reach || PathFinder.isPassage(level.getBlockState(feet))) return false;
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
        BlockHitResult hit = level.clip(new ClipContext(eye, Hands.aimPoint(level, target), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        return hit.getType() == HitResult.Type.MISS || isTarget(level, hit.getBlockPos());
    }

    /** The block itself, or the other half of a door. */
    private boolean isTarget(ServerLevel level, BlockPos pos) {
        if (pos.equals(target)) return true;
        BlockState state = level.getBlockState(target);
        return state.getBlock() instanceof DoorBlock && level.getBlockState(pos).is(state.getBlock())
                && pos.getX() == target.getX() && pos.getZ() == target.getZ() && Math.abs(pos.getY() - target.getY()) == 1;
    }

    @Override
    public String progress() {
        if (ctx == null) return "";
        return (walking ? "walking to " : "using ") + Names.id(ctx.level().getBlockState(target).getBlock()) + " at " + Names.pos(target);
    }
}
