package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;

/** sleep: lie down in the nearest bed and stay until morning or until another action. */
public final class SleepAction extends BodyAction {
    private BlockPos bed;
    private boolean walking;
    private boolean asleep;
    /** "; your respawn point is now …" once lying down moved it; it stays part of what happened. */
    private String spawnNote = "";

    public SleepAction(String call) {
        super("sleep", call);
    }

    @Override
    protected void onStart(ActionContext ctx) {
        bed = Targets.nearestBlock(ctx.level(), ctx.body().blockPosition(), s -> s.is(BlockTags.BEDS), 24);
        if (bed == null) failed("no bed within 24 blocks");
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        if (asleep) {
            if (!body.isSleeping()) done("woke up; slept " + (ticks / 20) + "s in the bed at " + Names.pos(bed) + spawnNote);
            return;
        }
        if (Moves.eyeDistance(nav.feet(), bed) > 2.6) {
            if (!walking) {
                walking = true;
                nav.navigate(bed, f -> Moves.eyeDistance(f, bed) <= 2.4 && !f.equals(bed));
            }
            if (nav.tick() == Navigator.Status.FAILED) failed("could not reach the bed at " + Names.pos(bed) + ": " + nav.failure());
            return;
        }
        nav.stop();
        // The game sets the respawn point at the bed before it checks whether sleeping is possible.
        GlobalPos spawnBefore = Compat.respawnPoint(body);
        var result = body.startSleepInBed(bed);
        String spawn = UseBlockAction.respawnChange(body, spawnBefore);
        if (!spawn.isEmpty()) spawnNote = "; " + spawn;
        if (result.left().isPresent()) {
            Player.BedSleepingProblem problem = result.left().get();
            String why = problem.message() != null ? problem.message().getString() : "the game did not allow it";
            failed("could not sleep in the bed at " + Names.pos(bed) + ": " + why + spawnNote);
            return;
        }
        asleep = true;
    }

    @Override
    protected void onCleanup(ActionContext ctx) {
        if (ctx.body().isSleeping()) ctx.body().stopSleepInBed(true, true);
    }

    @Override
    public String progress() {
        return asleep ? "sleeping in the bed at " + Names.pos(bed) + spawnNote : bed == null ? "" : "walking to the bed at " + Names.pos(bed);
    }
}
