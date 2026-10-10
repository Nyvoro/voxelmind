package com.voxelmind.local.mind;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.config.LocalConfig;
import com.voxelmind.local.tool.Tools;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides when the companion's model is asked and carries out what it chooses.
 * Events wake it; each wake leads to one decision call whose tools can speak (say)
 * and choose one body tool. Code executes the choice and records the observed result.
 */
public final class Mind {
    private final Companion c;
    private final Set<String> reasons = new LinkedHashSet<>();
    private boolean wanted;
    private long dueAt;
    private int request;
    private boolean inFlight;
    private long nextIdleAt;
    private long waitUntil;
    private long waitStarted;
    private String waitFor = "";
    private String waitCall = "";
    private long lastNoticeAt;
    private final MemoryWriter memory = new MemoryWriter();
    private int invalidStreak;
    private DecisionLog log;

    public Mind(Companion c) {
        this.c = c;
        scheduleIdle();
    }

    public void setLog(DecisionLog log) {
        this.log = log;
    }

    /** Something happened that may deserve a decision. */
    public void wake(String reason, long debounceMs) {
        long now = System.currentTimeMillis();
        if (waitUntil > 0 && !reason.startsWith("waited")) endWait("wait interrupted after " + ((now - waitStarted) / 1000) + "s by " + reason, "done");
        reasons.add(reason);
        wanted = true;
        if (reason.startsWith("chat") && inFlight) {
            // The running decision did not hear this chat: its late response is logged and not executed.
            request++;
            inFlight = false;
        }
        long due = now + debounceMs;
        dueAt = dueAt == 0 ? due : Math.min(dueAt, due);
        scheduleIdle();
    }

    private void endWait(String text, String outcome) {
        c.event("result", waitCall + ": " + text, false, outcome);
        waitUntil = 0;
        waitFor = "";
    }

    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        LocalConfig config = LocalConfig.get();
        if (waitUntil > 0 && now >= waitUntil) {
            long waited = (now - waitStarted) / 1000;
            String forWhat = waitFor;
            endWait("waited " + waited + "s" + (forWhat.isEmpty() ? "" : " for " + forWhat), "done");
            wake("waited " + waited + "s", 0);
        }
        if (config.idleWakeMaxSeconds > 0 && now >= nextIdleAt) {
            scheduleIdle();
            if (ownerNear() && ownerActive(c.owner()) && !inFlight && reasons.isEmpty()) {
                // Not through wake(): a timer leaves a running wait running.
                reasons.add("a quiet moment (timer)");
                wanted = true;
                dueAt = now;
            }
        }
        if (wanted && !inFlight && dueAt > 0 && now >= dueAt) launch(server, now);
        memory.tick(c, server, log);
    }

    private boolean ownerNear() {
        CompanionPlayer body = c.body();
        ServerPlayer owner = c.owner();
        return body != null && owner != null && owner.level() == body.level() && owner.distanceTo(body) <= 48;
    }

    /** Whether the owner acted in the game recently (a technical cost guard for timer wakes, not for real events). */
    public static boolean ownerActive(@org.jspecify.annotations.Nullable ServerPlayer owner) {
        int minutes = LocalConfig.get().idleOwnerAwayMinutes;
        return owner != null && (minutes <= 0 || net.minecraft.util.Util.getMillis() - owner.getLastActionTime() < minutes * 60_000L);
    }

    private void scheduleIdle() {
        LocalConfig config = LocalConfig.get();
        int min = Math.max(10, config.idleWakeMinSeconds);
        int max = Math.max(min + 1, config.idleWakeMaxSeconds);
        nextIdleAt = System.currentTimeMillis() + 1000L * ThreadLocalRandom.current().nextInt(min, max);
    }

    private void launch(MinecraftServer server, long now) {
        if (c.body() == null || c.body().isDeadOrDying()) {
            dueAt = now + 1000;
            return;
        }
        if (!LocalConfig.get().hasCredentials()) {
            if (now - lastNoticeAt > 120_000) {
                lastNoticeAt = now;
                c.notifyOwner(com.voxelmind.local.command.VmlCommand.noAiNotice(c.name()));
            }
            clearWants();
            return;
        }
        if (!LlmClient.tryAcquireBudget()) {
            dueAt = now + 1500;
            return;
        }
        String wokeBy = String.join("; ", reasons);
        clearWants();
        PromptBuilder.Prompt prompt = PromptBuilder.decision(c, PromptBuilder.context(c, wokeBy, waitLine(now)));
        JsonArray tools = Tools.decisionTools(c.actionRunning());
        int id = ++request;
        inFlight = true;
        LocalConfig config = LocalConfig.get();
        LlmClient.chat(config.model, messages(prompt), tools, true, config.temperature, config.maxTokens, null)
                .whenComplete((response, error) -> server.execute(() -> onDecision(id, wokeBy, prompt, response, error)));
    }

    private void clearWants() {
        wanted = false;
        reasons.clear();
        dueAt = 0;
    }

    private JsonArray messages(PromptBuilder.Prompt prompt) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", prompt.system());
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prompt.user());
        messages.add(user);
        return messages;
    }

    private void onDecision(int id, String wokeBy, PromptBuilder.Prompt prompt, LlmClient.Response response, Throwable error) {
        if (id != request) {
            logCall(wokeBy, prompt, response, error, "superseded by a newer wake");
            return;
        }
        inFlight = false;
        if (error != null) {
            onError(error);
            logCall(wokeBy, prompt, null, error, "error");
            return;
        }
        List<LlmClient.ToolCall> calls = response.toolCalls();
        if (calls.isEmpty()) {
            // The endpoint ignored tool_choice=required. Nothing is executed or said; one technical retry follows.
            c.event("note", "your last decision contained no tool call, so nothing was executed or said", false, null);
            if (++invalidStreak <= 1) wake("your last decision contained no tool call", 2000);
            logCall(wokeBy, prompt, response, null, "no tool call");
            return;
        }
        // In the order the calls came: the first line (a say call or a body call's say argument) is sent, the first other
        // call is the body choice of this decision.
        List<String> applied = new ArrayList<>();
        boolean said = false;
        boolean acted = false;
        boolean spoke = false;
        for (LlmClient.ToolCall call : calls) {
            boolean say = call.name().equals("say");
            if (say ? said : acted) {
                c.event("note", "your extra tool call " + Tools.callText(call.name(), call.arguments())
                        + (say ? " was not sent: one say per decision" : " was not executed: one action per decision"), false, null);
                applied.add("extra " + call.name() + " skipped");
                continue;
            }
            if (say) said = true;
            else acted = true;
            Tools.Choice choice = Tools.parse(call.name(), call.arguments());
            spoke |= choice instanceof Tools.Say;
            applied.add(apply(choice, say));
            String line = say ? "" : Tools.sayArgument(call.name(), call.arguments());
            if (line.isEmpty()) continue;
            if (choice instanceof Tools.Invalid) {
                c.event("note", "the say argument of " + call.name() + " was not sent: that call could not be executed", false, null);
                applied.add("say argument not sent");
            } else if (said) {
                c.event("note", "the say argument \"" + line + "\" of " + call.name() + " was not sent: one say per decision", false, null);
                applied.add("say argument not sent");
            } else {
                said = true;
                applied.add("say (" + c.say(line, "say", "") + ")");
            }
        }
        // A decision that only speaks is complete too, so a later technical failure gets its one retry again.
        if (spoke && !acted) invalidStreak = 0;
        logCall(wokeBy, prompt, response, null, String.join(" + ", applied));
    }

    /** Carries out a parsed choice; returns a short description for the passive log. */
    private String apply(Tools.Choice choice, boolean say) {
        switch (choice) {
            case Tools.Body body -> {
                invalidStreak = 0;
                if (c.isRunning(body.action().call())) {
                    // The same call again while it runs: it keeps running instead of restarting.
                    c.event("note", body.action().call() + " is already running; it continues", false, null);
                    return "already running: " + body.action().call();
                }
                replaceWait(body.action().call());
                c.startAction(body.action());
                return "started " + body.action().call();
            }
            case Tools.Stop stop -> {
                invalidStreak = 0;
                replaceWait(stop.call());
                c.stopAction(stop.call());
                return "stop";
            }
            case Tools.Wait wait -> {
                invalidStreak = 0;
                replaceWait(wait.call());
                int seconds = Math.max(1, Math.min(300, wait.seconds()));
                waitStarted = System.currentTimeMillis();
                waitUntil = waitStarted + seconds * 1000L;
                waitFor = wait.forWhat() == null ? "" : wait.forWhat().length() > 60 ? wait.forWhat().substring(0, 60) : wait.forWhat();
                waitCall = wait.call();
                return "wait " + seconds + "s";
            }
            case Tools.Keep keep -> {
                invalidStreak = 0;
                return "keep_current_action";
            }
            case Tools.Say line -> {
                String audience = c.say(line.text(), line.mode(), line.target());
                return "say (" + audience + ")";
            }
            case Tools.Invalid invalid -> {
                c.event("result", invalid.call() + ": cannot execute: " + invalid.reason(), false, "failed");
                if (!say && ++invalidStreak <= 1) wake("your previous tool call could not be executed", 200);
                return "invalid: " + invalid.reason();
            }
        }
    }

    /**
     * A decision can come during a wait (a timer, or a wake that queued behind the decision that chose the wait); a newer
     * body choice ends that wait the way it replaces a running action.
     */
    private void replaceWait(String call) {
        if (waitUntil > 0) endWait("wait replaced after " + ((System.currentTimeMillis() - waitStarted) / 1000) + "s by your newer choice " + call, "preempted");
    }

    /** The running wait for the prompt of a decision that comes during it; empty when none runs. */
    private String waitLine(long now) {
        if (waitUntil == 0) return "";
        return waitCall + " running for " + ((now - waitStarted) / 1000) + "s of " + ((waitUntil - waitStarted) / 1000)
                + "s; any new event, stop, wait or another body action ends it";
    }

    private void onError(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        VoxelMindLocal.LOG.warn("[{}] LLM request failed: {}", c.name(), cause.toString());
        long now = System.currentTimeMillis();
        if (LlmClient.outOfPrepaid(cause)) {
            // Joules recharge over hours; one notice every 10 minutes is enough.
            if (now - lastNoticeAt > 600_000) {
                lastNoticeAt = now;
                c.notifyOwner(com.voxelmind.local.command.VmlCommand.emptyNotice(c.name(), c.state.ownerName));
            }
            return;
        }
        if (now - lastNoticeAt > 120_000) {
            lastNoticeAt = now;
            c.notifyOwner(c.name() + " could not think: " + LlmClient.explain(cause), true);
        }
    }

    private void logCall(String wokeBy, PromptBuilder.Prompt prompt, LlmClient.Response response, Throwable error, String applied) {
        if (log == null) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("lane", "decision");
        entry.addProperty("companion", c.name());
        entry.addProperty("woke_by", wokeBy);
        entry.addProperty("applied", applied);
        entry.addProperty("prompt_chars", prompt.system().length() + prompt.user().length());
        if (LocalConfig.get().logPrompts) entry.addProperty("prompt", prompt.user());
        if (response != null) {
            JsonArray calls = new JsonArray();
            for (LlmClient.ToolCall call : response.toolCalls()) calls.add(call.name() + " " + call.rawArguments());
            entry.add("tool_calls", calls);
            if (!response.content().isBlank()) entry.addProperty("content", response.content());
            entry.addProperty("latency_ms", response.latencyMs());
            entry.addProperty("prompt_tokens", response.promptTokens());
            entry.addProperty("completion_tokens", response.completionTokens());
            entry.addProperty("cost_usd", response.cost());
            entry.addProperty("cached_tokens", response.cachedTokens());
            if (response.provider() != null) entry.addProperty("provider", response.provider());
        }
        if (error != null) entry.addProperty("error", (error.getCause() != null ? error.getCause() : error).toString());
        log.write(entry);
    }
}
