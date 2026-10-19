package com.voxelmind.local.test;

import com.voxelmind.local.mind.Brain;
import com.voxelmind.local.mind.Brains;
import com.voxelmind.local.mind.PromptTexts;
import com.voxelmind.local.provider.UsageStats;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Brains as data: the shipped ones, a new copy to change, broken brains, and the steps of the usage count. */
public class BrainGameTests {
    @GameTest
    public void shippedBrainsLoadAndAnUnknownOneFallsBackToTheDefault(GameTestHelper helper) {
        Brains.reload();
        Brain def = Brains.get("default");
        Brain lite = Brains.get("lite");
        helper.assertTrue(def.builtin && def.id.equals("default") && def.frame == null && def.decision == null, "the default uses the code's texts");
        helper.assertTrue(lite.builtin && lite.frame != null && lite.conversation != null && lite.decision != null && lite.memoryGuide != null,
                "Lite brings its own texts");
        helper.assertTrue(lite.decision.contains("{continuity}") && lite.timelineEvents == 15 && !lite.clock && lite.temperature != null,
                "Lite: shorter timeline, no clock, its own temperature");
        helper.assertTrue(Brains.get("no-such-brain").id.equals("default") && Brains.get(null).id.equals("default"), "unknown falls back to the default");
        helper.assertTrue(lite.allows("say") && lite.allows("gather") && lite.statsId().equals("lite"), "shipped brains count by id");
        helper.succeed();
    }

    @GameTest
    public void aNewBrainIsAFullCopyOfTheDefaultToChange(GameTestHelper helper) throws IOException {
        Path dir = Brains.folder().resolve("vml-test-copy");
        deleteTree(dir);
        Path made = Brains.create("vml-test-copy");
        helper.assertTrue(made.equals(dir), "made in the brains folder: " + made);
        helper.assertTrue(Files.readString(dir.resolve("frame.md")).strip().equals(PromptTexts.DEFAULT_FRAME), "frame.md holds the default frame without the core rules");
        helper.assertTrue(Files.readString(dir.resolve("decision.md")).contains("{continuity}"), "decision.md keeps the continuity place");
        helper.assertTrue(Files.readString(dir.resolve("memory.md")).contains("{maxPreviously}"), "memory.md keeps the length limits");
        for (String f : List.of("brain.json", "conversation.md", "identity.md")) helper.assertTrue(Files.isRegularFile(dir.resolve(f)), f + " written");
        Brain copy = Brains.get("vml-test-copy");
        helper.assertTrue(copy.id.equals("vml-test-copy") && !copy.builtin && copy.statsId().equals("custom"), "loaded as an own brain, counted as custom");
        helper.assertTrue(copy.timelineEvents == 30 && copy.chatChars == 6000 && copy.clock && copy.memory, "every setting as the default's");
        assertFails(helper, () -> Brains.create("vml-test-copy"), "already exists");
        assertFails(helper, () -> Brains.create("lite"), "shipped with the mod");
        assertFails(helper, () -> Brains.create("Bad Name!"), "lowercase");
        deleteTree(dir);
        Brains.reload();
        helper.succeed();
    }

    @GameTest
    public void aBrokenBrainIsSkippedWithItsProblemAndTheOthersStillLoad(GameTestHelper helper) throws IOException {
        Path broken = Brains.folder().resolve("vml-test-broken");
        Path big = Brains.folder().resolve("vml-test-big");
        Path shadow = Brains.folder().resolve("lite");
        Files.createDirectories(broken);
        Files.createDirectories(big);
        Files.createDirectories(shadow);
        Files.writeString(broken.resolve("brain.json"), "{ not json");
        Files.writeString(big.resolve("brain.json"), "{}");
        Files.writeString(big.resolve("frame.md"), "x".repeat(20_000));
        Files.writeString(shadow.resolve("brain.json"), "{}");
        List<String> problems = Brains.reload();
        helper.assertTrue(problems.stream().anyMatch(p -> p.startsWith("vml-test-broken: brain.json is not valid JSON")), "broken JSON named: " + problems);
        helper.assertTrue(problems.stream().anyMatch(p -> p.startsWith("vml-test-big: frame.md")), "an oversized text named: " + problems);
        helper.assertTrue(problems.stream().anyMatch(p -> p.startsWith("lite: this name belongs to a brain shipped")), "a shipped name cannot be taken: " + problems);
        helper.assertTrue(Brains.get("lite").builtin && Brains.exists("default"), "the shipped brains still load");
        deleteTree(broken);
        deleteTree(big);
        deleteTree(shadow);
        Brains.reload();
        helper.succeed();
    }

    @GameTest
    public void theUsageCountOnlyHasSteps(GameTestHelper helper) {
        helper.assertTrue(UsageStats.ageStep("2026-10-19", "2026-10-19").equals("0"), "first day");
        helper.assertTrue(UsageStats.ageStep("2026-10-12", "2026-10-19").equals("1-7"), "a week");
        helper.assertTrue(UsageStats.ageStep("2026-09-25", "2026-10-19").equals("8-30"), "a month");
        helper.assertTrue(UsageStats.ageStep("2026-01-01", "2026-10-19").equals("31+"), "longer");
        helper.assertTrue(UsageStats.ageStep("garbage", "2026-10-19").equals("0"), "an unreadable day counts as new");
        helper.assertTrue(UsageStats.minuteStep(3).equals("1-15") && UsageStats.minuteStep(15).equals("15-60") && UsageStats.minuteStep(200).equals("60+"),
                "minutes in steps, never exact");
        helper.succeed();
    }

    private interface Io {
        void run() throws IOException;
    }

    private static void assertFails(GameTestHelper helper, Io call, String expected) {
        try {
            call.run();
            helper.fail("expected a refusal mentioning " + expected);
        } catch (IOException e) {
            helper.assertTrue(e.getMessage().contains(expected), "message names " + expected + ": " + e.getMessage());
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
