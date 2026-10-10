package com.voxelmind.local.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.voxelmind.local.action.ActionContext;
import com.voxelmind.local.action.BodyAction;
import com.voxelmind.local.body.CompanionConnection;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.companion.CompanionManager;
import com.voxelmind.local.config.LocalConfig;
import com.voxelmind.local.mind.PromptBuilder;
import com.voxelmind.local.mind.Timeline;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The companion's mind against a stub OpenAI-compatible endpoint: one decision call per wake that both speaks and acts.
 * Also the conversation window of its prompt.
 */
public class MindGameTests {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    /**
     * Part of every name, so a companion file or decision log left behind by an earlier run (the test world is kept)
     * cannot block a summon or add entries to a log in this one.
     */
    private static final String RUN = String.format("%05d", ThreadLocalRandom.current().nextInt(100_000));
    /**
     * The mind decides on the wall clock (debounce, HTTP) while the test server ticks as fast as it can. The deadline is
     * longer than the 60 s the Player2 test may block the server thread.
     */
    private static final long DEADLINE_MS = 120_000;
    private static final int MAX_TICKS = 100_000;
    private static final String EMPTY = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}";

    /**
     * Canned answers by companion name, one per request in order (the last one repeats); other callers (minds of tests
     * running at the same time) get an empty message.
     */
    private static final Map<String, List<String>> ANSWERS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> REQUESTS = new ConcurrentHashMap<>();
    /** The user message of each request by companion name, in order. */
    private static final Map<String, List<String>> PROMPTS = new ConcurrentHashMap<>();
    /** Answers the stub holds back until the test releases them, by "name#request number". */
    private static final Map<String, CountDownLatch> HELD = new ConcurrentHashMap<>();
    private static HttpServer stub;
    private static ExecutorService stubThreads;
    private static int stubUsers;
    private static String baseUrlBefore = "";
    private static int budgetBefore;

    // ---- stub endpoint ----

    /** LocalConfig is global: it points at the stub while any test of this class needs it, and the last one restores it. */
    private static void useStub() throws IOException {
        if (stubUsers++ > 0) return;
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", MindGameTests::answer);
        // A held answer must not keep the other tests' requests waiting.
        stubThreads = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "vml-test-stub");
            t.setDaemon(true);
            return t;
        });
        stub.setExecutor(stubThreads);
        stub.start();
        LocalConfig config = LocalConfig.get();
        baseUrlBefore = config.baseUrl;
        budgetBefore = config.maxRequestsPerMinute;
        config.baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
        config.maxRequestsPerMinute = 1000;
    }

    private static void releaseStub() {
        if (--stubUsers > 0) return;
        LocalConfig config = LocalConfig.get();
        config.baseUrl = baseUrlBefore;
        config.maxRequestsPerMinute = budgetBefore;
        HELD.values().forEach(CountDownLatch::countDown);
        stub.stop(0);
        stubThreads.shutdown();
        stub = null;
    }

    private static void answer(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String reply = EMPTY;
        String user = "";
        try {
            JsonArray messages = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages");
            user = messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
        } catch (RuntimeException ignored) {
        }
        for (Map.Entry<String, List<String>> e : ANSWERS.entrySet()) {
            if (!user.contains("You are " + e.getKey() + ".")) continue;
            int n = REQUESTS.computeIfAbsent(e.getKey(), k -> new AtomicInteger()).incrementAndGet();
            PROMPTS.computeIfAbsent(e.getKey(), k -> new CopyOnWriteArrayList<>()).add(user);
            reply = e.getValue().get(Math.min(n, e.getValue().size()) - 1);
            CountDownLatch held = HELD.get(e.getKey() + "#" + n);
            if (held != null) {
                try {
                    held.await(DEADLINE_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The stub keeps the answer to this request of the companion until the returned latch is released. */
    private static CountDownLatch hold(Companion c, int request) {
        CountDownLatch latch = new CountDownLatch(1);
        HELD.put(c.name() + "#" + request, latch);
        return latch;
    }

    /** The user message of the companion's n-th request, or "". */
    private static String prompt(Companion c, int n) {
        List<String> prompts = PROMPTS.getOrDefault(c.name(), List.of());
        return prompts.size() >= n ? prompts.get(n - 1) : "";
    }

    private static JsonObject call(String name, String... pairs) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) args.addProperty(pairs[i], pairs[i + 1]);
        return call(name, args);
    }

    private static JsonObject call(String name, JsonObject args) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("arguments", args.toString());
        JsonObject call = new JsonObject();
        call.addProperty("id", "call_" + COUNTER.incrementAndGet());
        call.addProperty("type", "function");
        call.add("function", function);
        return call;
    }

    /** A chat completion whose message holds exactly these tool calls, in this order. */
    private static String completion(JsonObject... calls) {
        JsonArray toolCalls = new JsonArray();
        for (JsonObject call : calls) toolCalls.add(call);
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.add("content", JsonNull.INSTANCE);
        message.add("tool_calls", toolCalls);
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("message", message);
        choice.addProperty("finish_reason", "tool_calls");
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        return root.toString();
    }

    // ---- world ----

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    /** A human owner standing in the test area. */
    private static ServerPlayer owner(GameTestHelper helper, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Owner" + RUN + COUNTER.incrementAndGet());
        ServerPlayer p = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        BlockPos abs = helper.absolutePos(rel);
        p.snapTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0f, 0f);
        server.getPlayerList().placeNewPlayer(new CompanionConnection(), p, CommonListenerCookie.createInitial(profile, false));
        p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        p.setGameMode(GameType.CREATIVE);
        return p;
    }

    /** A new companion next to its owner; the stub gives these answers to its decisions in order, the last one repeating. */
    private static Companion summon(GameTestHelper helper, ServerPlayer owner, String... answers) {
        String name = "Mind" + RUN + "_" + COUNTER.incrementAndGet();
        ANSWERS.put(name, List.of(answers));
        String error = CompanionManager.get().summon(owner, name, null);
        helper.assertTrue(error == null, "summon: " + error);
        Companion c = CompanionManager.get().find(owner.getUUID(), name);
        helper.assertTrue(c != null && c.body() != null, "the companion has a body");
        return c;
    }

    private static String abs(GameTestHelper helper, int x, int y, int z) {
        BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /**
     * Retries the check every tick until it passes, then removes owner and companion and releases the stub. Past the
     * deadline the cleanup runs anyway, so a failing test does not leave the global config on the stub.
     */
    private static void expect(GameTestHelper helper, ServerPlayer owner, Companion c, Runnable check) {
        long until = System.currentTimeMillis() + DEADLINE_MS;
        boolean[] closed = {false};
        String[] last = {"nothing checked yet"};
        Runnable close = () -> {
            if (closed[0]) return;
            closed[0] = true;
            CompanionManager.get().delete(c);
            helper.getLevel().getServer().getPlayerList().remove(owner);
            ANSWERS.remove(c.name());
            HELD.forEach((key, latch) -> {
                if (key.startsWith(c.name() + "#")) latch.countDown();
            });
            releaseStub();
        };
        helper.succeedWhen(() -> {
            if (closed[0] || System.currentTimeMillis() > until || helper.getTick() >= MAX_TICKS - 20) {
                close.run();
                helper.fail("the decision was not carried out in time; last check: " + last[0]);
            }
            try {
                check.run();
            } catch (RuntimeException e) {
                last[0] = e.getMessage();
                throw e;
            }
            close.run();
        });
    }

    private static int requests(Companion c) {
        AtomicInteger n = REQUESTS.get(c.name());
        return n == null ? 0 : n.get();
    }

    /** The chat_out row of a line the companion sent, or null. */
    private static Timeline.Event sent(Companion c, String text) {
        for (Timeline.Event e : c.state.timeline.last(Timeline.KEEP)) if (e.kind.equals("chat_out") && e.text.startsWith(text + "\"")) return e;
        return null;
    }

    private static boolean noted(Companion c, String... parts) {
        for (Timeline.Event e : c.state.timeline.last(Timeline.KEEP)) {
            if (!e.kind.equals("note")) continue;
            boolean all = true;
            for (String part : parts) all &= e.text.contains(part);
            if (all) return true;
        }
        return false;
    }

    /** Whether this tool is the running body action or the last one that ended. */
    private static boolean started(Companion c, String tool) {
        Companion.Activity running = c.activity();
        if (running != null) return running.call().startsWith(tool + "(");
        return c.lastAction() != null && c.lastAction().tool().equals(tool);
    }

    private static List<String> decisionLog(Companion c) {
        Path file = c.manager().server().getWorldPath(LevelResource.ROOT).resolve("voxelmind-local").resolve("logs").resolve(c.name() + ".jsonl");
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** A body action that runs until something replaces it. */
    private static final class Hold extends BodyAction {
        Hold() {
            super("hold", "hold()");
        }

        @Override
        protected void onStart(ActionContext ctx) {
        }

        @Override
        protected void onTick(ActionContext ctx) {
        }
    }

    // ---- one decision per wake ----

    @GameTest(maxTicks = MAX_TICKS)
    public void oneDecisionSpeaksAndActs(GameTestHelper helper) throws IOException {
        floor(helper);
        useStub();
        ServerPlayer owner = owner(helper, new BlockPos(1, 1, 1));
        String goal = abs(helper, 6, 1, 6);
        Companion c = summon(helper, owner, completion(call("say", "text", "On my way to the corner!"), call("go_to", "target", goal)));
        c.hear(owner.getGameProfile().name(), true, "Meet me in the corner, please.", "public");
        expect(helper, owner, c, () -> {
            Timeline.Event line = sent(c, "On my way to the corner!");
            helper.assertTrue(line != null && line.text.matches("(?s).*heard by .*\\b" + owner.getGameProfile().name() + "\\b.*"),
                    "the chat line reached the owner: " + (line == null ? null : line.text));
            helper.assertTrue(started(c, "go_to"), "the body action started");
            helper.assertTrue(requests(c) == 1, "one request for speech and action, got " + requests(c));
            List<String> log = decisionLog(c);
            helper.assertTrue(log.size() == 1, "one decision log entry, got " + log.size());
            JsonObject entry = JsonParser.parseString(log.getFirst()).getAsJsonObject();
            String applied = entry.get("applied").getAsString();
            helper.assertTrue(entry.get("lane").getAsString().equals("decision") && entry.getAsJsonArray("tool_calls").size() == 2,
                    "lane and tool calls: " + entry);
            helper.assertTrue(applied.startsWith("say (heard by ") && applied.contains(" + started go_to(target=\"" + goal + "\")"), "applied: " + applied);
            helper.assertTrue(entry.get("woke_by").getAsString().contains("chat from " + owner.getGameProfile().name()), "woke by: " + entry);
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void sayAloneLeavesTheRunningActionRunning(GameTestHelper helper) throws IOException {
        floor(helper);
        useStub();
        ServerPlayer owner = owner(helper, new BlockPos(1, 1, 1));
        Companion c = summon(helper, owner, completion(call("say", "text", "Still on it.")));
        Hold hold = new Hold();
        c.startAction(hold);
        c.hear(owner.getGameProfile().name(), true, "Are you still busy?", "public");
        expect(helper, owner, c, () -> {
            helper.assertTrue(sent(c, "Still on it.") != null, "the chat line was sent");
            helper.assertTrue(requests(c) == 1, "one request, got " + requests(c));
            helper.assertTrue(c.isRunning(hold.call()) && !hold.finished(), "the running action still runs: " + c.bodyActionLine());
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void extraCallsAreNotedNotCarriedOut(GameTestHelper helper) throws IOException {
        floor(helper);
        useStub();
        ServerPlayer owner = owner(helper, new BlockPos(1, 1, 1));
        Companion c = summon(helper, owner, completion(call("say", "text", "First things first."), call("go_to", "target", abs(helper, 6, 1, 6)),
                call("say", "text", "And a second line."), call("dig", "direction", "down")));
        c.hear(owner.getGameProfile().name(), true, "Go to the corner and dig down.", "public");
        expect(helper, owner, c, () -> {
            helper.assertTrue(sent(c, "First things first.") != null, "the first say was sent");
            helper.assertTrue(started(c, "go_to"), "the first body call started");
            helper.assertTrue(noted(c, "dig(direction=\"down\")", "was not executed: one action per decision"), "the second body call is a note");
            helper.assertTrue(noted(c, "And a second line.", "was not sent: one say per decision"), "the second say is a note");
            helper.assertTrue(sent(c, "And a second line.") == null, "the second say was not sent");
            boolean dug = c.state.timeline.last(Timeline.KEEP).stream().anyMatch(e -> e.kind.equals("result") && e.text.startsWith("dig("));
            helper.assertFalse(dug || started(c, "dig"), "dig did not run");
            helper.assertTrue(requests(c) == 1, "one request, got " + requests(c));
            List<String> log = decisionLog(c);
            String applied = log.isEmpty() ? "" : JsonParser.parseString(log.getFirst()).getAsJsonObject().get("applied").getAsString();
            // tools/e2e/say-do.cjs reads "superseded" or "not executed" in applied as a decision that was not carried out at all.
            helper.assertTrue(applied.contains(" + started go_to(") && applied.endsWith(" + extra say skipped + extra dig skipped")
                    && !applied.matches("(?is).*(superseded|not executed).*"), "applied: " + applied);
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aNewerChatSupersedesTheDecisionInFlight(GameTestHelper helper) throws IOException {
        floor(helper);
        useStub();
        ServerPlayer owner = owner(helper, new BlockPos(1, 1, 1));
        String name = owner.getGameProfile().name();
        Companion c = summon(helper, owner, completion(call("say", "text", "A stale line."), call("dig", "direction", "down")),
                completion(call("say", "text", "A fresh line."), call("follow", "target", "owner")));
        CountDownLatch late = hold(c, 1);
        c.hear(name, true, "Dig down right here.", "public");
        boolean[] step = {false, false};
        helper.onEachTick(() -> {
            if (!step[0] && requests(c) == 1) {
                // The owner speaks again while the first decision still waits for its answer.
                step[0] = true;
                c.hear(name, true, "No, follow me instead.", "public");
            }
            if (step[0] && !step[1] && sent(c, "A fresh line.") != null) {
                // The first decision's answer arrives after the newer decision was carried out.
                step[1] = true;
                late.countDown();
            }
        });
        expect(helper, owner, c, () -> {
            List<String> log = decisionLog(c);
            helper.assertTrue(log.stream().anyMatch(l -> l.contains("\"applied\":\"superseded by a newer wake\"") && l.contains("A stale line.")),
                    "the late answer is logged as superseded: " + log);
            helper.assertTrue(sent(c, "A fresh line.") != null && started(c, "follow"), "the newer decision was carried out");
            helper.assertTrue(sent(c, "A stale line.") == null, "the late answer's line was never sent");
            boolean dug = c.state.timeline.last(Timeline.KEEP).stream().anyMatch(e -> e.kind.equals("result") && e.text.startsWith("dig("));
            helper.assertFalse(dug || started(c, "dig"), "the late answer's dig never ran");
            helper.assertTrue(requests(c) == 2, "two requests, got " + requests(c));
            helper.assertTrue(prompt(c, 2).contains("No, follow me instead."), "the newer decision heard the newer chat");
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aDecisionThatOnlySpeaksRenewsTheRetry(GameTestHelper helper) throws IOException {
        floor(helper);
        useStub();
        ServerPlayer owner = owner(helper, new BlockPos(1, 1, 1));
        String name = owner.getGameProfile().name();
        Companion c = summon(helper, owner, EMPTY, completion(call("say", "text", "Sorry, here I am.")), EMPTY,
                completion(call("say", "text", "Here again.")));
        c.hear(name, true, "Hello?", "public");
        boolean[] asked = {false};
        helper.onEachTick(() -> {
            if (!asked[0] && sent(c, "Sorry, here I am.") != null) {
                asked[0] = true;
                c.hear(name, true, "Hello again?", "public");
            }
        });
        expect(helper, owner, c, () -> {
            helper.assertTrue(sent(c, "Here again.") != null, "the second answer without a tool call got its retry too; requests: " + requests(c));
            helper.assertTrue(requests(c) == 4, "four requests, got " + requests(c));
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aDecisionDuringAWaitSeesTheWait(GameTestHelper helper) throws IOException {
        floor(helper);
        useStub();
        ServerPlayer owner = owner(helper, new BlockPos(1, 1, 1));
        JsonObject wait = new JsonObject();
        wait.addProperty("seconds", 60);
        wait.addProperty("for_what", "my owner");
        Companion c = summon(helper, owner, completion(call("wait", wait)), completion(call("say", "text", "Still waiting here.")));
        CountDownLatch first = hold(c, 1);
        c.hear(owner.getGameProfile().name(), true, "Wait here for me.", "public");
        boolean[] woke = {false};
        helper.onEachTick(() -> {
            if (!woke[0] && requests(c) == 1) {
                // An event during the first decision queues behind it; the decision it wakes comes while the chosen wait runs.
                woke[0] = true;
                c.mind.wake("a test event", 0);
                first.countDown();
            }
        });
        expect(helper, owner, c, () -> {
            helper.assertTrue(sent(c, "Still waiting here.") != null, "the second decision spoke; requests: " + requests(c));
            String during = prompt(c, 2);
            helper.assertTrue(during.contains("Woke by: a test event") && during.contains("WAIT: wait(seconds=60, for_what=\"my owner\") running for "),
                    "the decision during the wait sees it:\n" + during);
            helper.assertFalse(prompt(c, 1).contains("WAIT:"), "no wait line while none runs");
            boolean ended = c.state.timeline.last(Timeline.KEEP).stream().anyMatch(e -> e.kind.equals("result") && e.text.startsWith("wait("));
            helper.assertFalse(ended, "a decision that only speaks leaves the wait running");
        });
    }

    // ---- conversation window ----

    private static Timeline.Event at(Timeline.Event e, long epochMs) {
        e.epochMs = epochMs;
        return e;
    }

    @GameTest
    public void identicalResultsInARowBecomeOneRow(GameTestHelper helper) {
        long now = System.currentTimeMillis();
        Timeline t = new Timeline();
        at(t.add("result", "go_to(target=\"owner\"): arrived next to your owner", false, "done"), now - 300_000);
        for (int i = 0; i < 14; i++) {
            at(t.add("result", "place_block(block=\"dirt\", pos=\"3 64 5\"): cannot place: the cell is occupied", false, "failed"), now - 180_000 + i * 10_000L);
        }
        at(t.add("chat_in", "Steve (your owner): \"is the hole patched?\"", true), now - 30_000);
        at(t.add("result", "eat(): ate one bread; food 12 -> 17", false, "done"), now - 10_000);
        List<Timeline.Row> rows = PromptBuilder.window(t, now);
        String rendered = Timeline.render(rows, now);
        helper.assertTrue(rows.size() == 4, "four rows:\n" + rendered);
        helper.assertTrue(rows.get(1).count() == 14 && rendered.lines().filter(l -> l.contains("cannot place")).count() == 1, rendered);
        helper.assertTrue(rendered.contains("(50s ago) ✗ place_block(block=\"dirt\", pos=\"3 64 5\"): cannot place: the cell is occupied (x14, first 3m ago)"),
                rendered);
        helper.assertTrue(rows.get(2).event().kind.equals("chat_in") && rows.get(3).event().text.startsWith("eat()"), "order kept:\n" + rendered);
        helper.succeed();
    }

    @GameTest
    public void chatWindowHoldsHalfAnHourAndAtLeast24Lines(GameTestHelper helper) {
        long now = System.currentTimeMillis();
        Timeline busy = new Timeline();
        for (int i = 0; i < 30; i++) at(busy.add("chat_in", "Steve (your owner): \"old " + i + "\"", true), now - 70 * 60_000L + i * 60_000L);
        for (int i = 0; i < 40; i++) at(busy.add("chat_in", "Steve (your owner): \"new " + i + "\"", true), now - 29 * 60_000L + i * 40_000L);
        String rendered = Timeline.render(PromptBuilder.window(busy, now), now);
        for (int i = 0; i < 40; i++) helper.assertTrue(rendered.contains("\"new " + i + "\""), "line " + i + " of the last 30 minutes is missing");
        helper.assertFalse(rendered.contains("\"old "), "older lines beyond the newest 24 stay out:\n" + rendered);

        Timeline quiet = new Timeline();
        for (int i = 0; i < 30; i++) at(quiet.add("chat_in", "Steve (your owner): \"old " + i + "\"", true), now - 70 * 60_000L + i * 60_000L);
        List<Timeline.Row> rows = PromptBuilder.window(quiet, now);
        helper.assertTrue(rows.size() == 24 && rows.getFirst().event().text.contains("\"old 6\""), "the newest 24 lines however old: " + rows.size());
        helper.succeed();
    }

    @GameTest
    public void chatWindowStaysWithin6000Characters(GameTestHelper helper) {
        long now = System.currentTimeMillis();
        Timeline t = new Timeline();
        String filler = "x".repeat(230);
        for (int i = 0; i < 40; i++) at(t.add("chat_in", "Steve (your owner): \"" + i + " " + filler + "\"", true), now - 20 * 60_000L + i * 20_000L);
        List<Timeline.Row> rows = PromptBuilder.window(t, now);
        int chars = rows.stream().mapToInt(r -> Timeline.line(r.event()).length()).sum();
        helper.assertTrue(chars <= 6000, "chat rows hold " + chars + " characters");
        helper.assertTrue(rows.size() > 10 && rows.size() < 40, "rows: " + rows.size());
        helper.assertTrue(rows.getLast().event().text.contains("\"39 "), "the newest line is kept");
        helper.assertTrue(rows.getFirst().event().text.contains("\"" + (40 - rows.size()) + " "), "the newest lines without gaps");
        helper.succeed();
    }
}
