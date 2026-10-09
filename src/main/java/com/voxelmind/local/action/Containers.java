package com.voxelmind.local.action;

import com.voxelmind.local.world.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Access to chest-like containers the way a player's open inventory screen moves items. */
final class Containers {
    private Containers() {}

    static @Nullable Container open(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) {
            return ChestBlock.getContainer(chest, state, level, pos, false);
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    static Map<Item, Integer> contents(Container c) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return out;
    }

    /** Moves up to {@code count} of {@code item} into the container; returns how many moved. */
    static int insert(Container c, ItemStack source, int count) {
        int moved = 0;
        for (int i = 0; i < c.getContainerSize() && moved < count && !source.isEmpty(); i++) {
            ItemStack slot = c.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, source) && slot.getCount() < slot.getMaxStackSize()) {
                int n = Math.min(Math.min(count - moved, source.getCount()), slot.getMaxStackSize() - slot.getCount());
                slot.grow(n);
                source.shrink(n);
                moved += n;
            }
        }
        for (int i = 0; i < c.getContainerSize() && moved < count && !source.isEmpty(); i++) {
            if (c.getItem(i).isEmpty() && c.canPlaceItem(i, source)) {
                int n = Math.min(count - moved, Math.min(source.getCount(), source.getMaxStackSize()));
                c.setItem(i, source.split(n));
                moved += n;
            }
        }
        if (moved > 0) c.setChanged();
        return moved;
    }

    static String describe(Map<Item, Integer> contents) {
        if (contents.isEmpty()) return "empty";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Item, Integer> e : contents.entrySet()) {
            if (!out.isEmpty()) out.append(", ");
            out.append(Names.id(e.getKey())).append(" ×").append(e.getValue());
        }
        return out.toString();
    }
}
