package com.voxelmind.local.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

/**
 * VoxelMind API (api.voxel-mind.com): our own endpoint, paid with prepaid Sparks. The player signs in
 * with the Minecraft account itself, by signing a server id with the player key Mojang certifies; the
 * resulting token stays in config/voxelmind-local.json and is only sent to the VoxelMind API.
 */
public final class VoxelMindApi {
    /** {@code -Dvoxelmind.api=http://127.0.0.1:8787/v1} points the mod at a local test worker. */
    public static final String BASE = System.getProperty("voxelmind.api", "https://api.voxel-mind.com/v1");
    public static final String SITE = "https://voxel-mind.com";
    public static final String MODEL = "voxelmind/standard";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public record Balance(double sparks, double free, boolean freeCapReached, String name) {
        public double total() {
            return sparks + free;
        }
    }

    public record Login(String token, String name, double welcome, double sparks, double free) {}

    /** What the player's key signs: this prefix and the server id. */
    public static final String CHALLENGE = "VoxelMind sign-in ";

    /** The signed server id with Mojang's certificate for the signing key (uuid, expiry, X.509 key, Mojang's signature). */
    public record Proof(String serverId, String uuid, long expiresAt, byte[] publicKey, byte[] keySignature, byte[] signature) {}

    private VoxelMindApi() {}

    public static boolean isVoxelMind(@Nullable String baseUrl) {
        return baseUrl != null && (com.voxelmind.local.config.LocalConfig.isHost(baseUrl, "api.voxel-mind.com") || baseUrl.equals(BASE));
    }

    /** The shop page, with the Minecraft name filled in when it is known. */
    public static String buyUrl(@Nullable String name) {
        return SITE + "/sparks" + (name == null || name.isBlank() ? "" : "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
    }

    /** The player's private Sparks page: balance, spending per day, credits. The token stays in the URL fragment. */
    public static String accountUrl(String token) {
        return SITE + "/sparks/account#t=" + token;
    }

    /** Redeems a one-time Sparks code for the signed-in account; completes with the Sparks credited. */
    public static CompletableFuture<Double> redeem(String token, String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/redeem"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            JsonObject root = object(r.body());
            if (r.statusCode() / 100 != 2 || root == null) throw new IllegalStateException(error(r.statusCode(), root));
            return num(root, "sparks");
        });
    }

    /** Step 1: a server id the client then signs with the player key. */
    public static CompletableFuture<String> startLogin() {
        return post("/auth/minecraft/start", new JsonObject()).thenApply(root -> root.get("server_id").getAsString());
    }

    /** Step 2: the API checks the certificate and the signature, and answers with a token. */
    public static CompletableFuture<Login> finishLogin(String name, Proof proof) {
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        JsonObject certificate = new JsonObject();
        certificate.addProperty("uuid", proof.uuid());
        certificate.addProperty("expires_at", proof.expiresAt());
        certificate.addProperty("public_key", b64.encodeToString(proof.publicKey()));
        certificate.addProperty("key_signature", b64.encodeToString(proof.keySignature()));
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("server_id", proof.serverId());
        body.add("certificate", certificate);
        body.addProperty("signature", b64.encodeToString(proof.signature()));
        return post("/auth/minecraft/finish", body).thenApply(root -> new Login(root.get("token").getAsString(), root.get("name").getAsString(),
                num(root, "welcome_sparks"), num(root, "sparks"), num(root, "free_sparks")));
    }

    public static CompletableFuture<Balance> balance(String token) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/me"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            JsonObject root = object(r.body());
            if (r.statusCode() == 401) throw new Rejected("the VoxelMind sign-in is no longer valid; sign in again");
            if (r.statusCode() / 100 != 2 || root == null) throw new IllegalStateException(error(r.statusCode(), root));
            return new Balance(num(root, "sparks"), num(root, "free_sparks"),
                    root.has("free_cap_reached") && root.get("free_cap_reached").getAsBoolean(), root.has("name") ? root.get("name").getAsString() : "");
        });
    }

    /** Stores the token and makes the VoxelMind API the endpoint, with its one model. */
    public static void adopt(String token, String name) {
        LocalConfig config = LocalConfig.get();
        config.voxelmindKey = token.trim();
        config.voxelmindName = name == null ? "" : name.trim();
        config.selectEndpoint(BASE);
        LocalConfig.save();
    }

    private static CompletableFuture<JsonObject> post(String path, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            JsonObject root = object(r.body());
            if (r.statusCode() / 100 != 2 || root == null) throw new IllegalStateException(error(r.statusCode(), root));
            return root;
        });
    }

    private static @Nullable JsonObject object(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String error(int status, @Nullable JsonObject root) {
        if (root != null && root.has("error") && root.get("error").isJsonObject()) {
            JsonObject e = root.getAsJsonObject("error");
            if (e.has("message")) return e.get("message").getAsString();
        }
        return "HTTP " + status;
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0;
    }
}
