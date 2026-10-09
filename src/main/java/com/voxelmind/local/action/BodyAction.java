package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import org.jspecify.annotations.Nullable;

/**
 * One chosen body tool being carried out over game ticks. An action ends in an
 * observed result: done, failed, or preempted by a newer choice. The text of the
 * result is a fact for the model, written from what the world actually shows.
 */
public abstract class BodyAction {
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
            failed("interrupted: your body died");
            return;
        }
        onTick(ctx);
    }

    /** A newer decision replaces this action; reports what had happened so far. */
    public final void preempt(String why) {
        if (finished()) return;
        cleanup();
        String sofar = progress();
        outcome = Outcome.PREEMPTED;
        result = (sofar.isEmpty() ? "" : sofar + "; ") + why;
    }

    protected final void done(String text) {
        finish(Outcome.DONE, text);
    }

    protected final void failed(String text) {
        finish(Outcome.FAILED, text);
    }

    private void finish(Outcome o, String text) {
        if (finished()) return;
        cleanup();
        outcome = o;
        result = text;
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

    public int ticksRunning() {
        return ticks;
    }
}
