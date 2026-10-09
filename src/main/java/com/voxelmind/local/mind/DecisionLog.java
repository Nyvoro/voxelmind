package com.voxelmind.local.mind;

import com.google.gson.JsonObject;
import com.voxelmind.local.VoxelMindLocal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Passive record of every model call. Never read back into prompts or decisions. */
public final class DecisionLog {
    private final Path file;

    public DecisionLog(Path dir, String companionName) {
        this.file = dir.resolve(companionName + ".jsonl");
    }

    public synchronized void write(JsonObject entry) {
        entry.addProperty("at", Instant.now().toString());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            VoxelMindLocal.LOG.warn("Could not write decision log {}", file, e);
        }
    }
}
