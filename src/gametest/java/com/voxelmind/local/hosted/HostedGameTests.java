package com.voxelmind.local.hosted;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.voxelmind.local.body.CompanionConnection;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.companion.CompanionManager;
import com.voxelmind.local.mind.Timeline;
import com.voxelmind.local.world.Placed;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * God and Builder in a real server world, without a model: the tools are called as the model would call
 * them, and the checks read the world and the timeline facts the next turn would see.
 */
public class HostedGameTests {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    /** A human player standing in the test area, looking south (yaw 0). */
    private static ServerPlayer owner(GameTestHelper helper, BlockPos rel, boolean operator) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Host" + COUNTER.incrementAndGet());
        ServerPlayer p = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        BlockPos abs = helper.absolutePos(rel);
        p.snapTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0f, 0f);
        server.getPlayerList().placeNewPlayer(new CompanionConnection(p), p, CommonListenerCookie.createInitial(profile, false));
        p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        p.setGameMode(GameType.CREATIVE);
        if (operator) op(server, p);
        return p;
    }

    /** The game-test server gives operators level 0 by default; a real operator has game-master rights. */
    private static void op(MinecraftServer server, ServerPlayer p) {
        server.getPlayerList().op(new NameAndId(p.getUUID(), p.getGameProfile().name()),
                java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.of(false));
    }

    private static Companion bring(GameTestHelper helper, ServerPlayer owner, String kind) {
        CompanionManager m = CompanionManager.get();
        String name = (kind.equals("god") ? "God" : "Fee") + COUNTER.incrementAndGet();
        String error = m.summon(owner, name, null, kind);
        helper.assertTrue(error == null, "summon " + kind + ": " + error);
        Companion c = m.find(owner.getUUID(), name);
        helper.assertTrue(c != null && c.hosted != null && c.hosted.active(), kind + " session is running");
        return c;
    }

    private static void leave(GameTestHelper helper, ServerPlayer owner, Companion c) {
        MinecraftServer server = helper.getLevel().getServer();
        CompanionManager.get().delete(c);
        server.getPlayerList().deop(new NameAndId(owner.getUUID(), owner.getGameProfile().name()));
        server.getPlayerList().remove(owner);
    }

    private static boolean timelineHas(Companion c, String kind, String... parts) {
        for (Timeline.Event e : c.state.timeline.last(200)) {
            if (!e.kind.equals(kind)) continue;
            boolean all = true;
            for (String part : parts) all &= e.text.contains(part);
            if (all) return true;
        }
        return false;
    }

    private static List<Entity> tagged(ServerLevel level, String tag) {
        return List.copyOf(level.getEntities(EntityTypeTest.forClass(Entity.class), e -> e.isAlive() && e.entityTags().contains(tag)));
    }

    private static boolean alive(GameTestHelper helper, UUID id) {
        Entity e = helper.getLevel().getEntity(id);
        return e != null && e.isAlive();
    }

    private static JsonObject args(Object... pairs) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            if (v instanceof Number n) o.addProperty((String) pairs[i], n);
            else if (v instanceof Boolean b) o.addProperty((String) pairs[i], b);
            else if (v instanceof JsonArray a) o.add((String) pairs[i], a);
            else o.addProperty((String) pairs[i], String.valueOf(v));
        }
        return o;
    }

    // ---- pure checks ----

    @GameTest
    public void commandPolicyKeepsAdminAndMassHarmOut(GameTestHelper helper) {
        helper.assertTrue(CommandPolicy.refuse("op Steve") != null, "op is refused");
        helper.assertTrue(CommandPolicy.refuse("/stop") != null, "stop is refused");
        helper.assertTrue(CommandPolicy.refuse("kill @a") != null, "kill @a is refused");
        helper.assertTrue(CommandPolicy.refuse("execute as @a run kill @s") != null, "harm hidden in execute still targets @a");
        helper.assertTrue(CommandPolicy.refuse("summon minecraft:zombie ~ ~ ~") == null, "summon is available");
        helper.assertTrue(CommandPolicy.refuse("kill @e[type=minecraft:zombie]") == null, "killing zombies is available");
        helper.assertTrue(CommandPolicy.refuse("setblock ~ ~1 ~ command_block{Command:\"vml endpoint https://x.example/v1\",auto:1b}") != null,
                "command blocks would run commands past the boundary");
        helper.assertTrue(CommandPolicy.refuse("give @p minecraft:repeating_command_block") != null, "no command block items");
        helper.assertTrue(CommandPolicy.refuse("gamerule max_block_modifications 50000000") != null, "the fill cap stays");
        helper.assertTrue(CommandPolicy.refuse("gamerule randomTickSpeed 100000") != null, "random ticks stay bounded");
        helper.assertTrue(CommandPolicy.refuse("tick sprint 1d") != null, "no tick sprint");
        helper.assertTrue(CommandPolicy.refuse("tick rate 500") != null, "no extreme tick rate");
        helper.assertTrue(CommandPolicy.refuse("gamerule random_tick_speed 6") == null, "small gamerule changes stay available");
        helper.assertTrue(CommandPolicy.refuse("tellraw @p {\"text\":\"Your next command: jump!\"}") == null, "the word command in a text is fine");
        helper.succeed();
    }

    @GameTest
    public void builderGeometryIsParsedAndPlacedAheadOfTheOwner(GameTestHelper helper) {
        BuilderWorld.Geometry g = BuilderWorld.parse("""
                ```
                1. fill 0 0 0 2 0 2 stone_bricks
                - setblock 1 1 1 oak_stairs[facing=north]
                fill 48 111 -52 1 1 1 dirt
                setblock 0 0 0 tnt
                a tall tower
                ```""");
        helper.assertTrue(g.ops().size() == 2, "two usable lines, got " + g.ops());
        helper.assertTrue(g.rejected().size() == 3, "absolute coordinates, tnt and prose are set aside: " + g.rejected());
        BuilderWorld.Placement p = BuilderWorld.place(g.ops(), new BlockPos(0, 64, 0), "south", 3);
        helper.assertTrue(p.target().min().equals(new BlockPos(-1, 64, 3)) && p.target().max().equals(new BlockPos(1, 65, 5)),
                "3x2x3 design centred sideways, 3 blocks south: " + p.target().bounds());
        helper.assertTrue(p.layers().size() == 3 && p.layers().get(1).getFirst().equals("fill -1 63 3 1 63 5 grass_block"),
                "site clearing, then a floor under the design: " + p.layers());
        helper.assertTrue(p.layers().get(2).contains("setblock 0 65 4 oak_stairs[facing=north]"), "design shifted into place: " + p.layers().get(2));
        BuilderWorld.Box north = BuilderWorld.ownerFront(new BlockPos(10, 64, 10), BuilderWorld.facing(180f), 3, 2, 1, 1, 0);
        helper.assertTrue(north.min().equals(new BlockPos(9, 64, 8)) && north.max().equals(new BlockPos(11, 64, 9)), "north front: " + north.bounds());
        helper.assertTrue(BuilderWorld.refuseBlock("command_block") != null && BuilderWorld.refuseBlock("lava") != null, "denied blocks");
        helper.assertTrue(BuilderWorld.refuseBlock("unicorn_block") != null, "unknown block");
        helper.assertTrue(BuilderWorld.refuseBlock("minecraft:oak_door[half=upper,facing=south]") == null, "states are fine");
        helper.assertTrue(BuilderWorld.chunkedFill(BuilderWorld.Box.of(0, 0, 0, 99, 9, 99), "air").size() == 4, "100x10x100 needs 4 fills of ≤32768");
        helper.succeed();
    }

    @GameTest
    public void rejectedGeometryLinesSayWhy(GameTestHelper helper) {
        BuilderWorld.Geometry g = BuilderWorld.parse("""
                fill 0 0 0 4 3 4 oak_planks hollow
                setblock 2 0 0 oak_door[half=lower, facing=south]
                # walls
                fill 0 0 0 4 0 4 stone_bricks""");
        helper.assertTrue(g.ops().size() == 1, "one usable line: " + g.ops());
        helper.assertTrue(g.rejected().size() == 3, "three lines set aside: " + g.rejected());
        helper.assertTrue(g.rejected().get(0).endsWith("(fill takes 7 values, this line has 8)"), "fill with a mode: " + g.rejected().get(0));
        helper.assertTrue(g.rejected().get(1).endsWith("(setblock takes 4 values, this line has 5)"), "state with a space: " + g.rejected().get(1));
        helper.assertTrue(g.rejected().get(2).endsWith("(not a fill or setblock command)"), "comment: " + g.rejected().get(2));
        String examples = BuilderMind.examples(g.rejected());
        helper.assertTrue(examples.contains("hollow (fill takes 7") && examples.contains("# walls (not a fill"), "the result names examples: " + examples);
        helper.succeed();
    }

    @GameTest
    public void aFillThatChangesNothingIsANoOpNotAFailure(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos b = helper.absolutePos(new BlockPos(3, 2, 3));
        String fill = "fill " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + b.getX() + " " + b.getY() + " " + b.getZ() + " oak_planks";
        Vec3 at = Vec3.atCenterOf(a);
        CommandRunner.Result first = CommandRunner.runAt(level.getServer(), level, at, Vec2.ZERO, "Test", fill);
        helper.assertTrue(first.ok() && !first.noOp(), "the first fill changes blocks: " + first);
        CommandRunner.Result again = CommandRunner.runAt(level.getServer(), level, at, Vec2.ZERO, "Test", fill);
        helper.assertTrue(again.ok() && again.noOp(), "the same fill again is ok and a no-op: " + again);
        helper.assertTrue(again.text().contains("No blocks were filled"), "the server's words stay: " + again.text());
        CommandRunner.Result bad = CommandRunner.runAt(level.getServer(), level, at, Vec2.ZERO, "Test", "setblock " + a.getX() + " " + a.getY() + " " + a.getZ() + " unicorn_block");
        helper.assertTrue(!bad.ok() && !bad.noOp(), "a real error stays an error: " + bad);
        Scheduler.Run run = new Scheduler.Run("e1", "fill twice", List.of(new Scheduler.Step(0, fill), new Scheduler.Step(0, fill)), level, r -> {});
        run.results.add(new Scheduler.StepResult(0, fill, first.ok(), first.text(), first.noOp()));
        run.results.add(new Scheduler.StepResult(1, fill, again.ok(), again.text(), again.noOp()));
        String summary = Scheduler.summary(run);
        helper.assertTrue(summary.contains("2/2 commands returned success (1 of them changed nothing: the blocks already were so)")
                && !summary.contains("first error"), "summary: " + summary);
        helper.succeed();
    }

    @GameTest
    public void godPlainTextSpeaksLikeTheBuilders(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = owner(helper, new BlockPos(3, 1, 3), true);
        Companion god = bring(helper, owner, "god");
        String turn = ((GodMind) god.hosted).turn("chat", System.currentTimeMillis());
        helper.assertTrue(turn.contains("Plain reply text reaches the players in chat") && !turn.contains("Only say speaks"),
                "the turn says where plain text goes: " + turn);
        helper.assertTrue(HostedTexts.GOD_FRAME.contains("plain assistant reply text is also public"), "the frame says so too");
        leave(helper, owner, god);
        helper.succeed();
    }

    // ---- sessions in the world ----

    @GameTest(maxTicks = 100)
    public void builderAndGodNeedHostOrOperator(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = owner(helper, new BlockPos(3, 1, 3), false);
        CompanionManager m = CompanionManager.get();
        String refused = m.summon(owner, "Fee" + COUNTER.incrementAndGet(), null, "builder");
        helper.assertTrue(refused != null && refused.contains("console power"), "a guest without op cannot bring a builder: " + refused);
        helper.assertTrue(m.ownedBy(owner.getUUID()).isEmpty(), "nothing was created");
        op(helper.getLevel().getServer(), owner);
        Companion c = bring(helper, owner, "builder");
        helper.assertTrue(c.state.kind.equals("builder") && c.present(), "the builder is present");
        UUID body = ((BuilderMind) c.hosted).bodyUuid();
        helper.assertTrue(body != null && alive(helper, body), "the fairy body appeared");
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(alive(helper, body), "its own body survives the leftover check");
            m.dismiss(c, true, true);
            helper.assertTrue(!c.hosted.active() && !c.state.active, "dismissed");
            helper.assertTrue(!alive(helper, body), "the body left with it");
            helper.assertTrue(timelineHas(c, "presence", "your session ended"), "the end is a fact");
            leave(helper, owner, c);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void builderFillChangesBlocksAndUndoRestoresThem(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = owner(helper, new BlockPos(3, 1, 1), true);
        Companion c = bring(helper, owner, "builder");
        BuilderMind b = (BuilderMind) c.hosted;
        BlockPos o = owner.blockPosition();
        ServerLevel level = helper.getLevel();
        // Glass a player placed where the fill goes: the fill replaces it, the undo puts it back, in the world and the index.
        BlockPos glass = o.offset(0, 0, 1);
        level.setBlockAndUpdate(glass, Blocks.GLASS.defaultBlockState());
        UUID gasade = UUID.randomUUID();
        Placed.of(level).record(level, glass, gasade, "Gasade");
        HostedMind.Outcome now = b.tool("fill", args("anchor", "owner_front", "distance", 1, "width", 3, "depth", 2, "height", 2, "block", "minecraft:oak_planks"));
        helper.assertTrue(now == null, "the fill runs on the server clock: " + now);
        helper.assertTrue(b.tool("fill", args("width", 3, "depth", 3, "height", 3, "block", "tnt")).text().contains("not available"), "tnt is refused");
        int[] phase = {0};
        helper.onEachTick(() -> {
            if (phase[0] == 0 && timelineHas(c, "result", "blocks changed")) {
                phase[0] = 1;
                for (int x = -1; x <= 1; x++) for (int y = 0; y <= 1; y++) for (int z = 1; z <= 2; z++) {
                    BlockPos p = o.offset(x, y, z);
                    if (!helper.getLevel().getBlockState(p).is(Blocks.OAK_PLANKS)) helper.fail("no planks at " + p);
                }
                if (!timelineHas(c, "result", "12 blocks changed", "undo target \"e")) helper.fail("receipt: " + c.state.timeline.last(5));
                // The filled blocks are placed by the Builder, the glass among them too now.
                for (int x = -1; x <= 1; x++) for (int y = 0; y <= 1; y++) for (int z = 1; z <= 2; z++) {
                    String placer = Placed.placer(level, o.offset(x, y, z));
                    if (!c.name().equals(placer)) helper.fail("filled block at " + o.offset(x, y, z) + " placed by " + placer);
                }
                HostedMind.Outcome undo = b.tool("undo", args("target", "last"));
                if (undo == null || !undo.ok() || !undo.text().contains("12 blocks restored")) helper.fail("undo: " + (undo == null ? null : undo.text()));
                for (int x = -1; x <= 1; x++) for (int y = 0; y <= 1; y++) for (int z = 1; z <= 2; z++) {
                    BlockPos p = o.offset(x, y, z);
                    if (p.equals(glass)) {
                        if (!level.getBlockState(p).is(Blocks.GLASS) || !"Gasade".equals(Placed.placer(level, p))) {
                            helper.fail("undo did not put Gasade's glass back: " + level.getBlockState(p) + ", placer " + Placed.placer(level, p));
                        }
                        continue;
                    }
                    if (!helper.getLevel().getBlockState(p).isAir()) helper.fail("undo left a block at " + p);
                    if (Placed.of(level).entry(level.dimension(), p) != null) helper.fail("undo left an index entry at " + p);
                }
                leave(helper, owner, c);
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = 300)
    public void builderFillOverTheSameBlocksIsNotAFailure(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = owner(helper, new BlockPos(3, 1, 1), true);
        Companion c = bring(helper, owner, "builder");
        BuilderMind b = (BuilderMind) c.hosted;
        JsonObject fill = args("anchor", "owner_front", "distance", 1, "width", 3, "depth", 2, "height", 2, "block", "minecraft:oak_planks");
        helper.assertTrue(b.tool("fill", fill) == null, "the first fill runs");
        int[] phase = {0};
        helper.onEachTick(() -> {
            if (phase[0] == 0 && timelineHas(c, "result", "12 blocks changed")) {
                phase[0] = 1;
                helper.assertTrue(b.tool("fill", fill) == null, "the same fill again runs");
            } else if (phase[0] == 1 && timelineHas(c, "result", "0 blocks changed")) {
                phase[0] = 2;
                if (timelineHas(c, "result", "FAILED")) helper.fail("an unchanged fill was reported as failed: " + c.state.timeline.last(5));
                if (!timelineHas(c, "result", "result of \"fill", "changed nothing: the blocks already were so")) {
                    helper.fail("the receipt does not say the blocks already were so: " + c.state.timeline.last(5));
                }
                leave(helper, owner, c);
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = 100, skyAccess = true)
    public void godRainsCreaturesAndCleansThemUp(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = owner(helper, new BlockPos(3, 1, 3), true);
        Companion c = bring(helper, owner, "god");
        GodMind g = (GodMind) c.hosted;
        HostedMind.Outcome rain = g.tool("rain", args("entity", "minecraft:chicken", "count", 4, "radius", 3, "name", "Omen"));
        helper.assertTrue(rain != null && rain.ok() && rain.text().contains("4 chicken"), "rain: " + (rain == null ? null : rain.text()));
        List<Entity> chickens = tagged(helper.getLevel(), GodMind.TAG);
        helper.assertTrue(chickens.size() == 4, "four tagged chickens: " + chickens.size());
        helper.assertTrue(g.tool("rain", args("entity", "wither")).text().startsWith("refused"), "bosses do not rain");
        String facts = g.facts("test", System.currentTimeMillis());
        helper.assertTrue(facts.contains("[YOUR ENTITIES]") && facts.contains("named \"Omen\""), "the god sees its creatures");
        HostedMind.Outcome clean = g.tool("cleanup", new JsonObject());
        helper.assertTrue(clean.text().contains("removed 4 creatures"), "cleanup: " + clean.text());
        helper.assertTrue(tagged(helper.getLevel(), GodMind.TAG).isEmpty(), "nothing tagged is left");
        leave(helper, owner, c);
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void godSequenceReportsEachStepAndRulesFireOnEvents(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = owner(helper, new BlockPos(3, 1, 3), true);
        Companion c = bring(helper, owner, "god");
        GodMind g = (GodMind) c.hosted;
        BlockPos a = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos b = helper.absolutePos(new BlockPos(2, 1, 1));
        JsonArray steps = new JsonArray();
        steps.add(args("delay", 0, "command", "setblock " + a.getX() + " " + a.getY() + " " + a.getZ() + " minecraft:gold_block"));
        steps.add(args("delay", 10, "command", "setblock " + b.getX() + " " + b.getY() + " " + b.getZ() + " minecraft:diamond_block"));
        helper.assertTrue(g.tool("sequence", args("label", "treasure", "steps", steps)) == null, "the sequence runs without the model");
        JsonArray bad = new JsonArray();
        bad.add(args("delay", 0, "command", "op " + owner.getGameProfile().name()));
        helper.assertTrue(g.tool("sequence", args("steps", bad)).text().contains("server-admin"), "admin steps are refused up front");
        JsonArray then = new JsonArray();
        then.add("setblock {x} {y} {z} minecraft:glass");
        HostedMind.Outcome rule = g.tool("rule", args("label", "glassy", "when", "block_break", "filter", "dirt", "then", then, "ttl_seconds", 60));
        helper.assertTrue(rule.ok(), "rule: " + rule.text());
        BlockPos broken = helper.absolutePos(new BlockPos(5, 1, 5));
        g.fireRules(new GodRules.Event("block_break", owner, helper.getLevel(), Vec3.atCenterOf(broken), "minecraft:dirt", "", ""));
        g.fireRules(new GodRules.Event("block_break", owner, helper.getLevel(), Vec3.atCenterOf(broken.east()), "minecraft:stone", "", ""));
        helper.assertTrue(helper.getLevel().getBlockState(broken).is(Blocks.GLASS), "the rule ran at the event");
        helper.assertTrue(helper.getLevel().getBlockState(broken.east()).isAir(), "the filter kept stone out");
        helper.succeedWhen(() -> {
            helper.assertTrue(timelineHas(c, "result", "\"treasure\": 2/2 commands returned success", "step 1: /setblock"),
                    "sequence receipt: " + c.state.timeline.last(4));
            helper.assertTrue(helper.getLevel().getBlockState(b).is(Blocks.DIAMOND_BLOCK), "second step after its delay");
            helper.assertTrue(timelineHas(c, "event", "rule \"glassy\" fired 1×"), "rule fire count is a fact");
            leave(helper, owner, c);
        });
    }

    @GameTest(maxTicks = 60)
    public void ownerSettingsBecomeFactsForTheNextTurn(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = owner(helper, new BlockPos(3, 1, 3), true);
        Companion god = bring(helper, owner, "god");
        CompanionManager m = CompanionManager.get();
        helper.assertTrue(m.setHosted(god, "chaos", "5") == null && god.state.chaos == 5, "chaos 5");
        helper.assertTrue(m.setHosted(god, "chaos", "9") != null, "chaos stays within 1-5");
        helper.assertTrue(m.setHosted(god, "campaign", "trials") == null && god.state.campaign.equals("trials"), "campaign");
        helper.assertTrue(m.setHosted(god, "guests", "off") == null && !god.state.guests, "guests hidden");
        helper.assertTrue(m.setHosted(god, "body", "bee") != null, "a god has no body");
        helper.assertTrue(timelineHas(god, "note", "chaos level to 5/5"), "the change is a fact");
        String facts = ((GodMind) god.hosted).facts("settings", System.currentTimeMillis());
        helper.assertTrue(facts.contains("Campaign frame: trials") && facts.contains("Chaos level 5/5") && facts.contains("hidden from you"),
                "[SETTINGS] shows the owner's choices");
        helper.assertTrue(m.summon(owner, god.name(), null, "builder") != null, "a name keeps its kind");
        leave(helper, owner, god);
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void companionBodyIsLabelledAsAiForEveryone(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = owner(helper, new BlockPos(3, 1, 3), false);
        CompanionManager m = CompanionManager.get();
        String name = "Bob" + COUNTER.incrementAndGet();
        helper.assertTrue(m.summon(owner, name, null) == null, "companion summoned");
        Companion c = m.find(owner.getUUID(), name);
        helper.assertTrue(c != null && c.body() != null, "the companion has a body");
        var team = helper.getLevel().getServer().getScoreboard().getPlayersTeam(c.body().getScoreboardName());
        helper.assertTrue(team != null && team.getName().equals("voxelmind_ai"), "the body is in the AI team");
        String shown = c.body().getDisplayName().getString();
        helper.assertTrue(shown.equals("[AI] " + name), "name tag, tab list and chat show [AI]: " + shown);
        leave(helper, owner, c);
        helper.succeed();
    }
}
