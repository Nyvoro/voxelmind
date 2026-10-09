package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.world.Inv;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Right-click placement of a carried block against a solid neighbour, as a player's hand does it. */
final class Placing {
    private Placing() {}

    /** Places one {@code item} at {@code target}; returns true when the world shows a new block there. */
    static boolean place(CompanionPlayer body, ServerLevel level, Item item, BlockPos target) {
        return com.voxelmind.local.body.Hands.place(body, level, item, target);
    }

    /** A free cell next to the body, at foot level, with a floor, that is not where the body stands. */
    static @Nullable BlockPos freeSpotNextTo(CompanionPlayer body, ServerLevel level) {
        BlockPos feet = body.blockPosition();
        Direction facing = Direction.fromYRot(body.getYRot());
        Direction[] order = {facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite()};
        for (int dist = 1; dist <= 2; dist++) {
            for (Direction d : order) {
                BlockPos spot = feet.relative(d, dist);
                if (level.getBlockState(spot).canBeReplaced() && level.getBlockState(spot).getFluidState().isEmpty()
                        && !level.getBlockState(spot.below()).getCollisionShape(level, spot.below()).isEmpty()
                        && body.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(spot)) == false) {
                    return spot;
                }
            }
        }
        return null;
    }
}
