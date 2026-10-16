package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.body.PathFinder;
import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.world.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * One chosen body tool being carried out over game ticks. An action ends in an
 * observed result: done, failed, or preempted by a newer choice. The text of the
 * result is a fact for the model, written from what the world actually shows.
 *
 * <p>An action that ends its own walk early (the walk was still under way) while the body's head is under water
 * first finishes that movement up to air, as a player does not stop swimming mid-dive: to the nearest ground with air
 * above within one route search, else to the water surface. Its result then also says where the body came up.
 */
public abstract class BodyAction {
    /** Search budget for the way up to air, as for a walk. */
    private static final int SURFACING_NODES = 6000;
    /** Ticks the way up to air may take before the action reports anyway. */
    private static final int SURFACING_TICKS = 400;

    public enum Outcome {
        DONE("done"), FAILED("failed"), PREEMPTED("preempted");

        public final String id;

        Outcome(String id) {
            this.id = id;
        }
    }

    private final String tool;
    private final String call;
    private @Nullable Outcome outcome;
    private String result = "";
    protected @Nullable ActionContext ctx;
    protected @Nullable Navigator nav;
    protected int ticks;
    /** Set while the body swims up to air after the action itself ended: its outcome and result, reported once it is up. */
    private @Nullable Outcome surfacingOutcome;
    private String surfacingText = "";
    private boolean surfacingToGround;
    private int surfacingTicks;

    protected BodyAction(String tool, String call) {
        this.tool = tool;
        this.call = call;
    }

    /** Tool name, e.g. "gather". */
    public String tool() {
        return tool;
    }

    /** The call as the model made it, e.g. {@code gather(resource="wood", amount=8)}. */
    public String call() {
        return call;
    }

    public boolean finished() {
        return outcome != null;
    }

    public @Nullable Outcome outcome() {
        return outcome;
    }

    public String result() {
        return result;
    }

    public final void start(ActionContext ctx) {
        this.ctx = ctx;
        this.nav = new Navigator(ctx.body());
        onStart(ctx);
    }

    public final void tick() {
        if (finished() || ctx == null) return;
        ticks++;
        if (ctx.body().isDeadOrDying() || ctx.body().isRemoved()) {
            if (surfacingOutcome != null) end(Outcome.FAILED, surfacingText + "; then your body died under water on the way up to air");
            else failed("interrupted: your body died");
            return;
        }
        if (surfacingOutcome != null) {
            surface(ctx);
            return;
        }
        onTick(ctx);
    }

    /** A newer decision replaces this action; reports what had happened so far. */
    public final void preempt(String why) {
        if (finished()) return;
        if (surfacingOutcome != null && ctx != null) {
            end(Outcome.PREEMPTED, surfacingText + "; then, on the way up to air at " + Names.pos(ctx.body().blockPosition()) + ": " + why);
            return;
        }
        cleanup();
        String sofar = progress();
        outcome = Outcome.PREEMPTED;
        result = (sofar.isEmpty() ? "" : sofar + "; ") + why + doorFacts();
    }

    protected final void done(String text) {
        finish(Outcome.DONE, text);
    }

    protected final void failed(String text) {
        finish(Outcome.FAILED, text);
    }

    private void finish(Outcome o, String text) {
        if (finished() || surfacingOutcome != null) return;
        boolean walkCutShort = nav != null && nav.status() == Navigator.Status.MOVING;
        cleanup();
        if (walkCutShort && ctx != null && ctx.body().isUnderWater() && !ctx.body().isDeadOrDying()) {
            if (startSurfacing(ctx)) {
                surfacingOutcome = o;
                surfacingText = text;
                return;
            }
            text += "; your head is under water at " + Names.pos(ctx.body().blockPosition()) + " and no way up to air was found";
        }
        end(o, text);
    }

    private void end(Outcome o, String text) {
        if (nav != null) nav.stop();
        if (ctx != null) ctx.body().motor.clear();
        outcome = o;
        result = text + doorFacts();
    }

    /**
     * Plans the way up to air from where the cut-short walk left the body: the nearest ground with air above it within
     * one route search, else the nearest cell of the water surface. False when neither is reachable.
     */
    private boolean startSurfacing(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        ServerLevel level = Compat.level(body);
        BlockPos feet = nav.feet();
        Predicate<BlockPos> air = p -> !PathFinder.headUnderWater(level, p);
        Predicate<BlockPos> ground = p -> air.test(p) && PathFinder.isFloor(level, p.below());
        PathFinder.Result way = PathFinder.find(level, feet, feet, ground, SURFACING_NODES);
        surfacingToGround = way.reachesGoal();
        if (!surfacingToGround) way = PathFinder.find(level, feet, feet, air, SURFACING_NODES);
        if (!way.reachesGoal()) return false;
        BlockPos up = way.path().getLast();
        nav.allowDig(false);
        surfacingTicks = 0;
        return nav.navigate(up, p -> p.equals(up)) != Navigator.Status.FAILED;
    }

    private void surface(ActionContext ctx) {
        Navigator.Status s = nav.tick();
        if (s == Navigator.Status.MOVING && ++surfacingTicks < SURFACING_TICKS) return;
        CompanionPlayer body = ctx.body();
        String where = Names.pos(body.blockPosition());
        String fact;
        if (body.isUnderWater()) {
            fact = "; on the way up to air your head is still under water at " + where + (s == Navigator.Status.FAILED ? ": " + nav.failure() : "");
        } else if (surfacingToGround) {
            fact = "; then you swam up to air and stand at " + where;
        } else {
            fact = "; then you swam up to the water surface at " + where + "; no ground with air above was within reach";
        }
        end(surfacingOutcome, surfacingText + fact);
    }

    /** The doors and gates the walks of this action opened and left open; read after cleanup ended the last walk. */
    private String doorFacts() {
        return nav == null ? "" : nav.doorFacts();
    }

    private void cleanup() {
        if (nav != null) nav.stop();
        if (ctx != null) {
            CompanionPlayer body = ctx.body();
            body.motor.clear();
            if (body.isUsingItem()) body.stopUsingItem();
            onCleanup(ctx);
        }
    }

    protected abstract void onStart(ActionContext ctx);

    protected abstract void onTick(ActionContext ctx);

    protected void onCleanup(ActionContext ctx) {
    }

    /** What has observably happened so far, for status lines and preemption results. */
    public String progress() {
        return "";
    }

    /** The status line of the running action: its progress, or the way up to air after it ended. */
    public final String status() {
        return surfacingOutcome != null ? "swimming up to air; then it reports: " + surfacingText : progress();
    }

    public int ticksRunning() {
        return ticks;
    }
}
