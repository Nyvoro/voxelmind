package com.voxelmind.local.mind;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** OpenAI-compatible chat completions client (OpenRouter, Ollama, LM Studio, ...). */
public final class LlmClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Deque<Long> RECENT = new ArrayDeque<>();
    /** Models whose endpoint refused reasoning switched off; they get the lowest effort instead. */
    private static final java.util.Set<String> REASONING_REQUIRED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Models whose endpoint refused tool_choice "required" (e.g. Anthropic with reasoning); they get "auto". */
    private static final java.util.Set<String> TOOL_CHOICE_AUTO = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Where a request goes: base URL and the key for it. */
    public record Endpoint(String baseUrl, String key, int timeoutSeconds) {
        static Endpoint configured() {
            LocalConfig config = LocalConfig.get();
            return new Endpoint(config.baseUrl, config.effectiveApiKey(), config.effectiveTimeoutSeconds());
        }

        boolean openRouter() {
            return LocalConfig.isHost(baseUrl, "openrouter.ai");
        }

        boolean player2() {
            return com.voxelmind.local.provider.Player2.isPlayer2(baseUrl);
        }

        boolean voxelmind() {
            return com.voxelmind.local.provider.VoxelMindApi.isVoxelMind(baseUrl);
        }
    }

    /** One tool call as the model sent it. {@code arguments} is null when the JSON could not be parsed. */
    public record ToolCall(String id, String name, @Nullable JsonObject arguments, String rawArguments) {}

    public record Response(String content, List<ToolCall> toolCalls, int promptTokens, int completionTokens,
                           double cost, long latencyMs,
                           int cachedTokens, @Nullable String provider) {}

    private LlmClient() {}

    /** Technical request budget shared by every companion on this server. */
    public static synchronized boolean tryAcquireBudget() {
        long now = System.currentTimeMillis();
        while (!RECENT.isEmpty() && now - RECENT.peekFirst() > 60_000) RECENT.pollFirst();
        if (RECENT.size() >= Math.max(1, LocalConfig.get().maxRequestsPerMinute)) return false;
        RECENT.addLast(now);
        return true;
    }

    /** The last failed decision request, for the menu; cleared by the next success. Passive status only. */
    public record Failure(int status, String explained, boolean player2, long at) {}

    private static volatile @Nullable Failure lastFailure;

    public static @Nullable Failure lastFailure() {
        return lastFailure;
    }

    public static CompletableFuture<Response> chat(String model, JsonArray messages, @Nullable JsonArray tools,
                                                   boolean toolRequired, double temperature, int maxTokens,
                                                   @Nullable JsonObject responseFormat) {
        return chat(model, messages, tools, toolRequired, temperature, maxTokens, responseFormat, null);
    }

    /**
     * {@code reasoning}: null switches the model's reasoning off (the companion's decisions, for latency);
     * "on" or an effort like "low" lets it think before answering, as the hosted God and Builder did.
     */
    public static CompletableFuture<Response> chat(String model, JsonArray messages, @Nullable JsonArray tools,
                                                   boolean toolRequired, double temperature, int maxTokens,
                                                   @Nullable JsonObject responseFormat, @Nullable String reasoning) {
        LocalConfig config = LocalConfig.get();
        // The measured provider order was measured with reasoning off; a thinking request lets OpenRouter route.
        List<String> providers = config.isOpenRouter() && config.providerOrder != null && reasoning == null ? config.providerOrder : List.of();
        boolean player2 = config.isPlayer2();
        return attempt(Endpoint.configured(), model, messages, tools, toolRequired, temperature, maxTokens, responseFormat, reasoning, providers, 0)
                .whenComplete((response, error) -> {
                    if (error == null) {
                        lastFailure = null;
                        com.voxelmind.local.provider.UsageStats.used();
                        return;
                    }
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    int status = cause instanceof LlmException e ? e.status : 0;
                    lastFailure = new Failure(status, explain(cause), player2, System.currentTimeMillis());
                });
    }

    /** Player2 answered that the player's joules are used up. */
    public static boolean outOfJoules(Throwable error) {
        return error instanceof LlmException e && e.status == 402 && LocalConfig.get().isPlayer2();
    }

    /** The VoxelMind API answered that the player's Sparks are used up. */
    public static boolean outOfSparks(Throwable error) {
        return error instanceof LlmException e && e.status == 402 && LocalConfig.get().isVoxelMind();
    }

    /** The prepaid AI power of the chosen service is used up (Player2 joules or VoxelMind Sparks). */
    public static boolean outOfPrepaid(Throwable error) {
        return outOfJoules(error) || outOfSparks(error);
    }

    /**
     * One small request with a single tool to a not yet saved endpoint, so the settings screen can show
     * whether key, model and tool calls work before anything is stored.
     */
    public static CompletableFuture<Response> probe(String baseUrl, String key, String model) {
        JsonObject param = new JsonObject();
        param.addProperty("type", "object");
        param.add("properties", new JsonObject());
        JsonObject function = new JsonObject();
        function.addProperty("name", "wave");
        function.addProperty("description", "Wave at the player.");
        function.add("parameters", param);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        JsonArray tools = new JsonArray();
        tools.add(tool);
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "A player says hello. Wave at them with the wave tool.");
        messages.add(user);
        boolean hosted = LocalConfig.isHost(baseUrl, "openrouter.ai") || com.voxelmind.local.provider.Player2.isPlayer2(baseUrl)
                || com.voxelmind.local.provider.VoxelMindApi.isVoxelMind(baseUrl);
        LocalConfig config = LocalConfig.get();
        Endpoint endpoint = new Endpoint(baseUrl, key, hosted ? Math.max(40, config.requestTimeoutSeconds) : config.localRequestTimeoutSeconds);
        return attempt(endpoint, model, messages, tools, true, 0.2, 256, null, null, List.of(), 0);
    }

    /** Tries one provider at a time; only a provider failure moves on to the next one. */
    private static CompletableFuture<Response> attempt(Endpoint endpoint, String model, JsonArray messages, @Nullable JsonArray tools,
                                                       boolean toolRequired, double temperature, int maxTokens,
                                                       @Nullable JsonObject responseFormat, @Nullable String reasoning, List<String> providers, int index) {
        String provider = index < providers.size() ? providers.get(index) : null;
        return send(endpoint, model, messages, tools, toolRequired, temperature, maxTokens, responseFormat, reasoning, provider)
                .handle((response, error) -> {
                    if (error == null) return CompletableFuture.completedFuture(response);
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    if (endpoint.openRouter() && !REASONING_REQUIRED.contains(model) && refusesReasoningOff(cause)) {
                        REASONING_REQUIRED.add(model);
                        return attempt(endpoint, model, messages, tools, toolRequired, temperature, maxTokens, responseFormat, reasoning, providers, index);
                    }
                    if (toolRequired && !TOOL_CHOICE_AUTO.contains(model) && refusesRequiredToolChoice(cause)) {
                        TOOL_CHOICE_AUTO.add(model);
                        return attempt(endpoint, model, messages, tools, toolRequired, temperature, maxTokens, responseFormat, reasoning, providers, index);
                    }
                    if (index + 1 < providers.size()) {
                        return attempt(endpoint, model, messages, tools, toolRequired, temperature, maxTokens, responseFormat, reasoning, providers, index + 1);
                    }
                    return CompletableFuture.<Response>failedFuture(error);
                })
                .thenCompose(f -> f);
    }

    private static boolean refusesRequiredToolChoice(Throwable error) {
        if (!(error instanceof LlmException e) || e.status != 400 || e.getMessage() == null) return false;
        String text = e.getMessage().toLowerCase(java.util.Locale.ROOT);
        return text.contains("tool_choice") && text.contains("not supported");
    }

    private static boolean refusesReasoningOff(Throwable error) {
        if (!(error instanceof LlmException e) || e.status != 400 || e.getMessage() == null) return false;
        String text = e.getMessage().toLowerCase(java.util.Locale.ROOT);
        return text.contains("reasoning") && (text.contains("mandatory") || text.contains("cannot be disabled") || text.contains("required"));
    }

    private static CompletableFuture<Response> send(Endpoint endpoint, String model, JsonArray messages, @Nullable JsonArray tools,
                                                    boolean toolRequired, double temperature, int maxTokens,
                                                    @Nullable JsonObject responseFormat, @Nullable String reasoning, @Nullable String provider) {
        JsonObject body = new JsonObject();
        // Player2 chooses the model from the player's account; its API has no model field.
        // The VoxelMind API has one model and picks the provider itself.
        if (endpoint.voxelmind()) body.addProperty("model", com.voxelmind.local.provider.VoxelMindApi.MODEL);
        else if (!endpoint.player2()) body.addProperty("model", model);
        body.add("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools);
            body.addProperty("tool_choice", toolRequired && !TOOL_CHOICE_AUTO.contains(model) ? "required" : "auto");
        }
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);
        if (responseFormat != null) body.add("response_format", responseFormat);
        if (endpoint.openRouter() || endpoint.voxelmind()) {
            JsonObject thinking = new JsonObject();
            if (reasoning != null) {
                thinking.addProperty("enabled", true);
                if (!reasoning.equals("on")) thinking.addProperty("effort", reasoning);
            } else if (REASONING_REQUIRED.contains(model)) {
                thinking.addProperty("effort", "low");
            } else {
                thinking.addProperty("enabled", false);
            }
            body.add("reasoning", thinking);
        }
        if (endpoint.openRouter()) {
            JsonObject usage = new JsonObject();
            usage.addProperty("include", true);
            body.add("usage", usage);
            if (provider != null && !provider.isBlank()) {
                JsonObject routing = new JsonObject();
                JsonArray order = new JsonArray();
                order.add(provider);
                routing.add("order", order);
                routing.addProperty("allow_fallbacks", false);
                body.add("provider", routing);
            }
        }

        String base = endpoint.baseUrl().endsWith("/") ? endpoint.baseUrl().substring(0, endpoint.baseUrl().length() - 1) : endpoint.baseUrl();
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new LlmException(0, "not a valid URL: " + base));
        }
        request.timeout(Duration.ofSeconds(Math.max(5, endpoint.timeoutSeconds())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!endpoint.key().isEmpty()) request.header("Authorization", "Bearer " + endpoint.key());
        if (endpoint.player2()) request.header("player2-game-key", com.voxelmind.local.provider.Player2.gameKey());
        if (endpoint.openRouter()) {
            request.header("HTTP-Referer", "https://voxel-mind.com");
            request.header("X-Title", "VoxelMind Local");
        }

        long started = System.currentTimeMillis();
        return HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parse(response, System.currentTimeMillis() - started));
    }

    private static Response parse(HttpResponse<String> response, long latencyMs) {
        if (response.statusCode() / 100 != 2) {
            throw new LlmException(response.statusCode(), "HTTP " + response.statusCode() + ": " + errorText(response.body()));
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (root.has("error")) throw new LlmException(errorCode(root), errorText(response.body()));
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) throw new LlmException(0, "no choices in response");
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        String content = message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "";
        List<ToolCall> calls = new ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                JsonObject call = element.getAsJsonObject();
                JsonObject function = call.getAsJsonObject("function");
                if (function == null || !function.has("name")) continue;
                String raw = function.has("arguments") && !function.get("arguments").isJsonNull()
                        ? function.get("arguments").getAsString() : "{}";
                JsonObject args;
                try {
                    JsonElement parsed = JsonParser.parseString(raw.isBlank() ? "{}" : raw);
                    args = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
                } catch (Exception e) {
                    args = null;
                }
                String id = call.has("id") && !call.get("id").isJsonNull() ? call.get("id").getAsString() : "call_" + calls.size();
                calls.add(new ToolCall(id, function.get("name").getAsString(), args, raw));
            }
        }
        int promptTokens = 0;
        int completionTokens = 0;
        int cachedTokens = 0;
        double cost = 0;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usage");
            if (usage.has("prompt_tokens")) promptTokens = usage.get("prompt_tokens").getAsInt();
            if (usage.has("completion_tokens")) completionTokens = usage.get("completion_tokens").getAsInt();
            if (usage.has("cost") && !usage.get("cost").isJsonNull()) cost = usage.get("cost").getAsDouble();
            if (usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject()) {
                JsonObject details = usage.getAsJsonObject("prompt_tokens_details");
                if (details.has("cached_tokens") && !details.get("cached_tokens").isJsonNull()) cachedTokens = details.get("cached_tokens").getAsInt();
            }
        }
        String provider = root.has("provider") && root.get("provider").isJsonPrimitive() ? root.get("provider").getAsString() : null;
        return new Response(content, calls, promptTokens, completionTokens, cost, latencyMs, cachedTokens, provider);
    }

    /** The endpoint's error with a plain hint for the usual BYOK problems. */
    public static String explain(Throwable error) {
        String message = error.getMessage() != null ? error.getMessage() : error.toString();
        String hint = "";
        boolean player2 = LocalConfig.get().isPlayer2();
        boolean voxelmind = LocalConfig.get().isVoxelMind();
        if (error instanceof LlmException e) {
            hint = switch (e.status) {
                case 401 -> voxelmind ? "The VoxelMind sign-in is no longer valid. Sign in with Minecraft again in the settings."
                        : player2 ? "Player2 no longer accepts the sign-in. Connect Player2 again in the settings."
                        : "The key was not accepted. Sign in again or paste a new key.";
                case 402 -> voxelmind ? "The Sparks are used up; new free Sparks come tomorrow. Packs and prices: " + com.voxelmind.local.provider.VoxelMindApi.buyUrl(null) + "."
                        : player2 ? "The Player2 AI power (joules) is used up for now; it recharges daily, or see " + com.voxelmind.local.provider.Player2.PROFILE_URL + "."
                        : "The credit on this key is used up. Top up at openrouter.ai/settings/credits or pick a cheaper model.";
                case 404 -> "The endpoint does not offer this model. Pick another one.";
                case 429 -> "The provider is rate limiting; it will retry by itself.";
                default -> "";
            };
        } else if (error instanceof java.net.ConnectException) {
            hint = "Nothing answers at " + LocalConfig.get().baseUrl + ". Is the server (e.g. Ollama) running?";
        } else if (error instanceof java.net.http.HttpTimeoutException) {
            hint = "The model took longer than " + LocalConfig.get().effectiveTimeoutSeconds() + " s to answer.";
        }
        return hint.isEmpty() ? message : message + ". " + hint;
    }

    private static String errorText(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("error") && root.get("error").isJsonPrimitive()) {
                // Player2 style: {"error": "Unauthorized", "error_description": "..."}
                String text = root.get("error").getAsString();
                if (root.has("error_description")) text += ": " + root.get("error_description").getAsString();
                return text;
            }
            if (root.has("error")) {
                JsonElement error = root.get("error");
                if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                    JsonObject e = error.getAsJsonObject();
                    String message = e.get("message").getAsString();
                    // OpenRouter wraps the provider's own reason in metadata.raw ("Provider returned error").
                    if (e.has("metadata") && e.get("metadata").isJsonObject() && e.getAsJsonObject("metadata").has("raw")) {
                        String raw = e.getAsJsonObject("metadata").get("raw").toString();
                        message += " (" + (raw.length() > 400 ? raw.substring(0, 400) + "…" : raw) + ")";
                    }
                    return message;
                }
                return error.toString();
            }
        } catch (Exception ignored) {
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private static int errorCode(JsonObject root) {
        JsonElement error = root.get("error");
        if (error.isJsonObject() && error.getAsJsonObject().has("code") && error.getAsJsonObject().get("code").isJsonPrimitive()) {
            try {
                return error.getAsJsonObject().get("code").getAsInt();
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    public static final class LlmException extends RuntimeException {
        /** HTTP status or the error code inside a 200 body; 0 when neither says. */
        public final int status;

        public LlmException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
