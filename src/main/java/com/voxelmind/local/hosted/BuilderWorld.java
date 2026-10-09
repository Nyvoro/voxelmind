package com.voxelmind.local.hosted;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Builder's spatial maths and block checks, ported from the hosted brain: anchors, boxes, chunked
 * fills, the build geometry parser and placement, and the block scan behind find_blocks.
 */
public final class BuilderWorld {
    public static final int FILL_CAP = 32_768;
    public static final int MAX_FILL_VOLUME = 200_000;
    private static final Set<String> DENIED = Set.of("tnt", "lava", "flowing_lava", "fire", "soul_fire", "spawner", "trial_spawner",
            "creaking_heart", "command_block", "chain_command_block", "repeating_command_block", "structure_block", "structure_void",
            "jigsaw", "barrier", "light", "bedrock", "reinforced_deepslate", "end_portal", "end_portal_frame", "end_gateway",
            "nether_portal", "moving_piston", "piston_head", "bubble_column", "cave_air", "void_air", "frosted_ice", "water_cauldron",
            "lava_cauldron", "powder_snow_cauldron");
    private static final Pattern BLOCK = Pattern.compile("^[a-z][a-z0-9_]*(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*\\])?$");
    private static final Pattern NUMBERING = Pattern.compile("^(?:[-*]\\s+|\\d+[.)]\\s+)");

    private BuilderWorld() {}

    public record Box(BlockPos min, BlockPos max) {
        public int width() {
            return max.getX() - min.getX() + 1;
        }

        public int height() {
            return max.getY() - min.getY() + 1;
        }

        public int depth() {
            return max.getZ() - min.getZ() + 1;
        }

        public long volume() {
            return (long) width() * height() * depth();
        }

        public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Box(new BlockPos(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2)),
                    new BlockPos(Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)));
        }

        public String bounds() {
            return "(" + min.getX() + ", " + min.getY() + ", " + min.getZ() + ") to (" + max.getX() + ", " + max.getY() + ", " + max.getZ() + ")";
        }
    }

    /** Minecraft's yaw convention: 0 = south, 90 = west, ±180 = north, -90 = east. */
    public static String facing(float yaw) {
        double n = ((yaw % 360) + 360) % 360;
        if (n >= 315 || n < 45) return "south";
        if (n < 135) return "west";
        if (n < 225) return "north";
        return "east";
    }

    /** The area ahead of the owner along their facing, centred sideways on them. */
    public static Box ownerFront(BlockPos owner, String facing, int width, int depth, int height, int distance, int yOffset) {
        int baseY = owner.getY() + yOffset;
        int ox = owner.getX();
        int oz = owner.getZ();
        int sideLo = -Math.floorDiv(width - 1, 2);
        int x1;
        int x2;
        int z1;
        int z2;
        switch (facing) {
            case "north" -> {
                z2 = oz - distance;
                z1 = z2 - depth + 1;
                x1 = ox + sideLo;
                x2 = x1 + width - 1;
            }
            case "east" -> {
                x1 = ox + distance;
                x2 = x1 + depth - 1;
                z1 = oz + sideLo;
                z2 = z1 + width - 1;
            }
            case "west" -> {
                x2 = ox - distance;
                x1 = x2 - depth + 1;
                z1 = oz + sideLo;
                z2 = z1 + width - 1;
            }
            default -> {
                z1 = oz + distance;
                z2 = z1 + depth - 1;
                x1 = ox + sideLo;
                x2 = x1 + width - 1;
            }
        }
        return Box.of(x1, baseY, z1, x2, baseY + height - 1, z2);
    }

    /** A box centred on a reference block: width along X, depth along Z, height upward from it. */
    public static Box centred(BlockPos ref, int width, int depth, int height, int yOffset) {
        int xLo = ref.getX() - Math.floorDiv(width - 1, 2);
        int zLo = ref.getZ() - Math.floorDiv(depth - 1, 2);
        int y = ref.getY() + yOffset;
        return Box.of(xLo, y, zLo, xLo + width - 1, y + height - 1, zLo + depth - 1);
    }

    /** Splits a box into fill commands of at most {@link #FILL_CAP} cells: Y bands, then X slices. */
    public static List<String> chunkedFill(Box box, String block) {
        List<String> out = new ArrayList<>();
        int w = box.width();
        int d = box.depth();
        int yStep = Math.max(1, FILL_CAP / (w * d));
        for (int y = box.min().getY(); y <= box.max().getY(); y += yStep) {
            int yTop = Math.min(box.max().getY(), y + yStep - 1);
            int bandH = yTop - y + 1;
            int xStep = Math.max(1, FILL_CAP / (bandH * d));
            for (int x = box.min().getX(); x <= box.max().getX(); x += xStep) {
                int xTop = Math.min(box.max().getX(), x + xStep - 1);
                out.add("fill " + x + " " + y + " " + box.min().getZ() + " " + xTop + " " + yTop + " " + box.max().getZ() + " " + block);
            }
        }
        return out;
    }

    /** Why a block may not be placed by the Builder, or null when it may. */
    public static @Nullable String refuseBlock(String block) {
        String b = block.toLowerCase(Locale.ROOT).trim();
        if (b.startsWith("minecraft:")) b = b.substring(10);
        if (!BLOCK.matcher(b).matches()) return "\"" + block + "\" is not a block id";
        String id = b.contains("[") ? b.substring(0, b.indexOf('[')) : b;
        if (DENIED.contains(id) || id.startsWith("infested_")) return "\"" + id + "\" is not available for building";
        Identifier key = Identifier.tryParse("minecraft:" + id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) return "\"" + id + "\" is not a block in this version";
        return null;
    }

    public static String cleanBlock(String block) {
        String b = block.toLowerCase(Locale.ROOT).trim();
        return b.startsWith("minecraft:") ? b.substring(10) : b;
    }

    // ---- build geometry ----

    public record Op(boolean fill, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        Op shift(int dx, int dy, int dz) {
            return new Op(fill, x1 + dx, y1 + dy, z1 + dz, x2 + dx, y2 + dy, z2 + dz, block);
        }

        String command() {
            return fill ? "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block
                    : "setblock " + x1 + " " + y1 + " " + z1 + " " + block;
        }
    }

    public record Geometry(List<Op> ops, List<String> rejected) {}

    /** Parses the geometry model's lines; unusable lines are kept aside instead of sinking the design. */
    public static Geometry parse(String text) {
        List<Op> ops = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("```")) continue;
            if (line.startsWith("/")) line = line.substring(1);
            Matcher m = NUMBERING.matcher(line);
            if (m.find()) line = line.substring(m.end());
            String[] t = line.trim().split("\\s+");
            try {
                if (t[0].equals("fill") && t.length == 8) {
                    int[] c = ints(t, 1, 6);
                    String reason = refuseBlock(t[7]);
                    if (reason != null) {
                        rejected.add(line + " (" + reason + ")");
                        continue;
                    }
                    ops.add(new Op(true, c[0], c[1], c[2], c[3], c[4], c[5], cleanBlock(t[7])));
                } else if (t[0].equals("setblock") && t.length == 5) {
                    int[] c = ints(t, 1, 3);
                    String reason = refuseBlock(t[4]);
                    if (reason != null) {
                        rejected.add(line + " (" + reason + ")");
                        continue;
                    }
                    ops.add(new Op(false, c[0], c[1], c[2], c[0], c[1], c[2], cleanBlock(t[4])));
                } else {
                    rejected.add(line);
                }
            } catch (NumberFormatException e) {
                rejected.add(line + " (coordinates must be integers within ±40)");
            }
        }
        return new Geometry(ops, rejected);
    }

    private static int[] ints(String[] t, int from, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = Integer.parseInt(t[from + i]);
            if (Math.abs(out[i]) > 40) throw new NumberFormatException("out of range");
        }
        return out;
    }

    public record Placement(Box target, List<List<String>> layers) {}

    /** Places the design ahead of the owner without rotating it, with site clearing and a floor first. */
    public static Placement place(List<Op> ops, BlockPos owner, String facing, int distance) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Op op : ops) {
            minX = Math.min(minX, Math.min(op.x1(), op.x2()));
            minY = Math.min(minY, Math.min(op.y1(), op.y2()));
            minZ = Math.min(minZ, Math.min(op.z1(), op.z2()));
            maxX = Math.max(maxX, Math.max(op.x1(), op.x2()));
            maxY = Math.max(maxY, Math.max(op.y1(), op.y2()));
            maxZ = Math.max(maxZ, Math.max(op.z1(), op.z2()));
        }
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int height = maxY - minY + 1;
        boolean ns = facing.equals("north") || facing.equals("south");
        Box target = ownerFront(owner, facing, ns ? width : depth, ns ? depth : width, height, distance, 0);
        int dx = target.min().getX() - minX;
        int dy = target.min().getY() - minY;
        int dz = target.min().getZ() - minZ;
        List<String> clear = chunkedFill(Box.of(target.min().getX(), target.min().getY(), target.min().getZ(),
                target.min().getX() + width - 1, target.min().getY() + height + 1, target.min().getZ() + depth - 1), "air");
        List<String> floor = List.of("fill " + target.min().getX() + " " + (target.min().getY() - 1) + " " + target.min().getZ() + " "
                + (target.min().getX() + width - 1) + " " + (target.min().getY() - 1) + " " + (target.min().getZ() + depth - 1) + " grass_block");
        List<String> design = new ArrayList<>();
        for (Op op : ops) design.add(op.shift(dx, dy, dz).command());
        Box placed = Box.of(target.min().getX(), target.min().getY(), target.min().getZ(),
                target.min().getX() + width - 1, target.min().getY() + height - 1, target.min().getZ() + depth - 1);
        return new Placement(placed, List.of(clear, floor, design));
    }

    // ---- find_blocks ----

    public record Hit(BlockPos pos, double distance) {}

    public static @Nullable Predicate<BlockState> matcher(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (q.startsWith("#")) {
            Identifier id = Identifier.tryParse(q.substring(1).contains(":") ? q.substring(1) : "minecraft:" + q.substring(1));
            if (id == null) return null;
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
            return state -> state.is(tag);
        }
        Identifier id = Identifier.tryParse(q.contains(":") ? q : "minecraft:" + q);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        return state -> state.is(block);
    }

    public static List<Hit> scan(ServerLevel level, BlockPos center, int radius, Predicate<BlockState> match) {
        List<Hit> hits = new ArrayList<>();
        int dyMax = Math.min(24, radius);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -dyMax; dy <= dyMax; dy++) {
                    p.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (p.getY() < level.getMinY() || p.getY() > level.getMaxY()) continue;
                    if (!level.isLoaded(p)) continue;
                    if (match.test(level.getBlockState(p))) hits.add(new Hit(p.immutable(), Math.sqrt(dx * dx + dy * dy + dz * dz)));
                }
            }
        }
        hits.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        return hits;
    }

    /** S, SE, E, NE, N, NW, W, SW from a horizontal offset. */
    public static String compass(int dx, int dz) {
        if (dx == 0 && dz == 0) return "here";
        double angle = Math.toDegrees(Math.atan2(dx, dz));
        String[] names = {"S", "SE", "E", "NE", "N", "NW", "W", "SW"};
        int index = (int) Math.round(((angle % 360) + 360) % 360 / 45.0) % 8;
        return names[index];
    }
}
