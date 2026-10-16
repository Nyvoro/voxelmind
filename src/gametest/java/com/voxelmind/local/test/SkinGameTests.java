package com.voxelmind.local.test;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.voxelmind.local.companion.Skins;
import com.voxelmind.local.provider.UsageStats;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Own skin PNGs: the image check, and the way from a link or a file through MineSkin (a local stand-in) to signed textures. */
public class SkinGameTests {
    /** A 64×64 skin; slim leaves the outer right-arm columns (x 54-55, y 20-31) empty, as skin editors do. */
    private static byte[] skin(boolean slim, int height, boolean noisy) {
        BufferedImage image = new BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB);
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < 64; x++) {
                boolean armGap = slim && x >= 54 && x <= 55 && y >= 20 && y <= 31;
                int rgb = noisy ? random.nextInt(0xffffff) : 0x3366cc;
                image.setRGB(x, y, armGap ? 0 : 0xff000000 | rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    @GameTest
    public void ownPngIsCheckedAndItsArmWidthFound(GameTestHelper helper) {
        Skins.Png classic = Skins.check(skin(false, 64, true), null);
        Skins.Png slim = Skins.check(skin(true, 64, true), null);
        helper.assertTrue(classic.variant().equals("classic"), "full arms are classic, got " + classic.variant());
        helper.assertTrue(slim.variant().equals("slim"), "empty outer arm columns are slim, got " + slim.variant());
        helper.assertTrue(Skins.check(skin(true, 64, true), "classic").variant().equals("classic"), "a stated variant wins");
        helper.assertTrue(Skins.check(skin(false, 32, true), null).variant().equals("classic"), "old 64×32 skins are classic");
        byte[] tiny = skin(false, 64, false);
        helper.assertTrue(tiny.length < 1024, "the one-colour skin compresses below MineSkin's 1 KB minimum: " + tiny.length);
        Skins.Png padded = Skins.check(tiny, null);
        helper.assertTrue(padded.bytes().length >= 1024 && padded.bytes().length <= 20_000, "rewritten for MineSkin: " + padded.bytes().length + " bytes");
        assertRefused(helper, () -> Skins.check("not an image".getBytes(StandardCharsets.UTF_8), null), "not a PNG");
        BufferedImage big = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(big, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertRefused(helper, () -> Skins.check(out.toByteArray(), null), "128×128");
        helper.succeed();
    }

    private static void assertRefused(GameTestHelper helper, Runnable call, String expected) {
        try {
            call.run();
            helper.fail("expected a refusal mentioning " + expected);
        } catch (Skins.SkinException e) {
            helper.assertTrue(e.getMessage().contains(expected), "message names " + expected + ": " + e.getMessage());
        }
    }

    @GameTest
    public void ownSkinFromALinkAndAFileIsSignedThroughMineSkin(GameTestHelper helper) throws IOException {
        byte[] png = skin(true, 64, true);
        List<String> uploads = new CopyOnWriteArrayList<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/skin.png", ex -> reply(ex, 200, "image/png", png));
        stub.createContext("/moved.png", ex -> {
            ex.getResponseHeaders().add("Location", "/skin.png");
            reply(ex, 302, "text/plain", new byte[0]);
        });
        stub.createContext("/v2/queue", ex -> {
            if (ex.getRequestMethod().equals("POST")) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
                uploads.add(body + "\nUA=" + ex.getRequestHeaders().getFirst("User-Agent"));
                // As the real service does: 202 with a job, even when it is already completed.
                reply(ex, 202, "application/json", "{\"success\":true,\"job\":{\"id\":\"job-1\",\"status\":\"completed\"}}".getBytes(StandardCharsets.UTF_8));
            } else {
                JsonObject data = new JsonObject();
                data.addProperty("value", "c2lnbmVkLXRleHR1cmVz");
                data.addProperty("signature", "c2lnbmF0dXJl");
                String json = "{\"success\":true,\"job\":{\"id\":\"job-1\",\"status\":\"completed\"},\"skin\":{\"uuid\":\"u\",\"variant\":\"slim\","
                        + "\"visibility\":\"unlisted\",\"texture\":{\"data\":" + data + "}}}";
                reply(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
            }
        });
        stub.start();
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();
        String before = System.getProperty("voxelmind.mineskin");
        System.setProperty("voxelmind.mineskin", base);
        Path file = Skins.folder().resolve("vml-test-skin.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        try {
            Skins.Skin fromLink = Skins.resolve(base + "/moved.png", true).get(30, TimeUnit.SECONDS);
            helper.assertTrue(fromLink.value().equals("c2lnbmVkLXRleHR1cmVz") && fromLink.signature().equals("c2lnbmF0dXJl"), "signed textures from the link");
            helper.assertTrue(fromLink.sourceName().equals(base + "/moved.png"), "the source is what the player typed");
            Skins.Skin fromFile = Skins.resolve("vml-test-skin.png classic", false).get(30, TimeUnit.SECONDS);
            helper.assertTrue(fromFile.sourceName().equals("vml-test-skin.png"), "a file from the skins folder: " + fromFile.sourceName());
            helper.assertTrue(uploads.size() == 2, "two uploads: " + uploads.size());
            String first = uploads.get(0);
            helper.assertTrue(first.contains("name=\"variant\"\r\n\r\nslim\r\n"), "slim found in the image");
            helper.assertTrue(first.contains("name=\"visibility\"\r\n\r\nunlisted\r\n"), "not in MineSkin's public gallery");
            helper.assertTrue(first.contains("filename=\"skin.png\"") && first.contains("PNG"), "the PNG is uploaded as a file");
            helper.assertTrue(first.contains("UA=VoxelMind-Local/"), "MineSkin wants a User-Agent naming the app");
            helper.assertTrue(uploads.get(1).contains("name=\"variant\"\r\n\r\nclassic\r\n"), "\"classic\" after the file name wins");

            Throwable local = Skins.resolve(base + "/skin.png", false).handle((s, e) -> e).get(30, TimeUnit.SECONDS);
            helper.assertTrue(local != null && Skins.explain(local).contains("local network"), "other players cannot point the server into its network: " + local);
            Throwable missing = Skins.resolve("nope-not-here.png", false).handle((s, e) -> e).get(30, TimeUnit.SECONDS);
            helper.assertTrue(missing != null && Skins.explain(missing).contains("There is no nope-not-here.png"), "missing file named: " + missing);
            Throwable outside = Skins.resolve("..\\..\\options.png", false).handle((s, e) -> e).get(30, TimeUnit.SECONDS);
            helper.assertTrue(outside != null, "no file outside the skins folder");
            Throwable junk = Skins.resolve("not a skin at all", false).handle((s, e) -> e).get(30, TimeUnit.SECONDS);
            helper.assertTrue(junk != null && Skins.explain(junk).contains("is not a Minecraft account"), "explains the three forms: " + junk);
            helper.succeed();
        } catch (Exception e) {
            helper.fail("own skin: " + e);
        } finally {
            if (before == null) System.clearProperty("voxelmind.mineskin");
            else System.setProperty("voxelmind.mineskin", before);
            Files.deleteIfExists(file);
            stub.stop(0);
        }
    }

    @GameTest
    public void usageCountNamesOnlyTheKindOfService(GameTestHelper helper) {
        var config = new com.voxelmind.local.config.LocalConfig();
        helper.assertTrue(UsageStats.kind(config).equals("sparks"), "a new config is on Sparks");
        config.selectEndpoint(com.voxelmind.local.provider.Player2.WEB);
        helper.assertTrue(UsageStats.kind(config).equals("player2"), "Player2");
        config.selectEndpoint(com.voxelmind.local.config.LocalConfig.OPENROUTER_URL);
        helper.assertTrue(UsageStats.kind(config).equals("openrouter"), "OpenRouter");
        for (String url : new String[] {"http://localhost:11434/v1", "http://127.0.0.1:1234/v1", "http://192.168.1.20:11434/v1", "http://[::1]:11434/v1", "http://gpu-box.local:8080/v1"}) {
            config.selectEndpoint(url);
            helper.assertTrue(UsageStats.kind(config).equals("local"), url + " is a local model");
        }
        config.selectEndpoint("https://api.together.xyz/v1");
        helper.assertTrue(UsageStats.kind(config).equals("custom"), "another hosted endpoint");
        helper.assertTrue(config.usageStats && !config.usageStatsNoticed, "on by default, but nothing is sent before the notice");
        String notice = UsageStats.notice().getString();
        helper.assertTrue(notice.contains("anonymous") && notice.contains("[Turn off]"), "the notice says what and how to turn it off: " + notice);
        boolean turnsOff = UsageStats.notice().toFlatList().stream().anyMatch(part ->
                part.getStyle().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand run && run.command().equals("/vml stats off"));
        helper.assertTrue(turnsOff, "[Turn off] runs /vml stats off");
        helper.succeed();
    }

    private static void reply(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) ex.getResponseBody().write(body);
        ex.close();
    }
}
