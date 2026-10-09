package com.voxelmind.local.hosted;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** The Builder's lookup: search the Minecraft Wiki, then read the first hit's plain-text extract. */
public final class Wiki {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final String API = "https://minecraft.wiki/api.php";
    private static final int MAX_CHARS = 1500;
    private static final Map<String, String> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 200;
        }
    };

    private Wiki() {}

    public static CompletableFuture<String> lookup(String query) {
        String key = query.trim().toLowerCase();
        synchronized (CACHE) {
            String hit = CACHE.get(key);
            if (hit != null) return CompletableFuture.completedFuture(hit);
        }
        return get(API + "?action=query&list=search&srlimit=3&format=json&srsearch=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
                .thenCompose(search -> {
                    JsonArray hits = search.getAsJsonObject("query").getAsJsonArray("search");
                    if (hits == null || hits.isEmpty()) return CompletableFuture.completedFuture("no wiki page found for \"" + query + "\"");
                    String title = hits.get(0).getAsJsonObject().get("title").getAsString();
                    return get(API + "?action=query&prop=extracts&explaintext=1&exintro=0&redirects=1&format=json&titles="
                            + URLEncoder.encode(title, StandardCharsets.UTF_8)).thenApply(page -> {
                        JsonObject pages = page.getAsJsonObject("query").getAsJsonObject("pages");
                        String extract = "";
                        for (Map.Entry<String, JsonElement> e : pages.entrySet()) {
                            JsonObject p = e.getValue().getAsJsonObject();
                            if (p.has("extract")) extract = p.get("extract").getAsString();
                        }
                        String text = "Minecraft Wiki, page " + title + ": " + extract.replaceAll("\\s+", " ").trim();
                        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "…" : text;
                    });
                })
                .thenApply(text -> {
                    synchronized (CACHE) {
                        CACHE.put(key, text);
                    }
                    return text;
                });
    }

    private static CompletableFuture<JsonObject> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "VoxelMind-Local (Minecraft mod; https://voxel-mind.com)").GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() / 100 != 2) throw new IllegalStateException("wiki answered HTTP " + r.statusCode());
            return JsonParser.parseString(r.body()).getAsJsonObject();
        });
    }
}
