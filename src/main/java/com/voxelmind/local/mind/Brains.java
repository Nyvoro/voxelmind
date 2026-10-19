package com.voxelmind.local.mind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.voxelmind.local.VoxelMindLocal;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The brains this game knows: the ones shipped in the mod and the ones in {@code .minecraft/voxelmind-local/brains/<id>/}.
 * Loaded at start and with /vml brain reload; a broken brain is skipped with its problem, it never stops the others.
 */
public final class Brains {
    /** Shipped in the mod (assets/voxelmind_local/brains/<id>/), in menu order. */
    public static final List<String> BUILTIN = List.of(Brain.DEFAULT, "lite");
    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    static final int MAX_FILE_CHARS = 16_000;
    static final String[] TEXTS = {"frame.md", "conversation.md", "decision.md", "identity.md", "memory.md"};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile Map<String, Brain> all = Map.of();
    private static volatile List<String> problems = List.of();

    private Brains() {}

    public static Path folder() {
        return FabricLoader.getInstance().getGameDir().resolve("voxelmind-local").resolve("brains");
    }

    /** Every known brain, shipped ones first. */
    public static List<Brain> all() {
        if (all.isEmpty()) reload();
        return List.copyOf(all.values());
    }

    /** What the last load skipped and why. */
    public static List<String> problems() {
        return problems;
    }

    /** The brain with this id, or the default when it is unknown (a brain folder that was removed, a typo in a save). */
    public static Brain get(@Nullable String id) {
        if (all.isEmpty()) reload();
        Brain brain = id == null ? null : all.get(id);
        return brain != null ? brain : all.get(Brain.DEFAULT);
    }

    public static boolean exists(String id) {
        if (all.isEmpty()) reload();
        return all.containsKey(id);
    }

    /** Loads the shipped brains and the brains folder again; returns the problems found. */
    public static synchronized List<String> reload() {
        Map<String, Brain> loaded = new LinkedHashMap<>();
        List<String> found = new ArrayList<>();
        for (String id : BUILTIN) {
            try {
                loaded.put(id, builtin(id));
            } catch (Exception e) {
                found.add(id + ": " + e.getMessage());
                VoxelMindLocal.LOG.error("Shipped brain {} could not be read", id, e);
            }
        }
        Path dir = folder();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> entries = Files.list(dir)) {
                for (Path p : entries.sorted().toList()) {
                    if (!Files.isDirectory(p)) continue;
                    String id = p.getFileName().toString();
                    if (id.startsWith("_") || id.startsWith(".")) continue;
                    if (!ID.matcher(id).matches()) {
                        found.add(id + ": a brain folder name is 1-32 lowercase letters, digits, - or _");
                        continue;
                    }
                    if (loaded.containsKey(id) && BUILTIN.contains(id)) {
                        found.add(id + ": this name belongs to a brain shipped with the mod; rename the folder");
                        continue;
                    }
                    try {
                        loaded.put(id, local(id, p));
                    } catch (Exception e) {
                        found.add(id + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                found.add("brains folder: " + e.getMessage());
            }
        }
        if (!loaded.containsKey(Brain.DEFAULT)) throw new IllegalStateException("the default brain is missing from the mod");
        all = Collections.unmodifiableMap(loaded);
        problems = List.copyOf(found);
        for (String p : found) VoxelMindLocal.LOG.warn("Brain skipped: {}", p);
        return problems;
    }

    private static Brain builtin(String id) throws IOException {
        String base = "/assets/voxelmind_local/brains/" + id + "/";
        String json = resource(base + "brain.json");
        if (json == null) throw new IOException("brain.json missing");
        return new Brain(id, true, parse(json), resource(base + "frame.md"), resource(base + "conversation.md"),
                resource(base + "decision.md"), resource(base + "identity.md"), resource(base + "memory.md"));
    }

    private static Brain local(String id, Path dir) throws IOException {
        Path json = dir.resolve("brain.json");
        if (!Files.isRegularFile(json)) throw new IOException("brain.json missing");
        return new Brain(id, false, parse(read(json)), text(dir, "frame.md"), text(dir, "conversation.md"), text(dir, "decision.md"),
                text(dir, "identity.md"), text(dir, "memory.md"));
    }

    private static JsonObject parse(String json) throws IOException {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("brain.json is not valid JSON: " + e.getMessage());
        }
    }

    private static @Nullable String text(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        if (!Files.isRegularFile(p)) return null;
        String text = read(p).strip();
        return text.isEmpty() ? null : text;
    }

    private static String read(Path p) throws IOException {
        if (Files.size(p) > MAX_FILE_CHARS * 4L) throw new IOException(p.getFileName() + " is too large (at most " + MAX_FILE_CHARS + " characters)");
        String text = Files.readString(p, StandardCharsets.UTF_8);
        if (text.length() > MAX_FILE_CHARS) throw new IOException(p.getFileName() + " has " + text.length() + " characters; at most " + MAX_FILE_CHARS);
        return text;
    }

    private static @Nullable String resource(String path) throws IOException {
        try (InputStream in = Brains.class.getResourceAsStream(path)) {
            if (in == null) return null;
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? null : text;
        }
    }

    /**
     * Creates {@code brains/<id>/} as a full copy of the default brain: brain.json with every setting written out and each text
     * file holding the text the default uses, so a person or an AI agent changes existing text instead of starting empty.
     */
    public static Path create(String id) throws IOException {
        if (!ID.matcher(id).matches()) throw new IOException("a brain name is 1-32 lowercase letters, digits, - or _");
        if (BUILTIN.contains(id)) throw new IOException(id + " belongs to a brain shipped with the mod");
        Path dir = folder().resolve(id);
        if (Files.exists(dir)) throw new IOException(dir + " already exists");
        Files.createDirectories(dir);
        JsonObject json = new JsonObject();
        json.addProperty("name", id);
        json.addProperty("version", "0.1.0");
        json.addProperty("author", "");
        json.addProperty("description", "Started from VoxelMind Default. Describe what this brain does differently.");
        json.add("models", new com.google.gson.JsonArray());
        json.addProperty("minMod", "0.5.0");
        JsonObject tools = new JsonObject();
        tools.add("include", new com.google.gson.JsonArray());
        tools.add("exclude", new com.google.gson.JsonArray());
        json.add("tools", tools);
        JsonObject timeline = new JsonObject();
        timeline.addProperty("events", 30);
        timeline.addProperty("chats", 24);
        timeline.addProperty("chatMinutes", 30);
        timeline.addProperty("chatChars", 6000);
        json.add("timeline", timeline);
        JsonObject blocks = new JsonObject();
        for (String b : new String[] {"sensory", "bodyInterface", "clock", "lastAction", "previously", "aboutOwner"}) blocks.addProperty(b, true);
        json.add("blocks", blocks);
        json.addProperty("memory", true);
        Files.writeString(dir.resolve("brain.json"), GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("frame.md"), PromptTexts.DEFAULT_FRAME + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("conversation.md"), PromptTexts.CONVERSATION_FRAME + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("decision.md"), PromptTexts.DECISION_TEMPLATE + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("identity.md"), PromptTexts.DEFAULT_IDENTITY + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("memory.md"), PromptTexts.MEMORY_GUIDE + "\n", StandardCharsets.UTF_8);
        reload();
        return dir;
    }

    /** The prompt an owner gives their AI agent to build a brain; {@code what} is their idea, or a placeholder. */
    public static String agentPrompt(String what) {
        return "Build a VoxelMind brain for me. Read the guide first: https://github.com/Nyvoro/voxelmind/blob/master/brains/README.md . "
                + "Work in this folder: " + folder().toAbsolutePath() + " (run /vml brain new <name> in Minecraft first if it has no copy to start from). "
                + "What the brain should do differently: " + what + ". Only edit the brain's files (brain.json and the .md texts), keep "
                + "{name} and {owner} where they make sense, then tell me to run /vml brain reload and /vml brain <companion> <name> in the game.";
    }

}
