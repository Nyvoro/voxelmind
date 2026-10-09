package com.voxelmind.local.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.config.LocalConfig;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * OpenRouter sign-in (OAuth PKCE) and key status. The player's browser talks to OpenRouter directly;
 * the resulting key lands only in config/voxelmind-local.json.
 */
public final class OpenRouterAccount {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String AUTH_URL = "https://openrouter.ai/auth";
    private static final String KEYS_URL = "https://openrouter.ai/api/v1/auth/keys";
    private static final int LOGIN_TIMEOUT_MINUTES = 5;

    /** Credits and limit of the configured key; fields are NaN when OpenRouter does not report them. */
    public record KeyStatus(String label, double usage, double usageToday, double limitRemaining, double balance) {}

    /** A started sign-in: open {@code url} in a browser, then wait for {@code key}. */
    public record Login(URI url, CompletableFuture<String> key, Runnable cancel) {}

    private OpenRouterAccount() {}

    /**
     * Sign-in with a callback to a short-lived server on this machine. Works where the browser
     * runs on the same computer as Minecraft (singleplayer, LAN host).
     */
    public static Login startLocalLogin() throws IOException {
        String verifier = randomToken();
        String state = randomToken();
        CompletableFuture<String> key = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = server.getAddress().getPort();
        // Browsers may resolve localhost to ::1 first; listen there too when the system has it.
        HttpServer server6 = null;
        try {
            server6 = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), port), 0);
        } catch (IOException ignored) {
        }
        java.util.concurrent.atomic.AtomicBoolean exchanging = new java.util.concurrent.atomic.AtomicBoolean();
        com.sun.net.httpserver.HttpHandler handler = exchange -> {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            boolean ok = state.equals(query.get("state")) && query.containsKey("code");
            String page = ok
                    ? "<h2>VoxelMind: signed in.</h2><p>You can close this tab and go back to Minecraft.</p>"
                    : "<h2>VoxelMind: sign-in did not finish.</h2><p>Go back to Minecraft and try again.</p>";
            byte[] body = ("<!doctype html><meta charset=utf-8><title>VoxelMind</title>"
                    + "<body style=\"font-family:sans-serif;margin:3em\">" + page).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(ok ? 200 : 400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            // The code is single-use; a reloaded tab must not spend it a second time.
            if (ok && exchanging.compareAndSet(false, true)) {
                exchange(query.get("code"), verifier).whenComplete((k, error) -> {
                    if (error != null) key.completeExceptionally(error);
                    else key.complete(k);
                });
            } else if (!ok && query.containsKey("error")) {
                // OpenRouter returns no state on denial, so only an explicit error ends the wait.
                key.completeExceptionally(new IllegalStateException("OpenRouter said: " + query.get("error")));
            }
        };
        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VoxelMind Local sign-in");
            t.setDaemon(true);
            return t;
        });
        for (HttpServer s : server6 == null ? new HttpServer[]{server} : new HttpServer[]{server, server6}) {
            s.setExecutor(executor);
            s.createContext("/callback", handler);
            s.start();
        }
        HttpServer second = server6;
        Runnable stop = () -> {
            server.stop(0);
            if (second != null) second.stop(0);
            executor.shutdown();
        };
        key.whenComplete((k, error) -> stop.run());
        key.orTimeout(LOGIN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        String callback = "http://localhost:" + port + "/callback";
        URI url = URI.create(AUTH_URL + "?callback_url=" + encode(callback) + "&code_challenge=" + challenge(verifier)
                + "&code_challenge_method=S256&state=" + state + "&key_label=" + encode("VoxelMind Local"));
        return new Login(url, key, () -> key.completeExceptionally(new IllegalStateException("cancelled")));
    }

    /** Pending headless sign-ins by player: OpenRouter shows a code that the player types into chat. */
    private static final Map<java.util.UUID, String> HEADLESS = new HashMap<>();

    /** Sign-in where OpenRouter shows the code on its page; for dedicated servers and other machines. */
    public static synchronized URI startHeadlessLogin(java.util.UUID player) {
        String verifier = randomToken();
        HEADLESS.put(player, verifier);
        return URI.create(AUTH_URL + "?code_challenge=" + challenge(verifier) + "&code_challenge_method=S256&key_label="
                + encode("VoxelMind Local"));
    }

    public static synchronized @Nullable CompletableFuture<String> finishHeadlessLogin(java.util.UUID player, String code) {
        String verifier = HEADLESS.remove(player);
        return verifier == null ? null : exchange(code.trim(), verifier);
    }

    private static CompletableFuture<String> exchange(String code, String verifier) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("code_verifier", verifier);
        body.addProperty("code_challenge_method", "S256");
        HttpRequest request = HttpRequest.newBuilder(URI.create(KEYS_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            JsonObject root = parseObject(response.body());
            if (response.statusCode() / 100 != 2 || root == null || !root.has("key")) {
                String message = root != null && root.has("error") ? root.get("error").toString() : "HTTP " + response.statusCode();
                throw new IllegalStateException("OpenRouter did not issue a key: " + message);
            }
            return root.get("key").getAsString();
        });
    }

    /** Stores a key from a sign-in and points the mod at OpenRouter. */
    public static synchronized void adopt(String key) {
        LocalConfig config = LocalConfig.get();
        config.selectEndpoint(LocalConfig.OPENROUTER_URL);
        config.apiKey = key.trim();
        LocalConfig.save();
        VoxelMindLocal.LOG.info("OpenRouter key saved from sign-in");
    }

    /** Usage and remaining credit of a key, from /key and /credits. */
    public static CompletableFuture<KeyStatus> status(String key) {
        CompletableFuture<JsonObject> keyInfo = get("https://openrouter.ai/api/v1/key", key);
        CompletableFuture<JsonObject> credits = get("https://openrouter.ai/api/v1/credits", key)
                .exceptionally(error -> new JsonObject());
        return keyInfo.thenCombine(credits, (k, c) -> {
            JsonObject data = k.has("data") ? k.getAsJsonObject("data") : new JsonObject();
            JsonObject money = c.has("data") && c.get("data").isJsonObject() ? c.getAsJsonObject("data") : new JsonObject();
            double balance = Double.NaN;
            if (money.has("total_credits") && money.has("total_usage")) {
                balance = money.get("total_credits").getAsDouble() - money.get("total_usage").getAsDouble();
            }
            return new KeyStatus(
                    data.has("label") && !data.get("label").isJsonNull() ? data.get("label").getAsString() : "",
                    num(data, "usage"), num(data, "usage_daily"), num(data, "limit_remaining"), balance);
        });
    }

    private static CompletableFuture<JsonObject> get(String url, String key) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + key)
                .GET()
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 401) throw new Rejected("OpenRouter does not accept this key");
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
            JsonObject root = parseObject(response.body());
            return root == null ? new JsonObject() : root;
        });
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : Double.NaN;
    }

    private static @Nullable JsonObject parseObject(String body) {
        try {
            var element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(@Nullable String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
