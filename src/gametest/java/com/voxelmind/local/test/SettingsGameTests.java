package com.voxelmind.local.test;

import com.voxelmind.local.config.LocalConfig;
import com.voxelmind.local.provider.ModelCatalog;
import com.voxelmind.local.mind.LlmClient;
import com.voxelmind.local.provider.OpenRouterAccount;
import com.voxelmind.local.provider.Player2;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.List;

/** Settings logic without a world: model switch, endpoint switch, catalog parsing and cost estimate. */
public class SettingsGameTests {
    private static final String OPENROUTER_MODELS = """
            {"data":[
              {"id":"deepseek/deepseek-v4-flash-0731","name":"DeepSeek V4 Flash","context_length":163840,
               "pricing":{"prompt":"0.000000006","completion":"0.00000128"},"supported_parameters":["tools","tool_choice"],
               "architecture":{"output_modalities":["text"]}},
              {"id":"acme/talker","name":"Talker","context_length":8000,
               "pricing":{"prompt":"0.0000001","completion":"0.0000002"},"supported_parameters":["temperature"],
               "architecture":{"output_modalities":["text"]}},
              {"id":"acme/painter","name":"Painter","pricing":{"prompt":"0.000001","completion":"0.00001"},
               "architecture":{"output_modalities":["image"]}},
              {"id":"acme/free:free","name":"Free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"]},
              {"id":"acme/talker:batch","name":"Talker (batch)","pricing":{"prompt":"0.00000005","completion":"0.0000001"},"supported_parameters":["tools"]},
              {"id":"openrouter/auto","name":"Auto Router","pricing":{"prompt":"-1","completion":"-1"},"supported_parameters":["tools"]}
            ]}""";
    private static final String OLLAMA_MODELS = """
            {"object":"list","data":[{"id":"qwen3:8b","object":"model","owned_by":"library"}]}""";

    @GameTest
    public void otherModelDropsMeasuredProviderOrder(GameTestHelper helper) {
        LocalConfig config = new LocalConfig();
        config.memoryModel = "old/memory-model";
        config.selectModel("anthropic/claude-haiku-5.5");
        helper.assertTrue(config.model.equals("anthropic/claude-haiku-5.5"), "model set");
        helper.assertTrue(config.effectiveMemoryModel().equals("anthropic/claude-haiku-5.5"), "memory follows the model");
        helper.assertTrue(config.providerOrder.isEmpty(), "provider order only fits the default model");
        config.selectModel(LocalConfig.DEFAULT_MODEL);
        helper.assertTrue(config.providerOrder.equals(LocalConfig.DEFAULT_PROVIDER_ORDER), "default model gets its measured order back");
        helper.succeed();
    }

    @GameTest
    public void newConfigStartsOnSparksAndKeysOnlyReachTheirHosts(GameTestHelper helper) {
        LocalConfig config = new LocalConfig();
        helper.assertTrue(config.isVoxelMind() && !config.hasCredentials(), "a new config starts on VoxelMind Sparks and waits for the sign-in");
        config.selectEndpoint(LocalConfig.OPENROUTER_URL);
        config.setKey("sk-or-v1-secret");
        config.selectEndpoint("https://openrouter.ai.example.net/api/v1");
        helper.assertFalse(config.isOpenRouter(), "a lookalike host is not OpenRouter");
        helper.assertTrue(config.effectiveApiKey().isEmpty(), "the OpenRouter key never goes to a lookalike host");
        config.selectEndpoint("https://api.voxel-mind.com.example.net/v1");
        helper.assertFalse(config.isVoxelMind(), "a lookalike host is not the VoxelMind API");
        config.selectEndpoint("https://api.player2.game.example.net/v1");
        helper.assertFalse(config.isPlayer2(), "a lookalike host is not Player2");
        helper.assertTrue(LocalConfig.isHost("https://OpenRouter.ai/api/v1", "openrouter.ai"), "hosts compare without case");
        helper.assertFalse(LocalConfig.isHost("http://openrouter.ai/api/v1", "openrouter.ai"), "only https gets a stored key");
        helper.assertTrue(LocalConfig.DEFAULT_PROVIDER_ORDER.equals(java.util.List.of("Relace", "Sail Research", "Together")),
                "the default model is routed to the zero-data-retention providers the VoxelMind API uses");
        helper.succeed();
    }

    @GameTest
    public void keysStayWithTheirEndpoint(GameTestHelper helper) {
        LocalConfig config = new LocalConfig();
        config.selectEndpoint(LocalConfig.OPENROUTER_URL);
        config.setKey("sk-or-v1-test");
        config.selectEndpoint("http://localhost:11434/v1/");
        helper.assertTrue(config.baseUrl.equals("http://localhost:11434/v1"), "trailing slash removed");
        helper.assertTrue(config.effectiveApiKey().isEmpty(), "the OpenRouter key is never sent to another endpoint");
        helper.assertTrue(config.hasCredentials(), "local endpoints need no key");
        helper.assertTrue(config.providerOrder.isEmpty(), "no OpenRouter routing on Ollama");
        helper.assertTrue(config.effectiveTimeoutSeconds() >= 120, "local models get the longer timeout");
        config.selectEndpoint(LocalConfig.OPENROUTER_URL);
        helper.assertTrue(config.effectiveApiKey().equals("sk-or-v1-test"), "the OpenRouter key is still there");
        helper.assertTrue(config.providerOrder.equals(LocalConfig.DEFAULT_PROVIDER_ORDER), "measured order restored for the default model");
        helper.succeed();
    }

    @GameTest
    public void joulesNoticeExplainsAndLinks(GameTestHelper helper) {
        var notice = com.voxelmind.local.command.VmlCommand.joulesNotice("Lysa");
        String text = notice.getString();
        helper.assertTrue(text.contains("Lysa") && text.contains("joules") && text.contains("recharges daily"), "notice: " + text);
        boolean linksToPlayer2 = notice.toFlatList().stream().anyMatch(part ->
                part.getStyle().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.OpenUrl url
                        && url.uri().toString().equals(Player2.PROFILE_URL));
        boolean linksToSettings = notice.toFlatList().stream().anyMatch(part ->
                part.getStyle().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand run
                        && run.command().equals("/vml setup"));
        helper.assertTrue(linksToPlayer2 && linksToSettings, "links to Player2 and to the settings");
        helper.assertFalse(LlmClient.outOfJoules(new LlmClient.LlmException(402, "HTTP 402")) && !LocalConfig.get().isPlayer2(),
                "402 counts as joules only on Player2");
        helper.succeed();
    }

    @GameTest
    public void player2KeepsItsOwnKeyAndModel(GameTestHelper helper) {
        LocalConfig config = new LocalConfig();
        config.selectEndpoint(LocalConfig.OPENROUTER_URL);
        config.setKey("sk-or-v1-openrouter");
        config.selectEndpoint(Player2.WEB);
        helper.assertTrue(config.isPlayer2() && !config.isOpenRouter(), "Player2 endpoint");
        helper.assertFalse(config.hasCredentials(), "Player2 needs a sign-in");
        helper.assertTrue(config.effectiveApiKey().isEmpty(), "the OpenRouter key never goes to Player2");
        config.setKey("p2-key");
        helper.assertTrue(config.player2Key.equals("p2-key") && config.apiKey.equals("sk-or-v1-openrouter"), "keys stay apart");
        helper.assertTrue(config.hasCredentials() && config.modelLabel().equals("Player2"), "signed in, model chosen by Player2");
        helper.assertTrue(config.providerOrder.isEmpty(), "no OpenRouter routing for Player2");
        helper.succeed();
    }

    /** Talks to the real Player2 API: a wrong key is refused with 401, a wrong client id is reported. */
    @GameTest
    public void player2ApiRefusesUnknownCallers(GameTestHelper helper) {
        var probe = LlmClient.probe(Player2.WEB, "not-a-real-key", "ignored");
        LocalConfig config = LocalConfig.get();
        String before = config.player2ClientId;
        config.player2ClientId = "voxelmind-test-invalid";
        var login = Player2.startDeviceLogin();
        config.player2ClientId = before;
        // The game test server ticks faster than real time; wait on the clock (device login takes 20-30 s for unknown ids).
        try {
            java.util.concurrent.CompletableFuture.allOf(probe.exceptionally(e -> null), login.exceptionally(e -> null))
                    .get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            helper.fail("Player2 did not answer within 60 s: " + e);
        }
        helper.succeedIf(() -> {
            Throwable chat = probe.handle((r, e) -> e).join();
            Throwable device = login.handle((r, e) -> e).join();
            if (chat instanceof java.util.concurrent.CompletionException) chat = chat.getCause();
            if (device instanceof java.util.concurrent.CompletionException) device = device.getCause();
            helper.assertTrue(chat instanceof LlmClient.LlmException le && le.status == 401, "chat: expected 401, got " + chat);
            helper.assertTrue(device != null && String.valueOf(device.getMessage()).contains("invalid client_id"), "device login: " + device);
        });
    }

    @GameTest
    public void voxelmindKeepsItsOwnTokenAndModel(GameTestHelper helper) {
        LocalConfig config = new LocalConfig();
        config.selectEndpoint(LocalConfig.OPENROUTER_URL);
        config.setKey("sk-or-v1-openrouter");
        config.selectEndpoint(com.voxelmind.local.provider.VoxelMindApi.BASE);
        helper.assertTrue(config.isVoxelMind() && !config.isOpenRouter() && !config.isPlayer2(), "VoxelMind endpoint");
        helper.assertFalse(config.hasCredentials(), "VoxelMind needs a sign-in");
        helper.assertTrue(config.effectiveApiKey().isEmpty(), "the OpenRouter key never goes to the VoxelMind API");
        config.setKey("vm_token");
        helper.assertTrue(config.voxelmindKey.equals("vm_token") && config.apiKey.equals("sk-or-v1-openrouter"), "keys stay apart");
        helper.assertTrue(config.hasCredentials() && config.modelLabel().equals("VoxelMind Sparks"), "signed in, our model");
        helper.assertTrue(config.providerOrder.isEmpty(), "routing happens in the API");
        helper.assertTrue(com.voxelmind.local.provider.VoxelMindApi.buyUrl("Steve_1").equals("https://voxel-mind.com/sparks?name=Steve_1"), "shop link");
        var notice = com.voxelmind.local.command.VmlCommand.sparksNotice("Lysa", "Steve");
        boolean linksToShop = notice.toFlatList().stream().anyMatch(part ->
                part.getStyle().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.OpenUrl url
                        && url.uri().toString().endsWith("/sparks?name=Steve"));
        helper.assertTrue(notice.getString().contains("the Sparks of Steve are used up") && linksToShop, "notice: " + notice.getString());
        helper.succeed();
    }

    /** With -Pvoxelmind.api=… and VML_E2E_VOXELMIND_TOKEN: one real tool call through the VoxelMind API. */
    @GameTest
    public void voxelmindApiAnswersWithTools(GameTestHelper helper) {
        String token = System.getenv("VML_E2E_VOXELMIND_TOKEN");
        if (token == null || token.isBlank()) {
            helper.succeed();
            return;
        }
        var probe = LlmClient.probe(com.voxelmind.local.provider.VoxelMindApi.BASE, token.trim(), "ignored/model");
        var balance = com.voxelmind.local.provider.VoxelMindApi.balance(token.trim());
        try {
            java.util.concurrent.CompletableFuture.allOf(probe, balance).get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            helper.fail("VoxelMind API: " + e);
            return;
        }
        helper.assertTrue(probe.join().toolCalls().stream().anyMatch(c -> c.name().equals("wave")), "the model waved through the API");
        helper.assertTrue(balance.join().total() > 0, "balance " + balance.join());
        helper.succeed();
    }

    @GameTest
    public void signInCallbackChecksStateAndDenial(GameTestHelper helper) throws Exception {
        OpenRouterAccount.Login login = OpenRouterAccount.startLocalLogin();
        String query = login.url().getRawQuery();
        helper.assertTrue(query.contains("code_challenge_method=S256") && query.contains("callback_url=http%3A%2F%2Flocalhost%3A"),
                "PKCE sign-in url " + query);
        String callback = java.net.URLDecoder.decode(query.replaceAll(".*callback_url=([^&]+).*", "$1"), java.nio.charset.StandardCharsets.UTF_8);
        var http = java.net.http.HttpClient.newHttpClient();
        var forged = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(callback + "?code=abc&state=wrong")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        helper.assertTrue(forged.statusCode() == 400, "a wrong state is refused, got " + forged.statusCode());
        helper.assertFalse(login.key().isDone(), "a wrong state does not end the sign-in");
        http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(callback + "?error=access_denied")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        boolean denied = false;
        try {
            login.key().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            denied = true;
        }
        helper.assertTrue(denied, "a denial ends the sign-in");
        helper.succeed();
    }

    @GameTest
    public void catalogReadsOpenRouterAndOllama(GameTestHelper helper) {
        // The price lines read the game's own config: measured costs are OpenRouter's.
        String endpointBefore = LocalConfig.get().baseUrl;
        LocalConfig.get().baseUrl = LocalConfig.OPENROUTER_URL;
        try {
            catalogChecks(helper);
        } finally {
            LocalConfig.get().baseUrl = endpointBefore;
        }
    }

    private void catalogChecks(GameTestHelper helper) {
        List<ModelCatalog.Model> models = ModelCatalog.parse(OPENROUTER_MODELS);
        helper.assertTrue(models.size() == 4, "image-only and batch models left out, got " + models.size());
        ModelCatalog.Model router = models.stream().filter(m -> m.id().equals("openrouter/auto")).findFirst().orElseThrow();
        helper.assertFalse(router.priced(), "router price -1 counts as unknown");
        helper.assertTrue(models.getFirst().id().equals(LocalConfig.DEFAULT_MODEL), "tested model first");
        ModelCatalog.Model flash = models.getFirst();
        helper.assertTrue(flash.tools() && flash.tested(), "tools and tested flag");
        double hourly = flash.dollarsPerHour();
        double expected = 530 * (8300 * 0.000000006 + 100 * 0.00000128);
        helper.assertTrue(Math.abs(hourly - expected) < 1e-9, "hourly estimate " + hourly);
        helper.assertTrue(ModelCatalog.priceLine(flash).equals("measured ≈ $0.20/h"), "price line " + ModelCatalog.priceLine(flash));
        ModelCatalog.Model untested = models.stream().filter(m -> m.id().equals("acme/talker")).findFirst().orElseThrow();
        helper.assertTrue(ModelCatalog.priceLine(untested).equals("≈ $0.45/h"), "list-price line " + ModelCatalog.priceLine(untested));
        ModelCatalog.Model talker = models.stream().filter(m -> m.id().equals("acme/talker")).findFirst().orElseThrow();
        helper.assertFalse(talker.tools(), "no tools listed");
        ModelCatalog.Model free = models.stream().filter(m -> m.id().equals("acme/free:free")).findFirst().orElseThrow();
        helper.assertTrue(free.free(), "free model");

        List<ModelCatalog.Model> local = ModelCatalog.parse(OLLAMA_MODELS);
        helper.assertTrue(local.size() == 1 && local.getFirst().id().equals("qwen3:8b"), "ollama list");
        helper.assertTrue(local.getFirst().tools() && !local.getFirst().priced(), "unknown tools count as possible, no price");
        helper.assertTrue(ModelCatalog.matches(flash, "deep flash") && !ModelCatalog.matches(flash, "haiku"), "search");
        helper.succeed();
    }

    /**
     * LM Studio refuses response_format json_object ("'response_format.type' must be 'json_schema'"); the memory rewrite
     * used to fail on every try there. The request is repeated without it, and later ones leave it out at once.
     */
    @GameTest
    public void anEndpointThatRefusesResponseFormatIsAskedWithoutIt(GameTestHelper helper) throws java.io.IOException {
        List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer stub = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            bodies.add(body);
            boolean withFormat = body.contains("\"response_format\"");
            String reply = withFormat ? "{\"error\":\"'response_format.type' must be 'json_schema'\"}"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"previously\\\":\\\"we built a hut\\\"}\"}}]}";
            byte[] bytes = reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(withFormat ? 400 : 200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        stub.start();
        try {
            var endpoint = new LlmClient.Endpoint("http://127.0.0.1:" + stub.getAddress().getPort() + "/v1", "", 20);
            com.google.gson.JsonObject format = new com.google.gson.JsonObject();
            format.addProperty("type", "json_object");
            com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
            com.google.gson.JsonObject user = new com.google.gson.JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", "Answer with JSON.");
            messages.add(user);
            LlmClient.Response first = LlmClient.chatAt(endpoint, "openai/gpt-oss-20b", messages, format).get(20, java.util.concurrent.TimeUnit.SECONDS);
            helper.assertTrue(first.content().contains("we built a hut"), "the answer arrives: " + first.content());
            helper.assertTrue(bodies.size() == 2 && !bodies.get(1).contains("response_format"), "asked again without response_format, requests " + bodies.size());
            LlmClient.chatAt(endpoint, "openai/gpt-oss-20b", messages, format).get(20, java.util.concurrent.TimeUnit.SECONDS);
            helper.assertTrue(bodies.size() == 3 && !bodies.get(2).contains("response_format"), "the next request leaves it out at once, requests " + bodies.size());
            helper.assertFalse(LlmClient.refusesResponseFormat(new LlmClient.LlmException(400, "HTTP 400: tool_choice is not supported")),
                    "another 400 is not about response_format");
            helper.succeed();
        } catch (Exception e) {
            helper.fail("response_format fallback: " + e);
        } finally {
            stub.stop(0);
        }
    }
}
