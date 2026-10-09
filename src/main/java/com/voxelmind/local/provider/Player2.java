package com.voxelmind.local.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.config.LocalConfig;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Player2 (player2.game): free AI for players through their Player2 account. The player signs in
 * through the Player2 app on this PC or with a code in the browser; the resulting key stays in
 * config/voxelmind-local.json. Player2 picks the model itself.
 */
public final class Player2 {
    public static final String WEB = "https://api.player2.game/v1";
    /**
     * The Player2 app on IPv4 loopback. Player2's docs and its own Minecraft engine use port 4315, the
     * developer dashboard names 4316 for the login; both are tried.
     */
    private static final java.util.List<String> APP_BASES = java.util.List.of("http://127.0.0.1:4315/v1", "http://127.0.0.1:4316/v1");
    /** Fallback for player2-game-key when no Client ID is set. */
    public static final String GAME_KEY = "voxelmind-local";
    /** Game Client ID from the Player2 Developer Dashboard; config.player2ClientId overrides it. */
    public static final String CLIENT_ID = "01a11bf0-6aec-7985-b273-59def381f5fa";
    public static final String PROFILE_URL = "https://player2.game/profile/ai-power";
    private static final long HEARTBEAT_MS = 60_000;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ScheduledExecutorService POLL = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VoxelMind Local Player2");
        t.setDaemon(true);
        return t;
    });
    private static volatile long lastHeartbeat;

    /** A started code sign-in: show {@code userCode} and {@code url}, then wait for {@code key}. */
    public record DeviceLogin(String userCode, URI url, CompletableFuture<String> key, Runnable cancel) {}

    public record Balance(long joules, String tier) {}

    private Player2() {}

    public static boolean isPlayer2(@Nullable String baseUrl) {
        return baseUrl != null && baseUrl.contains("player2.game");
    }

    /** player2-game-key: the Client ID, which is how Player2 attributes play time to this mod (as its own engine does). */
    public static String gameKey() {
        String id = clientId();
        return id.isEmpty() ? GAME_KEY : id;
    }

    public static String clientId() {
        String configured = LocalConfig.get().player2ClientId;
        return configured != null && !configured.isBlank() ? configured.trim() : CLIENT_ID;
    }

    /** True when the Player2 app answers on this PC. */
    public static CompletableFuture<Boolean> appRunning() {
        return appBase(0).thenApply(base -> base != null);
    }

    /** The first app address that answers, or null. */
    private static CompletableFuture<@Nullable String> appBase(int index) {
        if (index >= APP_BASES.size()) return CompletableFuture.completedFuture(null);
        String base = APP_BASES.get(index);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(2)).GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle((r, error) -> error == null && r.statusCode() < 500)
                .thenCompose(ok -> ok ? CompletableFuture.completedFuture(base) : appBase(index + 1));
    }

    /** Key from the running Player2 app, for the account the player is signed in with there. */
    public static CompletableFuture<String> loginWithApp() {
        String id = clientId();
        if (id.isEmpty()) return CompletableFuture.failedFuture(noClientId());
        return appBase(0).thenCompose(base -> {
            if (base == null) return CompletableFuture.failedFuture(new IllegalStateException("the Player2 app is not running"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/login/web/" + URLEncoder.encode(id, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
                JsonObject root = object(r.body());
                if (r.statusCode() / 100 != 2 || root == null || !root.has("p2Key")) {
                    throw new IllegalStateException("the Player2 app did not give a key (" + error(r.statusCode(), r.body()) + ")");
                }
                return root.get("p2Key").getAsString();
            });
        });
    }

    /** OAuth device flow: the player approves in any browser, e.g. on the phone; works on dedicated servers too. */
    public static CompletableFuture<DeviceLogin> startDeviceLogin() {
        String id = clientId();
        if (id.isEmpty()) return CompletableFuture.failedFuture(noClientId());
        JsonObject body = new JsonObject();
        body.addProperty("client_id", id);
        // Measured 08.10.2026: this call can take 20-30 s.
        HttpRequest request = HttpRequest.newBuilder(URI.create(WEB + "/login/device/new"))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            JsonObject root = object(r.body());
            if (r.statusCode() / 100 != 2 || root == null || !root.has("deviceCode")) {
                throw new IllegalStateException("Player2 did not start the sign-in (" + error(r.statusCode(), r.body()) + ")");
            }
            String deviceCode = root.get("deviceCode").getAsString();
            String userCode = root.has("userCode") ? root.get("userCode").getAsString() : "";
            String complete = root.has("verificationUriComplete") ? root.get("verificationUriComplete").getAsString()
                    : root.get("verificationUri").getAsString();
            long expiresAt = System.currentTimeMillis() + 1000L * (root.has("expiresIn") ? root.get("expiresIn").getAsLong() : 600);
            long interval = Math.max(2, root.has("interval") ? root.get("interval").getAsLong() : 5);
            CompletableFuture<String> key = new CompletableFuture<>();
            poll(id, deviceCode, interval, expiresAt, key);
            return new DeviceLogin(userCode, URI.create(complete), key,
                    () -> key.completeExceptionally(new IllegalStateException("cancelled")));
        });
    }

    private static void poll(String clientId, String deviceCode, long interval, long expiresAt, CompletableFuture<String> key) {
        POLL.schedule(() -> {
            if (key.isDone()) return;
            if (System.currentTimeMillis() > expiresAt) {
                key.completeExceptionally(new IllegalStateException("the code expired; start again"));
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("client_id", clientId);
            body.addProperty("device_code", deviceCode);
            body.addProperty("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            HttpRequest request = HttpRequest.newBuilder(URI.create(WEB + "/login/device/token"))
                    .timeout(Duration.ofSeconds(40))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((r, error) -> {
                if (key.isDone()) return;
                if (error != null) {
                    poll(clientId, deviceCode, interval, expiresAt, key);
                    return;
                }
                JsonObject root = object(r.body());
                if (r.statusCode() / 100 == 2 && root != null && root.has("p2Key")) {
                    key.complete(root.get("p2Key").getAsString());
                    return;
                }
                String code = root != null && root.has("error") && root.get("error").isJsonPrimitive() ? root.get("error").getAsString() : "";
                switch (code) {
                    case "authorization_pending", "" -> poll(clientId, deviceCode, interval, expiresAt, key);
                    case "slow_down" -> poll(clientId, deviceCode, interval + 5, expiresAt, key);
                    default -> key.completeExceptionally(new IllegalStateException("Player2 said: " + error(r.statusCode(), r.body())));
                }
            });
        }, interval, TimeUnit.SECONDS);
    }

    /** Stores a key from a sign-in and points the mod at Player2. */
    public static synchronized void adopt(String key) {
        LocalConfig config = LocalConfig.get();
        config.selectEndpoint(WEB);
        config.player2Key = key.trim();
        LocalConfig.save();
        VoxelMindLocal.LOG.info("Player2 key saved from sign-in");
    }

    public static CompletableFuture<Balance> balance(String key) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(WEB + "/joules"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + key)
                .header("player2-game-key", gameKey())
                .GET()
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() == 401) throw new IllegalStateException("Player2 does not accept this sign-in any more");
            JsonObject root = object(r.body());
            if (r.statusCode() / 100 != 2 || root == null) throw new IllegalStateException(error(r.statusCode(), r.body()));
            long joules = root.has("joules") ? root.get("joules").getAsLong() : 0;
            String tier = root.has("patron_tier") && !root.get("patron_tier").isJsonNull() ? root.get("patron_tier").getAsString() : "";
            return new Balance(joules, tier);
        });
    }

    /**
     * Player2 counts play time from a health call about once a minute; it shares its revenue with
     * mods by that time. Sent only while a companion is in the world and thinks with Player2.
     */
    public static void heartbeat(boolean companionPresent) {
        LocalConfig config = LocalConfig.get();
        if (!companionPresent || !config.isPlayer2() || config.player2Key == null || config.player2Key.isBlank()) return;
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat < HEARTBEAT_MS) return;
        lastHeartbeat = now;
        HttpRequest request = HttpRequest.newBuilder(URI.create(WEB + "/health"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + config.player2Key.trim())
                .header("player2-game-key", gameKey())
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally(error -> null);
    }

    private static IllegalStateException noClientId() {
        return new IllegalStateException("this build has no Player2 Game Client ID yet; set \"player2ClientId\" in config/voxelmind-local.json");
    }

    static String error(int status, String body) {
        JsonObject root = object(body);
        if (root != null && root.has("error")) {
            JsonElement e = root.get("error");
            String text = e.isJsonPrimitive() ? e.getAsString() : e.toString();
            if (root.has("error_description")) text += ": " + root.get("error_description").getAsString();
            return "HTTP " + status + ", " + text;
        }
        return "HTTP " + status;
    }

    private static @Nullable JsonObject object(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
