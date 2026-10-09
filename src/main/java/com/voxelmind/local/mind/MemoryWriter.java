package com.voxelmind.local.mind;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.companion.CompanionState;
import com.voxelmind.local.config.LocalConfig;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Long-term memory, shared by every kind of companion: every {@link #BATCH} timeline events the model
 * rewrites its own [PREVIOUSLY] and [ABOUT OWNER] texts with the hosted brain's memory prompt.
 *
 * <p>The texts are stored as the model wrote them. The prompt asks for at most {@link #MAX_PREVIOUSLY} and
 * {@link #MAX_KNOWING} characters; a small overshoot is kept whole. A text far over that is not stored: the
 * model gets the length as a fact and writes the replacement again, so it decides what to leave out. Cutting
 * it in code would drop the newest part, which is written last.
 */
public final class MemoryWriter {
    public static final int BATCH = 24;
    private static final int MAX_PREVIOUSLY = 4500;
    private static final int MAX_KNOWING = 1800;
    /** Technical storage bound: what is kept without asking again. */
    private static final double TOLERANCE = 1.25;
    private static final int INPUT_CHARS = 14000;

    /** Technical cost limit: a burst of events (a God rule firing every tick) cannot start rewrite after rewrite. */
    private static final long MIN_INTERVAL_MS = 60_000;

    private boolean inFlight;
    private long retryAt;
    private long lastRunAt;

    /** Starts a rewrite when enough new events are waiting. */
    public void tick(Companion c, MinecraftServer server, @Nullable DecisionLog log) {
        long now = System.currentTimeMillis();
        if (inFlight || now < retryAt || now - lastRunAt < MIN_INTERVAL_MS) return;
        if (c.state.timeline.lastSeq() - c.state.memory.cursor < BATCH) return;
        compact(c, server, log, null);
    }

    /** {@code tooLong} is the length fact of the previous answer when this is the second attempt. */
    private void compact(Companion c, MinecraftServer server, @Nullable DecisionLog log, @Nullable String tooLong) {
        if (!LocalConfig.get().hasCredentials()) return;
        // Counted with every other model call against maxRequestsPerMinute.
        if (!LlmClient.tryAcquireBudget()) return;
        lastRunAt = System.currentTimeMillis();
        CompanionState s = c.state;
        List<Timeline.Event> rows = s.timeline.since(s.memory.cursor);
        if (rows.isEmpty()) return;
        String owner = s.ownerName;
        String prefix = PromptTexts.memoryPrefix(s.name, owner, s.memory.previously, s.memory.knowingOwner);
        String suffix = PromptTexts.memorySuffix(s.name, owner, MAX_PREVIOUSLY, MAX_KNOWING)
                + (tooLong == null ? "" : "\n" + tooLong);
        StringBuilder transcript = new StringBuilder();
        long lastIncluded = s.memory.cursor;
        int budget = Math.max(2000, INPUT_CHARS - prefix.length() - suffix.length());
        for (Timeline.Event e : rows) {
            String line = e.seq + " | " + java.time.Instant.ofEpochMilli(e.epochMs) + " | " + e.kind
                    + (e.outcome != null ? " (" + e.outcome + ")" : "") + " | " + e.text;
            if (transcript.length() + line.length() + 1 > budget && transcript.length() > 0) break;
            transcript.append(line).append('\n');
            lastIncluded = e.seq;
        }
        long cursorAfter = lastIncluded;
        inFlight = true;
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prefix + transcript + suffix);
        messages.add(user);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        LocalConfig config = LocalConfig.get();
        LlmClient.chat(config.effectiveMemoryModel(), messages, null, false, 0.2, 4096, format)
                .whenComplete((response, error) -> server.execute(() -> {
                    inFlight = false;
                    if (error != null) {
                        retryAt = System.currentTimeMillis() + 300_000;
                        VoxelMindLocal.LOG.warn("[{}] memory update failed: {}", c.name(), error.toString());
                        return;
                    }
                    try {
                        String text = response.content().trim();
                        int start = text.indexOf('{');
                        int end = text.lastIndexOf('}');
                        JsonObject json = JsonParser.parseString(text.substring(start, end + 1)).getAsJsonObject();
                        String previously = json.get("previously").getAsString();
                        String knowing = json.get("knowingOwner").getAsString();
                        boolean fits = previously.length() <= MAX_PREVIOUSLY * TOLERANCE && knowing.length() <= MAX_KNOWING * TOLERANCE;
                        if (!fits && tooLong == null) {
                            // Not stored, cursor unchanged: the same events again, with the length as a fact.
                            log(log, c, cursorAfter, response, previously.length(), knowing.length(), "too_long");
                            compact(c, server, log, "Your last replacement had " + previously.length() + " characters for PREVIOUSLY and "
                                    + knowing.length() + " for KNOWING " + owner + "; at most " + MAX_PREVIOUSLY + " and " + MAX_KNOWING
                                    + " can be stored. Write the complete replacement again within these limits.");
                            return;
                        }
                        s.memory.previously = fits ? previously : newest(previously, (int) (MAX_PREVIOUSLY * TOLERANCE));
                        s.memory.knowingOwner = fits ? knowing : newest(knowing, (int) (MAX_KNOWING * TOLERANCE));
                        s.memory.cursor = cursorAfter;
                        s.memory.updatedAt = System.currentTimeMillis();
                        c.manager().markDirty();
                        log(log, c, cursorAfter, response, previously.length(), knowing.length(), fits ? "stored" : "stored_newest_part");
                    } catch (Exception e) {
                        retryAt = System.currentTimeMillis() + 300_000;
                        VoxelMindLocal.LOG.warn("[{}] memory update returned no valid JSON", c.name());
                    }
                }));
    }

    /**
     * Last resort when even the second answer is far too long: the newest part, which the memory prompt puts
     * last, starting at a line or sentence boundary.
     */
    private static String newest(String text, int max) {
        if (text.length() <= max) return text;
        String tail = text.substring(text.length() - max);
        int cut = tail.indexOf('\n');
        if (cut < 0 || cut > max / 4) cut = tail.indexOf(". ");
        return (cut >= 0 && cut <= max / 4 ? tail.substring(cut + 1) : tail).trim();
    }

    private static void log(@Nullable DecisionLog log, Companion c, long cursor, LlmClient.Response response, int previouslyChars, int knowingChars, String outcome) {
        if (log == null) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("lane", "memory");
        entry.addProperty("companion", c.name());
        entry.addProperty("cursor", cursor);
        entry.addProperty("previously_chars", previouslyChars);
        entry.addProperty("knowing_chars", knowingChars);
        entry.addProperty("outcome", outcome);
        entry.addProperty("cost_usd", response.cost());
        entry.addProperty("latency_ms", response.latencyMs());
        log.write(entry);
    }
}
