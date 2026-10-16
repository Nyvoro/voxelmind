package com.voxelmind.local.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.config.LocalConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A companion's skin: borrowed from an existing Minecraft account, or the player's own PNG (a link or a file in the
 * skins folder). Players' games only show a skin Mojang signed, so an own PNG is signed through MineSkin
 * (mineskin.org), which puts it on one of its Minecraft accounts and hands back the signed textures. It is uploaded
 * as "unlisted": not in MineSkin's gallery, though anyone with the texture link can see the image, as with every skin.
 */
public final class Skins {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Executor EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoxelMind Local skins");
        t.setDaemon(true);
        return t;
    });
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern FILE = Pattern.compile("[^/\\\\:*?\"<>|]{1,80}\\.png", Pattern.CASE_INSENSITIVE);
    /** A skin is 64×64 pixels and a few KB; anything far larger is not a skin. */
    private static final int MAX_BYTES = 1 << 20;
    /** MineSkin takes PNGs from 1 KB to 20 KB. */
    private static final int MINESKIN_MIN = 1024;
    private static final int MINESKIN_MAX = 20_000;

    public record Skin(String sourceName, String value, String signature) {}

    /** A failure whose message is meant for the player as it is. */
    public static final class SkinException extends RuntimeException {
        public SkinException(String message) {
            super(message);
        }
    }

    private Skins() {}

    /** The folder for own skin files, next to the game's worlds: {@code .minecraft/voxelmind-local/skins}. */
    public static Path folder() {
        return FabricLoader.getInstance().getGameDir().resolve("voxelmind-local").resolve("skins");
    }

    /**
     * Resolves what a player typed: a Minecraft account name, a PNG link (http or https) or the name of a PNG in
     * {@link #folder()}, optionally followed by "slim" or "classic" for the arm width of an own PNG.
     * {@code localNetwork}: links may point into this PC or the home network (only for the host or an owner-level
     * operator, so other players cannot make a server fetch addresses inside its network).
     */
    public static CompletableFuture<Skin> resolve(String typed, boolean localNetwork) {
        String source = typed.trim();
        String variant = null;
        String lower = source.toLowerCase(Locale.ROOT);
        for (String v : new String[] {"slim", "classic"}) {
            if (lower.endsWith(" " + v)) {
                variant = v;
                source = source.substring(0, source.length() - v.length()).trim();
                break;
            }
        }
        String text = source;
        String arms = variant;
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return CompletableFuture.supplyAsync(() -> download(text, localNetwork), EXEC).thenCompose(png -> sign(png, text, arms));
        }
        if (FILE.matcher(source).matches()) {
            return CompletableFuture.supplyAsync(() -> readFile(text), EXEC).thenCompose(png -> sign(png, text, arms));
        }
        if (ACCOUNT.matcher(source).matches()) return fetch(source);
        return CompletableFuture.failedFuture(new SkinException("\"" + source + "\" is not a Minecraft account (3-16 letters, digits, _), "
                + "a PNG link (https://…png) or a .png file from " + folder()));
    }

    /** Looks up the account's current skin at Mojang. */
    public static CompletableFuture<Skin> fetch(String username) {
        String url = "https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(username, StandardCharsets.UTF_8);
        return get(url).thenCompose(profile -> {
            if (profile == null || !profile.has("id")) throw new SkinException("There is no Minecraft account named " + username + ".");
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

    // ---- own PNGs ----

    private static byte[] readFile(String name) {
        Path dir = folder().toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir)) throw new SkinException("Only files directly in " + dir + " can be used.");
        try {
            Files.createDirectories(dir);
            if (!Files.isRegularFile(file)) throw new SkinException("There is no " + name + " in " + dir + ". Put the PNG there and try again.");
            if (Files.size(file) > MAX_BYTES) throw new SkinException(name + " is larger than 1 MB; a skin is a 64×64 PNG of a few KB.");
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new SkinException("Could not read " + file + ": " + e.getMessage());
        }
    }

    private static byte[] download(String url, boolean localNetwork) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SkinException("That is not a valid link.");
        }
        for (int hop = 0; hop < 4; hop++) {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) throw new SkinException("Only http and https links work.");
            if (!localNetwork && inLocalNetwork(uri.getHost())) {
                throw new SkinException("Links into a local network only work for the host of the world.");
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent()).header("Accept", "image/png,image/*;q=0.8").GET().build();
            HttpResponse<InputStream> response;
            try {
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new SkinException("Could not load " + uri.getHost() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SkinException("The download was interrupted.");
            }
            int status = response.statusCode();
            if (status / 100 == 3) {
                String location = response.headers().firstValue("location").orElse(null);
                closeQuietly(response.body());
                if (location == null) throw new SkinException(uri.getHost() + " answered with a redirect without a target.");
                uri = uri.resolve(location);
                continue;
            }
            try (InputStream in = response.body()) {
                if (status / 100 != 2) throw new SkinException(uri.getHost() + " answered HTTP " + status + " for the link.");
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new SkinException("The file behind the link is larger than 1 MB; a skin is a 64×64 PNG of a few KB.");
                return bytes;
            } catch (IOException e) {
                throw new SkinException("Could not load " + uri.getHost() + ": " + e.getMessage());
            }
        }
        throw new SkinException("The link redirects too often.");
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /** This PC, the home network or another address that is not on the internet. */
    static boolean inLocalNetwork(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                byte[] b = a.getAddress();
                boolean uniqueLocal6 = b.length == 16 && (b[0] & 0xfe) == 0xfc;
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || uniqueLocal6) return true;
            }
            return false;
        } catch (UnknownHostException e) {
            throw new SkinException("The address " + host + " was not found.");
        }
    }

    /** A checked skin image, ready for MineSkin. */
    public record Png(byte[] bytes, String variant) {}

    /**
     * Checks that the bytes are a 64×64 (or old 64×32) PNG and finds the arm width: slim skins leave the outer
     * columns of the right arm (x 54-55, y 20-31) empty. MineSkin wants 1-20 KB, so a very small or very large file
     * is written again as a plain RGBA PNG.
     */
    public static Png check(byte[] bytes, String variant) {
        if (bytes.length < 8 || (bytes[0] & 0xff) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
            throw new SkinException("That is not a PNG image. Use the skin file itself (.png), for example from a skin editor.");
        }
        java.awt.image.BufferedImage image;
        try {
            image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new SkinException("The PNG could not be read: " + e.getMessage());
        } catch (LinkageError e) {
            // A Java without image support: MineSkin checks the image itself.
            return new Png(bytes, variant == null ? "classic" : variant);
        }
        if (image == null) throw new SkinException("The PNG could not be read.");
        int w = image.getWidth();
        int h = image.getHeight();
        if (w != 64 || h != 64 && h != 32) throw new SkinException("A skin is 64×64 pixels (or the old 64×32); this image is " + w + "×" + h + ".");
        String arms = variant;
        if (arms == null) {
            boolean slim = h == 64;
            for (int x = 54; x <= 55 && slim; x++) {
                for (int y = 20; y <= 31 && slim; y++) slim = (image.getRGB(x, y) >>> 24) == 0;
            }
            arms = slim ? "slim" : "classic";
        }
        if (bytes.length >= MINESKIN_MIN && bytes.length <= MINESKIN_MAX) return new Png(bytes, arms);
        return new Png(rewrite(image, bytes.length < MINESKIN_MIN), arms);
    }

    private static byte[] rewrite(java.awt.image.BufferedImage source, boolean uncompressed) {
        java.awt.image.BufferedImage argb = new java.awt.image.BufferedImage(source.getWidth(), source.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) argb.setRGB(x, y, source.getRGB(x, y));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("png").next();
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            if (uncompressed && param.canWriteCompressed()) {
                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(1.0f);
            }
            try (javax.imageio.stream.ImageOutputStream stream = javax.imageio.ImageIO.createImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new javax.imageio.IIOImage(argb, null, null), param);
            } finally {
                writer.dispose();
            }
        } catch (IOException e) {
            throw new SkinException("The PNG could not be prepared: " + e.getMessage());
        }
        return out.toByteArray();
    }

    /** Has MineSkin sign the PNG; finished jobs (also a skin MineSkin already knows) come back at once, others are polled. */
    static CompletableFuture<Skin> sign(byte[] bytes, String source, String variant) {
        Png png;
        try {
            png = check(bytes, variant);
        } catch (SkinException e) {
            return CompletableFuture.failedFuture(e);
        }
        String boundary = "VoxelMind" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            field(body, boundary, "variant", png.variant());
            field(body, boundary, "visibility", "unlisted");
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(png.bytes());
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        HttpRequest.Builder request = mineskin("/v2/queue")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        return HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .thenCompose(r -> answer(r, source, 0));
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static HttpRequest.Builder mineskin(String path) {
        // The voxelmind.mineskin property points the game tests at a local stand-in.
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(System.getProperty("voxelmind.mineskin", "https://api.mineskin.org") + path)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", userAgent()).header("Accept", "application/json");
        String key = LocalConfig.get().mineskinKey;
        if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key.trim());
        return b;
    }

    private static String userAgent() {
        String version = FabricLoader.getInstance().getModContainer(com.voxelmind.local.VoxelMindLocal.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        return "VoxelMind-Local/" + version + " (+https://voxel-mind.com)";
    }

    /** One MineSkin answer: the signed skin, a job still running (polled every 2 s for up to a minute), or an error. */
    private static CompletableFuture<Skin> answer(HttpResponse<String> r, String source, int polls) {
        if (r.statusCode() == 429) {
            long wait = r.headers().firstValueAsLong("retry-after").orElse(10);
            throw new CompletionException(new SkinException("MineSkin is busy right now. Try again in about " + Math.max(wait, 5) + " seconds."));
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(r.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new CompletionException(new SkinException("MineSkin answered HTTP " + r.statusCode() + " without a readable reply."));
        }
        if (r.statusCode() / 100 != 2) throw new CompletionException(new SkinException("MineSkin: " + errors(root, r.statusCode())));
        if (root.has("skin") && root.get("skin").isJsonObject()) {
            JsonObject data = root.getAsJsonObject("skin").getAsJsonObject("texture").getAsJsonObject("data");
            return CompletableFuture.completedFuture(new Skin(source, data.get("value").getAsString(),
                    data.has("signature") && !data.get("signature").isJsonNull() ? data.get("signature").getAsString() : null));
        }
        JsonObject job = root.has("job") && root.get("job").isJsonObject() ? root.getAsJsonObject("job") : null;
        String status = job != null && job.has("status") ? job.get("status").getAsString() : "";
        if (job == null || status.equals("failed") || !job.has("id")) {
            throw new CompletionException(new SkinException("MineSkin could not sign the skin: " + errors(root, r.statusCode())));
        }
        if (polls >= 30) throw new CompletionException(new SkinException("MineSkin took longer than a minute. Try again later."));
        String id = URLEncoder.encode(job.get("id").getAsString(), StandardCharsets.UTF_8);
        return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS, EXEC))
                .thenCompose(ignored -> HTTP.sendAsync(mineskin("/v2/queue/" + id).GET().build(), HttpResponse.BodyHandlers.ofString()))
                .thenCompose(next -> answer(next, source, polls + 1));
    }

    private static String errors(JsonObject root, int status) {
        if (root.has("errors") && root.get("errors").isJsonArray() && !root.getAsJsonArray("errors").isEmpty()) {
            JsonObject first = root.getAsJsonArray("errors").get(0).getAsJsonObject();
            String message = first.has("message") ? first.get("message").getAsString() : "";
            String code = first.has("code") ? first.get("code").getAsString() : "";
            return message.isBlank() ? code : message;
        }
        return "HTTP " + status;
    }

    /** The message for the player, without the wrapping of the futures. */
    public static String explain(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof SkinException) return cause.getMessage();
        if (cause instanceof java.net.http.HttpTimeoutException) return "The service did not answer in time. Try again in a minute.";
        if (cause instanceof IOException) return "No connection: " + cause.getMessage();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
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
