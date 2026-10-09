package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** place_block: places one carried block at coordinates, in front, or under your feet (jump-placing a pillar step). */
public final class PlaceBlockAction extends BodyAction {
    private final String blockText;
    private final String posText;
    private Item item;
    private Block block;
    private BlockPos target;
    private boolean underFeet;
    private boolean jumped;
    private boolean approaching;

    public PlaceBlockAction(String call, String blockText, String posText) {
        super("place_block", call);
        this.blockText = blockText;
        this.posText = posText == null ? "" : posText.trim();
    }

    @Override
    protected void onStart(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        item = Names.item(blockText);
        if (!(item instanceof BlockItem blockItem)) {
            failed("'" + blockText + "' is not a placeable block item");
            return;
        }
        block = blockItem.getBlock();
        if (Inv.count(body, item) == 0) {
            failed("you carry no " + Names.id(item));
            return;
        }
        String p = Names.normalize(posText);
        if (p.equals("under_feet") || p.equals("below")) {
            underFeet = true;
            target = body.blockPosition();
        } else if (p.equals("in_front") || p.equals("front") || p.isEmpty()) {
            target = body.blockPosition().relative(Direction.fromYRot(body.getYRot()));
        } else {
            target = Names.coords(posText);
            if (target == null) {
                failed("pos must be \"x y z\", \"in_front\" or \"under_feet\"");
            }
        }
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        ServerLevel level = ctx.level();
        if (underFeet) {
            if (!jumped) {
                if (!body.onGround()) {
                    if (ticks > 40) failed("you need to stand on the ground to jump-place under your feet");
                    return;
                }
                if (!level.getBlockState(target.above(2)).getCollisionShape(level, target.above(2)).isEmpty()) {
                    failed("no head room to jump at " + Names.pos(target));
                    return;
                }
                body.motor.jump = true;
                body.motor.targetPitch = 90f;
                jumped = true;
                return;
            }
            body.motor.jump = false;
            if (body.getY() < target.getY() + 1.0) {
                if (ticks > 30) failed("the jump was not high enough to place under your feet");
                return;
            }
            place(ctx);
            return;
        }
        BlockState current = level.getBlockState(target);
        if (!current.canBeReplaced()) {
            failed(Names.pos(target) + " is occupied by " + Names.id(current.getBlock()));
            return;
        }
        if (Moves.eyeDistance(body.blockPosition(), target) > 4.3) {
            if (!approaching) {
                approaching = true;
                nav.navigate(target, f -> Moves.eyeDistance(f, target) <= 3.8 && !f.equals(target) && !f.above().equals(target));
            }
            if (nav.tick() == Navigator.Status.FAILED) failed("could not get within reach of " + Names.pos(target) + ": " + nav.failure());
            return;
        }
        nav.stop();
        Moves.lookAt(body, Vec3.atCenterOf(target));
        if (!Moves.facing(body, Vec3.atCenterOf(target)) && ticks < 40) return;
        place(ctx);
    }

    private void place(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        ServerLevel level = ctx.level();
        Direction face = null;
        BlockPos support = null;
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
            BlockPos n = target.relative(d);
            BlockState ns = level.getBlockState(n);
            if (!ns.canBeReplaced() && !ns.getCollisionShape(level, n).isEmpty()) {
                support = n;
                face = d.getOpposite();
                break;
            }
        }
        if (support == null) {
            failed("nothing to place " + Names.id(item) + " against at " + Names.pos(target) + " (no solid neighbour)");
            return;
        }
        if (!Inv.hold(body, s -> s.is(item))) {
            failed("could not take " + Names.id(item) + " into your hand");
            return;
        }
        int before = Inv.count(body, item);
        Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        body.gameMode.useItemOn(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
        body.swing(InteractionHand.MAIN_HAND);
        BlockState now = level.getBlockState(target);
        int after = Inv.count(body, item);
        if (now.is(block) || after < before) {
            done("placed " + Names.id(now.getBlock()) + " at " + Names.pos(target) + " (" + after + " left)"
                    + (underFeet ? "; you now stand on it at " + Names.pos(body.blockPosition()) : ""));
        } else {
            failed("the game did not place " + Names.id(item) + " at " + Names.pos(target)
                    + " (something may be in the way); the block there is " + Names.id(now.getBlock()));
        }
    }
}
