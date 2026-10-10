package com.voxelmind.local.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.function.Predicate;

/** Resource words the model uses ("wood", "stone", "iron", "beef") mapped to blocks, creatures and items. */
public final class Resources {
    private Resources() {}

    private static final Set<String> STONE = Set.of("stone", "cobblestone", "deepslate", "cobbled_deepslate", "granite", "diorite",
            "andesite", "tuff", "blackstone", "calcite");
    private static final Set<String> ORE_DROPS = Set.of("coal", "raw_iron", "raw_copper", "raw_gold", "diamond", "redstone",
            "lapis_lazuli", "emerald", "quartz", "gold_nugget", "ancient_debris");

    /** A gatherable resource: blocks to mine or a creature to hunt, and which picked-up items count toward the amount. */
    public record Resource(String label, @Nullable Predicate<BlockState> blocks, @Nullable EntityType<?> creature,
                           Predicate<Item> counts, boolean needsTool) {}

    public static @Nullable Resource parse(String raw) {
        String s = Names.normalize(raw);
        switch (s) {
            case "wood", "log", "logs", "tree", "trees", "timber" -> {
                return new Resource("wood (logs)", st -> st.is(BlockTags.LOGS), null,
                        item -> Block.byItem(item).defaultBlockState().is(BlockTags.LOGS), false);
            }
            case "stone", "rock", "cobblestone", "cobble" -> {
                return new Resource("stone", st -> STONE.contains(Names.id(st.getBlock())), null,
                        item -> STONE.contains(Names.id(item)), true);
            }
            case "ore", "ores" -> {
                return new Resource("ore", st -> isOre(st.getBlock()), null, item -> ORE_DROPS.contains(Names.id(item)), true);
            }
            case "beef", "leather", "cow", "meat" -> {
                return new Resource("beef/leather (cow)", null, EntityTypes.COW, item -> Set.of("beef", "leather").contains(Names.id(item)), false);
            }
            case "porkchop", "pork", "pig" -> {
                return new Resource("porkchop (pig)", null, EntityTypes.PIG, item -> Names.id(item).equals("porkchop"), false);
            }
            case "mutton", "wool", "sheep" -> {
                return new Resource("mutton/wool (sheep)", null, EntityTypes.SHEEP,
                        item -> Names.id(item).equals("mutton") || Names.id(item).endsWith("_wool"), false);
            }
            case "chicken", "feather", "feathers" -> {
                return new Resource("chicken/feather", null, EntityTypes.CHICKEN,
                        item -> Set.of("chicken", "feather").contains(Names.id(item)), false);
            }
            default -> {
            }
        }
        for (String metal : new String[]{"coal", "iron", "copper", "gold", "diamond", "redstone", "lapis", "emerald"}) {
            if (s.equals(metal) || s.equals(metal + "_ore") || s.equals("raw_" + metal)) {
                String drop = switch (metal) {
                    case "iron", "copper", "gold" -> "raw_" + metal;
                    case "lapis" -> "lapis_lazuli";
                    default -> metal;
                };
                return new Resource(metal + " ore", st -> isOre(st.getBlock()) && Names.id(st.getBlock()).contains(metal), null,
                        item -> Names.id(item).equals(drop), true);
            }
        }
        Block block = Names.block(s);
        if (block != null) {
            Item asItem = block.asItem();
            // A crop counts its produce (wheat, beetroot, carrot), not the seeds its block stands for as an item.
            Item produce = block instanceof CropBlock ? Names.item(Names.id(block)) : null;
            Predicate<Item> counts = produce != null ? item -> item == produce : item -> item == asItem || Block.byItem(item) == block;
            return new Resource(Names.id(block), st -> st.is(block), null, counts, block.defaultBlockState().requiresCorrectToolForDrops());
        }
        return null;
    }

    public static boolean isOre(Block block) {
        String id = Names.id(block);
        return id.endsWith("_ore") || id.equals("ancient_debris");
    }

    /** A block a player could see and reach: at least one side touches a non-solid cell. */
    public static boolean exposed(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }
}
