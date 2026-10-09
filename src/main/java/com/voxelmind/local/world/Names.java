package com.voxelmind.local.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/** Minecraft ids as the model reads and writes them: {@code oak_log}, {@code zombie}, compass directions. */
public final class Names {
    private Names() {}

    public static String normalize(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
        return s;
    }

    public static @Nullable Item item(String raw) {
        String s = normalize(raw);
        for (String candidate : candidates(s)) {
            Identifier id = Identifier.tryParse(candidate.contains(":") ? candidate : "minecraft:" + candidate);
            if (id == null) continue;
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
            if (item.isPresent() && item.get() != Items.AIR) return item.get();
        }
        return null;
    }

    public static @Nullable Block block(String raw) {
        String s = normalize(raw);
        for (String candidate : candidates(s)) {
            Identifier id = Identifier.tryParse(candidate.contains(":") ? candidate : "minecraft:" + candidate);
            if (id == null) continue;
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isPresent() && block.get() != Blocks.AIR) return block.get();
        }
        return null;
    }

    public static @Nullable EntityType<?> entityType(String raw) {
        String s = normalize(raw);
        for (String candidate : candidates(s)) {
            Identifier id = Identifier.tryParse(candidate.contains(":") ? candidate : "minecraft:" + candidate);
            if (id == null) continue;
            Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (type.isPresent()) return type.get();
        }
        return null;
    }

    /** Also tries a singular form, so "oak_logs" finds "oak_log". */
    private static String[] candidates(String s) {
        if (s.endsWith("es") && s.length() > 3) return new String[]{s, s.substring(0, s.length() - 1), s.substring(0, s.length() - 2)};
        if (s.endsWith("s") && s.length() > 2) return new String[]{s, s.substring(0, s.length() - 1)};
        return new String[]{s};
    }

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    public static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    public static String id(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
    }

    public static String stack(ItemStack stack) {
        if (stack.isEmpty()) return "nothing";
        StringBuilder out = new StringBuilder(id(stack.getItem()));
        if (stack.getCount() > 1) out.append(" ×").append(stack.getCount());
        if (stack.isDamageableItem()) {
            int max = stack.getMaxDamage();
            int left = max - stack.getDamageValue();
            out.append(" (").append(Math.round(100.0 * left / max)).append("% — ").append(left).append('/').append(max).append(')');
        }
        return out.toString();
    }

    public static String entity(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.player.Player player) return player.getGameProfile().name();
        return id(entity.getType());
    }

    public static String pos(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    /** Eight-point compass direction from {@code from} toward {@code to}; north is -z. */
    public static String direction(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        if (dx * dx + dz * dz < 1.0) return "here";
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360;
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return names[(int) Math.round(angle / 45.0) % 8];
    }

    public static String facing(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        if (y >= 45 && y < 135) return "west";
        if (y >= 135 && y < 225) return "north";
        if (y >= 225 && y < 315) return "east";
        return "south";
    }

    /** Unit offset for a compass word, or null. */
    public static int @Nullable [] compass(String raw) {
        return switch (normalize(raw)) {
            case "north", "n" -> new int[]{0, -1};
            case "south", "s" -> new int[]{0, 1};
            case "east", "e" -> new int[]{1, 0};
            case "west", "w" -> new int[]{-1, 0};
            case "northeast", "north_east", "ne" -> new int[]{1, -1};
            case "northwest", "north_west", "nw" -> new int[]{-1, -1};
            case "southeast", "south_east", "se" -> new int[]{1, 1};
            case "southwest", "south_west", "sw" -> new int[]{-1, 1};
            default -> null;
        };
    }

    private static String[] numbers(String raw) {
        return raw.trim().replace("\"", " ").replace("'", " ").replace(",", " ").replace("(", " ").replace(")", " ").trim().split("\\s+");
    }

    /** Parses "x z" (commas, quotes and brackets allowed); null when it is not exactly two numbers. */
    public static int @Nullable [] coords2(String raw) {
        String[] parts = numbers(raw);
        if (parts.length != 2) return null;
        try {
            return new int[]{(int) Math.floor(Double.parseDouble(parts[0])), (int) Math.floor(Double.parseDouble(parts[1]))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses "x y z" (commas, quotes and brackets allowed). */
    public static @Nullable BlockPos coords(String raw) {
        String[] parts = numbers(raw);
        if (parts.length != 3) return null;
        try {
            return new BlockPos((int) Math.floor(Double.parseDouble(parts[0])), (int) Math.floor(Double.parseDouble(parts[1])),
                    (int) Math.floor(Double.parseDouble(parts[2])));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
