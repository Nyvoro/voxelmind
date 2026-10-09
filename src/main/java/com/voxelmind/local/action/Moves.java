package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.PathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Small body helpers shared by actions. */
final class Moves {
    private Moves() {}

    /** Turns head and body toward a point over the next ticks. */
    static void lookAt(CompanionPlayer body, Vec3 point) {
        Vec3 eye = body.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.3) body.motor.targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        body.motor.targetPitch = (float) -(Mth.atan2(dy, horizontal) * (180.0 / Math.PI));
    }

    /** True once the body faces the point closely enough for a hand action. */
    static boolean facing(CompanionPlayer body, Vec3 point) {
        Vec3 eye = body.getEyePosition();
        double dx = point.x - eye.x;
        double dz = point.z - eye.z;
        if (dx * dx + dz * dz < 0.36) return true;
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        return Math.abs(Mth.wrapDegrees(yaw - body.getYRot())) < 20;
    }

    /** Facing the point in both yaw and pitch, e.g. before a throw. */
    static boolean aimed(CompanionPlayer body, Vec3 point) {
        if (!facing(body, point)) return false;
        Float pitch = body.motor.targetPitch;
        return pitch == null || Math.abs(Mth.wrapDegrees(pitch - body.getXRot())) < 4;
    }

    static double eyeDistance(BlockPos feet, BlockPos block) {
        double ex = feet.getX() + 0.5;
        double ey = feet.getY() + 1.62;
        double ez = feet.getZ() + 0.5;
        double dx = block.getX() + 0.5 - ex;
        double dy = block.getY() + 0.5 - ey;
        double dz = block.getZ() + 0.5 - ez;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Moves the body to a free standing spot next to {@code target}; false when there is none. */
    static boolean teleportNear(CompanionPlayer body, Entity target) {
        if (!(target.level() instanceof ServerLevel level) || level != body.level()) return false;
        BlockPos base = target.blockPosition();
        int[][] offsets = {{-2, 0}, {2, 0}, {0, -2}, {0, 2}, {-1, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            for (int[] o : offsets) {
                BlockPos spot = base.offset(o[0], dy, o[1]);
                if (PathFinder.standable(level, spot) && !PathFinder.isWater(level, spot)) {
                    body.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
                    body.setDeltaMovement(Vec3.ZERO);
                    body.resetFallDistance();
                    return true;
                }
            }
        }
        return false;
    }
}
