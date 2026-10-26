package com.voxelmind.local.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.voxelmind.local.VoxelMindLocal;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Player-owned settings in config/voxelmind-local.json. The key never leaves this
 * file except as the Authorization header to the chosen LLM endpoint.
 */
public final class LocalConfig {
    public static final String OPENROUTER_URL = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_MODEL = "deepseek/deepseek-v4-flash-0731";
    /**
     * Providers tried one after another for {@link #DEFAULT_MODEL}: US providers with zero data retention,
     * measured on 08.10.2026 (Relace about $0.17/h, Sail Research $0.10-0.15/h, Together as
     * the last resort). Other models keep OpenRouter's own routing.
     */
    public static final java.util.List<String> DEFAULT_PROVIDER_ORDER = java.util.List.of("Relace", "Sail Research", "Together");
    private static final java.util.List<String> OLD_DEFAULT_PROVIDER_ORDER = java.util.List.of("StreamLake", "Together", "Wafer");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile LocalConfig current = new LocalConfig();

    /** OpenRouter key; only ever sent to OpenRouter. */
    public String apiKey = "";
    /** Key for any other endpoint in {@link #baseUrl}. Empty is fine for local servers like Ollama. */
    public String endpointKey = "";
    /** Player2 key from the sign-in; only ever sent to Player2. */
    public String player2Key = "";
    /** Game Client ID from the Player2 Developer Dashboard; empty uses the one built into the mod. */
    public String player2ClientId = "";
    /**
     * OpenAI-compatible base URL, e.g. http://localhost:11434/v1 for Ollama. A new config starts on Player2: free,
     * one sign-in, nothing else to set up. OpenRouter, a local model or any other server are one click further.
     */
    public String baseUrl = com.voxelmind.local.provider.Player2.WEB;
    /**
     * Set once when this config still pointed at VoxelMind Sparks, which ended in 2026 (see {@link #migrate}): the
     * host is told once and sent to the AI settings. Cleared when that notice was shown.
     */
    public boolean sparksEndedNotice = false;
    public String model = DEFAULT_MODEL;
    /** Model that rewrites long-term memory; empty uses {@link #model}. */
    public String memoryModel = "";
    public double temperature = 0.7;
    public int maxTokens = 4096;
    public int requestTimeoutSeconds = 25;
    /** Timeout for endpoints other than OpenRouter, e.g. Ollama on the same PC. */
    public int localRequestTimeoutSeconds = 120;
    /** OpenRouter only: providers tried one after another for the model; empty lets OpenRouter choose. */
    public java.util.List<String> providerOrder = new java.util.ArrayList<>(DEFAULT_PROVIDER_ORDER);
    /** Technical cost limit across all companions on this server. */
    public int maxRequestsPerMinute = 20;
    /** Companion bodies one player may have in the world at once; the singleplayer host and operators have no limit. 0 = none. */
    public int maxCompanionsPerPlayer = 3;
    /** Fallback speech language when the conversation gives no clue, e.g. "de" or "en". "auto" leaves it to the conversation. */
    public String language = "auto";
    /** Randomised quiet-time wake while the owner is near and the body is idle. 0 disables it. */
    public int idleWakeMinSeconds = 45;
    public int idleWakeMaxSeconds = 240;
    /**
     * The quiet-time timer (and a God's idle turn) only runs while the owner did something in the game within
     * this many minutes: an owner away from the keyboard costs no AI power or credit. Real events still wake. 0 = off.
     */
    public int idleOwnerAwayMinutes = 5;
    /** Blocks within which a companion hears public chat. */
    public int hearingRange = 48;
    /** Also write full prompts into the world's voxelmind-local/logs (local debugging only). */
    public boolean logPrompts = false;
    /**
     * One anonymous count a day of which kind of AI service this installation uses (see UsageStats): the kind,
     * the mod version and the game version, nothing else. false turns it off; so does /vml stats off.
     */
    public boolean usageStats = true;
    /** Set once the host or an owner-level operator was told about {@link #usageStats} in the game; nothing is sent before. */
    public boolean usageStatsNoticed = false;
    /** UTC day of the last count sent. */
    public String usageStatsDay = "";
    /** Optional own MineSkin API key for signing own skin PNGs; empty uses MineSkin without a key (about 10 skins a minute). */
    public String mineskinKey = "";
    /** UTC day this installation first ran this version of the config; only its age in steps goes into the usage count. */
    public String installedDay = "";
    /** UTC day of {@link #usageBrainMinutes}: minutes with at least one decision, per brain (shipped ones by id, others "custom"). */
    public String usageBrainDay = "";
    public java.util.Map<String, Integer> usageBrainMinutes = new java.util.HashMap<>();

    public static LocalConfig get() {
        return current;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("voxelmind-local.json");
    }

    public static synchronized void load() {
        Path path = path();
        LocalConfig loaded = null;
        if (Files.exists(path)) {
            try {
                loaded = fromJson(Files.readString(path, StandardCharsets.UTF_8));
            } catch (Exception e) {
                // Keys and sign-ins live in this file: keep it aside instead of overwriting it with defaults.
                Path aside = path.resolveSibling(path.getFileName() + ".broken-" + System.currentTimeMillis());
                VoxelMindLocal.LOG.error("Could not read {}; it is kept as {} and the defaults are used", path, aside.getFileName(), e);
                try {
                    Files.move(path, aside);
                } catch (IOException moveError) {
                    VoxelMindLocal.LOG.error("Could not keep {} aside", path, moveError);
                    current = new LocalConfig();
                    return;
                }
            }
        }
        current = loaded != null ? loaded : new LocalConfig();
        if (current.installedDay == null || current.installedDay.isBlank()) {
            current.installedDay = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        }
        if (current.usageBrainMinutes == null) current.usageBrainMinutes = new java.util.HashMap<>();
        save();
    }

    /** The text of a config file, read and migrated as {@link #load} does; null for an empty file. */
    public static @org.jspecify.annotations.Nullable LocalConfig fromJson(String json) {
        LocalConfig config = GSON.fromJson(json, LocalConfig.class);
        if (config != null) migrate(config);
        return config;
    }

    /** The config as {@link #save} writes it. */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Earlier versions wrote the provider order measured for the default model into every config; that
     * order started at a provider outside the zero-data-retention set.
     * <p>
     * Up to 0.5.0 a config could think with VoxelMind Sparks (api.voxel-mind.com), which ended in 2026. Such a config
     * moves to Player2 and keeps everything else; the old Sparks sign-in is dropped from the file with the next save.
     * {@link #sparksEndedNotice} makes the game say so once and open the AI settings.
     */
    private static void migrate(LocalConfig config) {
        if (config.providerOrder != null && config.providerOrder.equals(OLD_DEFAULT_PROVIDER_ORDER)) {
            config.providerOrder = new java.util.ArrayList<>(DEFAULT_PROVIDER_ORDER);
        }
        if (isSparks(config.baseUrl)) {
            config.selectEndpoint(com.voxelmind.local.provider.Player2.WEB);
            config.sparksEndedNotice = true;
            VoxelMindLocal.LOG.warn("VoxelMind Sparks has ended; the AI settings now start on Player2. "
                    + "Choose another AI provider (your own key) or a local model with /vml setup.");
        }
    }

    /** Host of the VoxelMind Sparks API, which ended in 2026. */
    private static final String SPARKS_HOST = "api.voxel-mind.com";

    /** Whether a URL points at the ended VoxelMind Sparks API (any scheme or path); such an endpoint is refused. */
    public static boolean isSparks(@org.jspecify.annotations.Nullable String url) {
        if (url == null) return false;
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            return host != null && SPARKS_HOST.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True once after the Sparks migration; whoever shows the notice first (title screen or the host's join) clears it. */
    public static synchronized boolean takeSparksEndedNotice() {
        if (!current.sparksEndedNotice) return false;
        current.sparksEndedNotice = false;
        save();
        return true;
    }

    public static synchronized void save() {
        try {
            writeAtomically(path(), current.toJson());
        } catch (IOException e) {
            VoxelMindLocal.LOG.error("Could not write {}", path(), e);
        }
    }

    /** Writes a file whole or not at all: a crash or a full disk mid-write leaves the previous version. */
    public static void writeAtomically(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Key for the current endpoint; for OpenRouter falls back to VOXELMIND_LOCAL_API_KEY for development runs. */
    public String effectiveApiKey() {
        if (isPlayer2()) return player2Key == null ? "" : player2Key.trim();
        if (!isOpenRouter()) return endpointKey == null ? "" : endpointKey.trim();
        if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();
        String env = System.getenv("VOXELMIND_LOCAL_API_KEY");
        return env == null ? "" : env.trim();
    }

    /** Stores a key for the current endpoint. */
    public void setKey(String key) {
        if (isPlayer2()) player2Key = key.trim();
        else if (isOpenRouter()) apiKey = key.trim();
        else endpointKey = key.trim();
    }

    public boolean isOpenRouter() {
        return isHost(baseUrl, "openrouter.ai");
    }

    /**
     * Whether an endpoint URL is https on exactly this host. Stored keys and tokens only ever go to their own
     * service, so a lookalike such as openrouter.ai.example.net is just another custom endpoint.
     */
    public static boolean isHost(@org.jspecify.annotations.Nullable String url, String host) {
        if (url == null) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && host.equalsIgnoreCase(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isPlayer2() {
        return com.voxelmind.local.provider.Player2.isPlayer2(baseUrl);
    }

    public boolean hasCredentials() {
        return !effectiveApiKey().isEmpty() || !isOpenRouter() && !isPlayer2();
    }

    /** What the menu shows as the model: Player2 chooses its model itself. */
    public String modelLabel() {
        return isPlayer2() ? "Player2" : model;
    }

    public String effectiveMemoryModel() {
        return memoryModel == null || memoryModel.isBlank() ? model : memoryModel.trim();
    }

    /**
     * Switches the companion's decisions and the memory rewrite to one model. The measured provider order only
     * fits the default model, so any other model lets OpenRouter route on its own.
     */
    public void selectModel(String id) {
        model = id.trim();
        memoryModel = "";
        providerOrder = new java.util.ArrayList<>(model.equals(DEFAULT_MODEL) ? DEFAULT_PROVIDER_ORDER : java.util.List.of());
    }

    /** Points the mod at another OpenAI-compatible endpoint. Provider routing exists only on OpenRouter. */
    public void selectEndpoint(String url) {
        String clean = url.trim();
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        baseUrl = clean.isEmpty() ? OPENROUTER_URL : clean;
        if (!isOpenRouter()) providerOrder = new java.util.ArrayList<>();
        else if (model.equals(DEFAULT_MODEL) && (providerOrder == null || providerOrder.isEmpty())) {
            providerOrder = new java.util.ArrayList<>(DEFAULT_PROVIDER_ORDER);
        }
    }

    /** Local models read an 8k-token prompt much slower than hosted ones. */
    public int effectiveTimeoutSeconds() {
        return isOpenRouter() || isPlayer2() ? Math.max(requestTimeoutSeconds, isPlayer2() ? 40 : 0)
                : Math.max(requestTimeoutSeconds, localRequestTimeoutSeconds);
    }
}
