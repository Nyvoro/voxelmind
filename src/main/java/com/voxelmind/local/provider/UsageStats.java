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
 * VoxelMind Sparks, Player2, OpenRouter, a local model or another endpoint. The count carries that kind and the mod
 * and game versions; no name, no id, no key and nothing from a chat. The VoxelMind API adds it to a counter per day
 * and kind and keeps nothing else. Nothing is sent before the host (or an owner-level operator) was told in the
 * game; "usageStats": false in the config or /vml stats off turns it off.
 */
public final class UsageStats {
    public static final String URL = VoxelMindApi.BASE + "/ping";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private UsageStats() {}

    /** The kind of service the configured endpoint is. */
    public static String kind(LocalConfig config) {
        if (config.isVoxelMind()) return "sparks";
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
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally(e -> null);
        } catch (IllegalArgumentException e) {
            // A broken voxelmind.api property in a development run; a count is never worth an error.
        }
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
                        "So we can see which AI services players pick, the mod sends one anonymous count a day: the kind of service (VoxelMind Sparks, Player2, OpenRouter or a local model) and the mod and game version. No name, no ID, nothing from your chats.")
                        .withStyle(ChatFormatting.GRAY))
                .append(" ")
                .append(Component.translatableWithFallback("voxelmind_local.stats.turn_off", "[Turn off]").withStyle(style -> style
                        .withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/vml stats off"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("/vml stats off")))));
    }
}
