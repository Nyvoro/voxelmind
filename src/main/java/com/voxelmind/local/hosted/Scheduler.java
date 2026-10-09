package com.voxelmind.local.hosted;

import com.voxelmind.local.compat.Compat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Timed command chains on the server clock: a God sequence or the steps of a Builder edit. Each step
 * runs at the host's live position (or in the edit's fixed dimension) and its real result is kept.
 */
public final class Scheduler {
    public static final int MAX_RUNNING = 6;
    private static final int STEPS_PER_TICK = 8;

    public record Step(int delay, String command) {}

    public record StepResult(int index, String command, boolean ok, String text, boolean noOp) {}

    public static final class Run {
        public final String id;
        public final String label;
        final List<Step> steps;
        final List<StepResult> results = new ArrayList<>();
        final @Nullable ServerLevel fixedLevel;
        final Consumer<Run> onDone;
        int index;
        long nextTick;
        boolean cancelled;

        Run(String id, String label, List<Step> steps, @Nullable ServerLevel fixedLevel, Consumer<Run> onDone) {
            this.id = id;
            this.label = label;
            this.steps = steps;
            this.fixedLevel = fixedLevel;
            this.onDone = onDone;
        }

        public List<StepResult> results() {
            return results;
        }

        public boolean cancelled() {
            return cancelled;
        }

        public int total() {
            return steps.size();
        }
    }

    private final List<Run> runs = new ArrayList<>();
    private final Supplier<@Nullable ServerPlayer> host;
    private final String sourceName;
    private long tick;

    public Scheduler(Supplier<@Nullable ServerPlayer> host, String sourceName) {
        this.host = host;
        this.sourceName = sourceName;
    }

    public int running() {
        return runs.size();
    }

    /** Starts a chain; returns null when too many are running. */
    public @Nullable Run start(String id, String label, List<Step> steps, @Nullable ServerLevel fixedLevel, Consumer<Run> onDone) {
        if (runs.size() >= MAX_RUNNING) return null;
        Run run = new Run(id, label, steps, fixedLevel, onDone);
        run.nextTick = tick + (steps.isEmpty() ? 0 : steps.getFirst().delay());
        runs.add(run);
        return run;
    }

    public void tick(MinecraftServer server) {
        tick++;
        Iterator<Run> it = runs.iterator();
        while (it.hasNext()) {
            Run run = it.next();
            int done = 0;
            while (run.index < run.steps.size() && tick >= run.nextTick && done < STEPS_PER_TICK) {
                Step step = run.steps.get(run.index);
                ServerPlayer h = host.get();
                CommandRunner.Result result;
                if (h == null) {
                    result = new CommandRunner.Result(false, "the host left the world", false, 0);
                } else {
                    ServerLevel level = run.fixedLevel != null ? run.fixedLevel : Compat.level(h);
                    String command = CommandRunner.placeholders(step.command(), h);
                    String refused = CommandPolicy.refuse(command);
                    result = refused != null ? new CommandRunner.Result(false, "refused: " + refused, false, 0)
                            : CommandRunner.runAt(server, level, h.position(), new Vec2(h.getXRot(), h.getYRot()), sourceName, command);
                }
                run.results.add(new StepResult(run.index, step.command(), result.ok(), result.text(), result.noOp()));
                run.index++;
                done++;
                if (run.index < run.steps.size()) run.nextTick = tick + run.steps.get(run.index).delay();
            }
            if (run.index >= run.steps.size()) {
                it.remove();
                run.onDone.accept(run);
            }
        }
    }

    public void cancelAll() {
        for (Run run : List.copyOf(runs)) {
            run.cancelled = true;
            runs.remove(run);
            run.onDone.accept(run);
        }
    }

    /** "label": X/N commands returned success; first failure. */
    public static String summary(Run run) {
        int ok = 0;
        String firstError = null;
        for (StepResult r : run.results) {
            if (r.ok()) ok++;
            else if (firstError == null) firstError = "step " + r.index() + ": " + r.text();
        }
        return "\"" + run.label + "\": " + ok + "/" + run.total() + " commands returned success; " + run.results.size() + " executed"
                + (run.cancelled ? "; cancelled" : "") + (firstError == null ? "" : "; first error: " + truncate(firstError, 300));
    }

    static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** The per-step lines the hosted brain appended to a sequence result. */
    public static String stepLines(Run run) {
        StringBuilder out = new StringBuilder();
        for (StepResult r : run.results) {
            out.append("\n  step ").append(r.index()).append(": /").append(truncate(r.command(), 200)).append(" → ")
                    .append(r.ok() ? "OK" : "FAILED: " + truncate(r.text(), 300));
        }
        return out.toString();
    }

    public static Vec3 vec(double x, double y, double z) {
        return new Vec3(x, y, z);
    }
}
