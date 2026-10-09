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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides when the companion's model is asked and carries out what it chooses.
 * Events wake it; the action lane picks one body tool, the speech lane picks
 * say or stay_silent. Code executes the choice and records the observed result.
 */
public final class Mind {
    private final Companion c;
    private final Set<String> reasons = new LinkedHashSet<>();
    private boolean wantAction;
    private boolean wantSpeech;
    private long dueAt;
    private int actionRequest;
    private int speechRequest;
    private boolean actionInFlight;
    private boolean speechInFlight;
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
    public void wake(String reason, boolean action, boolean speech, long debounceMs) {
        long now = System.currentTimeMillis();
        boolean chat = reason.startsWith("chat");
        if (action && waitUntil > 0 && !reason.startsWith("waited")) endWait("wait interrupted after " + ((now - waitStarted) / 1000) + "s by " + reason);
        reasons.add(reason);
        if (action) {
            wantAction = true;
            if (chat && actionInFlight) {
                actionRequest++;
                actionInFlight = false;
            }
        }
        if (speech) wantSpeech = true;
        long due = now + debounceMs;
        dueAt = dueAt == 0 ? due : Math.min(dueAt, due);
        if (chat) dueAt = Math.min(dueAt, due);
        if (action || chat) scheduleIdle();
    }

    private void endWait(String text) {
        c.event("result", waitCall + ": " + text, false, "done");
        waitUntil = 0;
        waitFor = "";
    }

    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        LocalConfig config = LocalConfig.get();
        if (waitUntil > 0 && now >= waitUntil) {
            long waited = (now - waitStarted) / 1000;
            String forWhat = waitFor;
            endWait("waited " + waited + "s" + (forWhat.isEmpty() ? "" : " for " + forWhat));
            wake("waited " + waited + "s", true, false, 0);
        }
        if (config.idleWakeMaxSeconds > 0 && now >= nextIdleAt) {
            scheduleIdle();
            if (ownerNear() && !actionInFlight && !speechInFlight && reasons.isEmpty()) {
                reasons.add("a quiet moment (timer)");
                wantAction = !c.actionRunning() && waitUntil == 0;
                wantSpeech = true;
                dueAt = now;
            }
        }
        if ((wantAction || wantSpeech) && dueAt > 0 && now >= dueAt) launch(server, now);
        memory.tick(c, server, log);
    }

    private boolean ownerNear() {
        CompanionPlayer body = c.body();
        ServerPlayer owner = c.owner();
        return body != null && owner != null && owner.level() == body.level() && owner.distanceTo(body) <= 48;
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
                c.notifyOwner(c.name() + " has no AI model connected yet. Sign in with OpenRouter or add a key:", true);
            }
            clearWants();
            return;
        }
        String wokeBy = String.join("; ", reasons);
        boolean launched = false;
        if (wantAction && !actionInFlight) {
            if (!LlmClient.tryAcquireBudget()) {
                dueAt = now + 1500;
                return;
            }
            startAction(server, wokeBy);
            wantAction = false;
            launched = true;
        }
        if (wantSpeech && !speechInFlight) {
            if (!LlmClient.tryAcquireBudget()) {
                dueAt = now + 1500;
                return;
            }
            startSpeech(server, wokeBy);
            wantSpeech = false;
            launched = true;
        }
        if (launched || (!wantAction && !wantSpeech)) {
            reasons.clear();
            dueAt = (wantAction || wantSpeech) ? now + 500 : 0;
        } else {
            dueAt = now + 300;
        }
    }

    private void clearWants() {
        wantAction = false;
        wantSpeech = false;
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

    private void startAction(MinecraftServer server, String wokeBy) {
        PromptBuilder.Prompt prompt = PromptBuilder.action(c, wokeBy);
        int id = ++actionRequest;
        actionInFlight = true;
        LocalConfig config = LocalConfig.get();
        JsonArray tools = Tools.actionTools(c.actionRunning());
        LlmClient.chat(config.model, messages(prompt), tools, true, config.temperature, config.maxTokens, null)
                .whenComplete((response, error) -> server.execute(() -> onAction(id, wokeBy, prompt, response, error)));
    }

    private void startSpeech(MinecraftServer server, String wokeBy) {
        PromptBuilder.Prompt prompt = PromptBuilder.speech(c, wokeBy);
        int id = ++speechRequest;
        speechInFlight = true;
        LocalConfig config = LocalConfig.get();
        LlmClient.chat(config.model, messages(prompt), Tools.speechTools(), true, config.temperature, config.maxTokens, null)
                .whenComplete((response, error) -> server.execute(() -> onSpeech(id, wokeBy, prompt, response, error)));
    }

    private void onAction(int id, String wokeBy, PromptBuilder.Prompt prompt, LlmClient.Response response, Throwable error) {
        if (id != actionRequest) {
            logCall("action", wokeBy, prompt, response, error, "superseded by a newer wake");
            return;
        }
        actionInFlight = false;
        if (error != null) {
            onError(error);
            logCall("action", wokeBy, prompt, null, error, "error");
            return;
        }
        List<LlmClient.ToolCall> calls = response.toolCalls();
        if (calls.isEmpty()) {
            // The endpoint ignored tool_choice=required. Nothing is executed; one technical retry follows.
            c.event("note", "your last action decision contained no tool call, so nothing was executed", false, null);
            if (++invalidStreak <= 1) wake("your last decision contained no tool call", true, false, 2000);
            logCall("action", wokeBy, prompt, response, null, "no tool call");
            return;
        }
        LlmClient.ToolCall call = calls.getFirst();
        Tools.Choice choice = Tools.parse(call.name(), call.arguments(), false);
        String applied = apply(choice, false);
        for (int i = 1; i < calls.size(); i++) {
            c.event("note", "your extra tool call " + Tools.callText(calls.get(i).name(), calls.get(i).arguments())
                    + " was not executed: one action per decision", false, null);
        }
        logCall("action", wokeBy, prompt, response, null, applied);
    }

    private void onSpeech(int id, String wokeBy, PromptBuilder.Prompt prompt, LlmClient.Response response, Throwable error) {
        if (id != speechRequest) return;
        speechInFlight = false;
        if (error != null) {
            onError(error);
            logCall("speech", wokeBy, prompt, null, error, "error");
            return;
        }
        String applied = "no tool call";
        for (LlmClient.ToolCall call : response.toolCalls()) {
            Tools.Choice choice = Tools.parse(call.name(), call.arguments(), true);
            applied = apply(choice, true);
            break;
        }
        logCall("speech", wokeBy, prompt, response, null, applied);
    }

    /** Carries out a parsed choice; returns a short description for the passive log. */
    private String apply(Tools.Choice choice, boolean speechLane) {
        switch (choice) {
            case Tools.Body body -> {
                invalidStreak = 0;
                if (c.isRunning(body.action().call())) {
                    // The same call again while it runs: it keeps running instead of restarting.
                    c.event("note", body.action().call() + " is already running; it continues", false, null);
                    return "already running: " + body.action().call();
                }
                c.startAction(body.action());
                return "started " + body.action().call();
            }
            case Tools.Stop stop -> {
                invalidStreak = 0;
                c.stopAction(stop.call());
                return "stop";
            }
            case Tools.Wait wait -> {
                invalidStreak = 0;
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
            case Tools.Say say -> {
                String audience = c.say(say.text(), say.mode(), say.target());
                return "say (" + audience + ")";
            }
            case Tools.Silent silent -> {
                return "stay_silent";
            }
            case Tools.Invalid invalid -> {
                c.event("result", invalid.call() + ": cannot execute: " + invalid.reason(), false, "failed");
                if (!speechLane && ++invalidStreak <= 1) wake("your previous tool call could not be executed", true, false, 200);
                return "invalid: " + invalid.reason();
            }
        }
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

    private void logCall(String lane, String wokeBy, PromptBuilder.Prompt prompt, LlmClient.Response response, Throwable error, String applied) {
        if (log == null) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("lane", lane);
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
        }
        if (error != null) entry.addProperty("error", (error.getCause() != null ? error.getCause() : error).toString());
        log.write(entry);
    }
}
