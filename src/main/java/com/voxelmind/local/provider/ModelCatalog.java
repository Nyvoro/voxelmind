package com.voxelmind.local.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.voxelmind.local.config.LocalConfig;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** The models an OpenAI-compatible endpoint lists under /models, with OpenRouter's prices where given. */
public final class ModelCatalog {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Calls per hour of active play and tokens per call, measured with the default model on 07.10.2026. */
    public static final int CALLS_PER_HOUR = 530;
    public static final int PROMPT_TOKENS_PER_CALL = 8300;
    public static final int COMPLETION_TOKENS_PER_CALL = 100;

    /**
     * Models that played the end-to-end scenarios on OpenRouter without errors, with the US$ per hour of
     * active play measured there (provider prices differ from the list price). Shown first when listed.
     * DeepSeek: 17 min of play on 07.10.2026; the others: the wood hand-over scenario on 08.10.2026.
     */
    public static final Map<String, Double> TESTED = Map.of(
            LocalConfig.DEFAULT_MODEL, 0.20,
            "z-ai/glm-5.3-flash", 0.26,
            "anthropic/claude-haiku-5.5", 0.31,
            "openai/gpt-5.6-luna", 0.45,
            "google/gemini-3.5-flash-lite", 1.20);

    /**
     * @param promptPrice     US$ per prompt token, NaN when the endpoint gives no price
     * @param tools           whether the endpoint says the model accepts tool calls; true when it does not say
     */
    public record Model(String id, String name, int contextLength, double promptPrice, double completionPrice, boolean tools) {
        public boolean priced() {
            return !Double.isNaN(promptPrice) && !Double.isNaN(completionPrice);
        }

        public boolean free() {
            return id.endsWith(":free") || priced() && promptPrice == 0 && completionPrice == 0;
        }

        /** List-price estimate per hour of active play, without prompt caching; NaN when unpriced. */
        public double dollarsPerHour() {
            if (!priced()) return Double.NaN;
            return CALLS_PER_HOUR * (PROMPT_TOKENS_PER_CALL * promptPrice + COMPLETION_TOKENS_PER_CALL * completionPrice);
        }

        public boolean tested() {
            return TESTED.containsKey(id);
        }
    }

    private static volatile @Nullable List<Model> cached;
    private static volatile String cachedFor = "";

    private ModelCatalog() {}

    /** Last list fetched for the current endpoint, or empty. */
    public static List<Model> cached() {
        return cached(LocalConfig.get().baseUrl);
    }

    public static List<Model> cached(String baseUrl) {
        List<Model> list = cached;
        return list != null && cachedFor.equals(baseUrl) ? list : List.of();
    }

    public static @Nullable Model find(String id) {
        for (Model m : cached()) if (m.id().equals(id)) return m;
        return null;
    }

    /** Models of the configured endpoint. */
    public static CompletableFuture<List<Model>> fetch(boolean refresh) {
        LocalConfig config = LocalConfig.get();
        return fetch(config.baseUrl, config.isOpenRouter() ? "" : config.effectiveApiKey(), refresh);
    }

    /** Models of any endpoint; OpenRouter lists them without a key. */
    public static CompletableFuture<List<Model>> fetch(String baseUrl, String key, boolean refresh) {
        List<Model> have = cached(baseUrl);
        if (!refresh && !have.isEmpty()) return CompletableFuture.completedFuture(have);
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + "/models")).timeout(Duration.ofSeconds(20)).GET();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new IllegalStateException("not a valid URL: " + baseUrl));
        }
        if (!key.isEmpty()) request.header("Authorization", "Bearer " + key);
        return HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " from " + baseUrl + "/models");
            }
            List<Model> models = parse(response.body());
            cached = models;
            cachedFor = baseUrl;
            return models;
        });
    }

    public static List<Model> parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        JsonArray data = root.isJsonObject() && root.getAsJsonObject().has("data")
                ? root.getAsJsonObject().getAsJsonArray("data")
                : root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
        List<Model> models = new ArrayList<>();
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject m = element.getAsJsonObject();
            if (!m.has("id")) continue;
            String id = m.get("id").getAsString();
            // Batch variants answer asynchronously within hours; a companion needs answers within seconds.
            if (id.endsWith(":batch")) continue;
            String name = m.has("name") && !m.get("name").isJsonNull() ? m.get("name").getAsString() : id;
            int context = m.has("context_length") && m.get("context_length").isJsonPrimitive() ? m.get("context_length").getAsInt() : 0;
            double prompt = Double.NaN;
            double completion = Double.NaN;
            if (m.has("pricing") && m.get("pricing").isJsonObject()) {
                JsonObject pricing = m.getAsJsonObject("pricing");
                prompt = number(pricing, "prompt");
                completion = number(pricing, "completion");
            }
            boolean tools = true;
            if (m.has("supported_parameters") && m.get("supported_parameters").isJsonArray()) {
                tools = false;
                for (JsonElement p : m.getAsJsonArray("supported_parameters")) {
                    if ("tools".equals(p.getAsString())) tools = true;
                }
            }
            if (m.has("architecture") && m.get("architecture").isJsonObject()) {
                JsonObject arch = m.getAsJsonObject("architecture");
                if (arch.has("output_modalities") && arch.get("output_modalities").isJsonArray()
                        && !arch.getAsJsonArray("output_modalities").toString().contains("\"text\"")) continue;
            }
            models.add(new Model(id, name, context, prompt, completion, tools));
        }
        models.sort(Comparator.comparing(Model::tested).reversed()
                .thenComparingDouble(m -> TESTED.getOrDefault(m.id(), 0.0))
                .thenComparing(Model::id));
        return models;
    }

    /** A price per token; routers that pick a model per request list -1, which counts as unknown. */
    private static double number(JsonObject o, String key) {
        try {
            double value = o.has(key) && !o.get(key).isJsonNull() ? Double.parseDouble(o.get(key).getAsString()) : Double.NaN;
            return value < 0 ? Double.NaN : value;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Short price line for lists, e.g. "≈ $0.09/h", "measured ≈ $0.20/h" or "free, daily limit". */
    public static String priceLine(Model m) {
        if (m.free()) return "free · daily request limit";
        Double measured = TESTED.get(m.id());
        if (measured != null && LocalConfig.get().isOpenRouter()) {
            return "measured ≈ $" + String.format(Locale.ROOT, "%.2f", measured) + "/h";
        }
        double hourly = m.dollarsPerHour();
        if (Double.isNaN(hourly)) return LocalConfig.get().isOpenRouter() ? "price unknown" : "runs on your endpoint";
        return "≈ $" + String.format(Locale.ROOT, hourly < 1 ? "%.2f" : "%.1f", hourly) + "/h";
    }

    /** Matches every word of the query against id and name. */
    public static boolean matches(Model m, String query) {
        String hay = (m.id() + " " + m.name()).toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!word.isEmpty() && !hay.contains(word)) return false;
        }
        return true;
    }
}
