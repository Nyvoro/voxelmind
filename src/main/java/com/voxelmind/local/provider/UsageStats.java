package com.voxelmind.local.provider;

import com.google.gson.JsonObject;
import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.config.LocalConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * One anonymous count a day of which kind of AI service this installation uses, so we can see what players pick:
 * Player2, OpenRouter, a local model or another endpoint. The count carries that kind and the mod and game versions;
 * no name, no id, no key and nothing from a chat. api.voxel-mind.com adds it to a counter per day and kind and keeps
 * nothing else. Nothing is sent before the host (or an owner-level operator) was told in the
 * game; "usageStats": false in the config or /vml stats off turns it off.
 */
public final class UsageStats {
    public static final String URL = "https://api.voxel-mind.com/v1/ping";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private UsageStats() {}

    /** The kind of service the configured endpoint is. */
    public static String kind(LocalConfig config) {
        if (config.isPlayer2()) return "player2";
        if (config.isOpenRouter()) return "openrouter";
        return isLocal(config.baseUrl) ? "local" : "custom";
    }

    /** Ollama, LM Studio and the like on this PC or in the home network; decided from the address alone, without a lookup. */
    static boolean isLocal(@Nullable String url) {
        if (url == null) return false;
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            return host.equals("localhost") || host.endsWith(".local") || host.equals("::1")
                    || host.matches("127\\..*|10\\..*|192\\.168\\..*|172\\.(1[6-9]|2\\d|3[01])\\..*|169\\.254\\..*|fd[0-9a-f]{2}:.*");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** After an answer from the AI service: the first one of a UTC day sends that day's count. */
    public static void used() {
        LocalConfig config = LocalConfig.get();
        // Development runs (game tests, runClient, runServer) are not players.
        if (!config.usageStats || !config.usageStatsNoticed || FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        synchronized (UsageStats.class) {
            if (today.equals(config.usageStatsDay)) return;
            config.usageStatsDay = today;
        }
        LocalConfig.save();
        JsonObject body = new JsonObject();
        body.addProperty("provider", kind(config));
        body.addProperty("mod", version(VoxelMindLocal.MOD_ID));
        body.addProperty("mc", version("minecraft"));
        body.addProperty("age", ageStep(config.installedDay, today));
        post(body);
    }

    private static void post(JsonObject body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally(e -> null);
        } catch (IllegalArgumentException e) {
            // A count is never worth an error.
        }
    }

    /** How long ago this installation started, in steps: "0" (today), "1-7", "8-30" or "31+" days. Only the step is sent. */
    public static String ageStep(String installedDay, String today) {
        try {
            long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(installedDay), LocalDate.parse(today));
            return days <= 0 ? "0" : days <= 7 ? "1-7" : days <= 30 ? "8-30" : "31+";
        } catch (RuntimeException e) {
            return "0";
        }
    }

    /** Minutes of a day in steps: "1-15", "15-60" or "60+". Only the step is sent, never the exact time. */
    public static String minuteStep(int minutes) {
        return minutes < 15 ? "1-15" : minutes < 60 ? "15-60" : "60+";
    }

    /** The minute of the last decision counted per brain, so one minute counts once however many decisions it had. */
    private static final java.util.Map<String, Long> COUNTED_MINUTE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * After a companion's decision: the minute counts for its brain. When a new UTC day starts, the previous day's minutes per
     * brain go out as steps (see {@link #minuteStep}), one count per installation and day; a day nobody played sends nothing.
     */
    public static void decided(com.voxelmind.local.mind.Brain brain) {
        LocalConfig config = LocalConfig.get();
        if (!config.usageStats || !config.usageStatsNoticed || FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        long minute = System.currentTimeMillis() / 60_000;
        String id = brain.statsId();
        String sendDay = null;
        java.util.Map<String, Integer> send = null;
        boolean save = false;
        synchronized (UsageStats.class) {
            if (!today.equals(config.usageBrainDay)) {
                if (config.usageBrainDay != null && !config.usageBrainDay.isBlank() && config.usageBrainMinutes != null && !config.usageBrainMinutes.isEmpty()) {
                    sendDay = config.usageBrainDay;
                    send = new java.util.TreeMap<>(config.usageBrainMinutes);
                }
                config.usageBrainDay = today;
                config.usageBrainMinutes = new java.util.HashMap<>();
                COUNTED_MINUTE.clear();
                save = true;
            }
            Long last = COUNTED_MINUTE.put(id, minute);
            if (last == null || last != minute) {
                int total = config.usageBrainMinutes.merge(id, 1, Integer::sum);
                // Written now and then, not every minute; the server stop writes the rest.
                save |= total % 5 == 1;
            }
        }
        if (save) LocalConfig.save();
        if (send == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("type", "brains");
        body.addProperty("day", sendDay);
        body.addProperty("mod", version(VoxelMindLocal.MOD_ID));
        body.addProperty("mc", version("minecraft"));
        JsonObject brains = new JsonObject();
        for (var e : send.entrySet()) brains.addProperty(e.getKey(), minuteStep(e.getValue()));
        body.add("brains", brains);
        post(body);
    }

    /** "0.4.5" for the mod (without the "+26.2" build suffix), "1.21.11" for the game. */
    static String version(String modId) {
        String v = FabricLoader.getInstance().getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        int plus = v.indexOf('+');
        return (plus > 0 ? v.substring(0, plus) : v).trim();
    }

    /** Tells the host or an owner-level operator once, with a link that turns the count off. */
    public static void noticeOnce(ServerPlayer player, boolean admin) {
        LocalConfig config = LocalConfig.get();
        if (!admin || !config.usageStats || config.usageStatsNoticed) return;
        config.usageStatsNoticed = true;
        LocalConfig.save();
        player.sendSystemMessage(notice());
    }

    public static MutableComponent notice() {
        return Component.literal("[VoxelMind] ").withStyle(ChatFormatting.GRAY)
                .append(Component.translatableWithFallback("voxelmind_local.stats.notice",
                        "So we can see which AI services players pick, the mod sends one anonymous count a day: the kind of service (Player2, OpenRouter or a local model) and the mod and game version. No name, no ID, nothing from your chats.")
                        .withStyle(ChatFormatting.GRAY))
                .append(" ")
                .append(Component.translatableWithFallback("voxelmind_local.stats.turn_off", "[Turn off]").withStyle(style -> style
                        .withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/vml stats off"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("/vml stats off")))));
    }
}
