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
 */
public final class MemoryWriter {
    public static final int BATCH = 24;
    private static final int MAX_PREVIOUSLY = 4500;
    private static final int MAX_KNOWING = 1800;
    private static final int INPUT_CHARS = 14000;

    private boolean inFlight;
    private long retryAt;

    /** Starts a rewrite when enough new events are waiting. */
    public void tick(Companion c, MinecraftServer server, @Nullable DecisionLog log) {
        if (inFlight || System.currentTimeMillis() < retryAt) return;
        if (c.state.timeline.lastSeq() - c.state.memory.cursor < BATCH) return;
        compact(c, server, log);
    }

    private void compact(Companion c, MinecraftServer server, @Nullable DecisionLog log) {
        if (!LocalConfig.get().hasCredentials()) return;
        CompanionState s = c.state;
        List<Timeline.Event> rows = s.timeline.since(s.memory.cursor);
        if (rows.isEmpty()) return;
        String owner = s.ownerName;
        String prefix = PromptTexts.memoryPrefix(s.name, owner, s.memory.previously, s.memory.knowingOwner);
        String suffix = PromptTexts.memorySuffix(s.name, owner, MAX_PREVIOUSLY, MAX_KNOWING);
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
                        s.memory.previously = previously.length() > MAX_PREVIOUSLY ? previously.substring(0, MAX_PREVIOUSLY) : previously;
                        s.memory.knowingOwner = knowing.length() > MAX_KNOWING ? knowing.substring(0, MAX_KNOWING) : knowing;
                        s.memory.cursor = cursorAfter;
                        s.memory.updatedAt = System.currentTimeMillis();
                        c.manager().markDirty();
                        if (log != null) {
                            JsonObject entry = new JsonObject();
                            entry.addProperty("lane", "memory");
                            entry.addProperty("companion", c.name());
                            entry.addProperty("cursor", cursorAfter);
                            entry.addProperty("cost_usd", response.cost());
                            entry.addProperty("latency_ms", response.latencyMs());
                            log.write(entry);
                        }
                    } catch (Exception e) {
                        retryAt = System.currentTimeMillis() + 300_000;
                        VoxelMindLocal.LOG.warn("[{}] memory update returned no valid JSON", c.name());
                    }
                }));
    }
}
