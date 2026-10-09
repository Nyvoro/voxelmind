package com.voxelmind.local.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.voxelmind.local.compat.Compat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Borrows the signed skin of an existing Minecraft account for a companion's body. */
public final class Skins {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public record Skin(String sourceName, String value, String signature) {}

    private Skins() {}

    /** Looks up the account's current skin at Mojang. */
    public static CompletableFuture<Skin> fetch(String username) {
        String url = "https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(username, StandardCharsets.UTF_8);
        return get(url).thenCompose(profile -> {
            if (profile == null || !profile.has("id")) throw new IllegalArgumentException("no Minecraft account named " + username);
            String id = profile.get("id").getAsString();
            String name = profile.has("name") ? profile.get("name").getAsString() : username;
            return get("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false").thenApply(session -> {
                if (session == null || !session.has("properties")) throw new IllegalStateException("Mojang returned no skin for " + name);
                JsonArray props = session.getAsJsonArray("properties");
                for (JsonElement e : props) {
                    JsonObject p = e.getAsJsonObject();
                    if ("textures".equals(p.get("name").getAsString())) {
                        return new Skin(name, p.get("value").getAsString(), p.has("signature") ? p.get("signature").getAsString() : null);
                    }
                }
                throw new IllegalStateException("Mojang returned no skin for " + name);
            });
        });
    }

    private static CompletableFuture<JsonObject> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() == 204 || r.statusCode() == 404) return null;
            if (r.statusCode() / 100 != 2) throw new IllegalStateException("Mojang answered HTTP " + r.statusCode());
            return JsonParser.parseString(r.body()).getAsJsonObject();
        });
    }

    /** The profile a companion's body uses: its own id and name, with the borrowed skin when set. */
    public static GameProfile profile(CompanionState state) {
        if (state.skinValue == null || state.skinValue.isBlank()) return new GameProfile(state.id, state.name);
        return Compat.texturedProfile(state.id, state.name, state.skinValue, state.skinSignature);
    }

    public static UUID id(CompanionState state) {
        return state.id;
    }
}
