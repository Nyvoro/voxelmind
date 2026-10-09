package com.voxelmind.local.world;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Reading and arranging a player's inventory the way a player's hands would. */
public final class Inv {
    private Inv() {}

    /** Main inventory slots 0..35 (hotbar 0..8). */
    public static final int MAIN_SIZE = 36;

    public static int count(Player player, Item item) {
        return count(player, s -> s.is(item));
    }

    public static int count(Player player, Predicate<ItemStack> match) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && match.test(s)) total += s.getCount();
        }
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty() && match.test(off)) total += off.getCount();
        return total;
    }

    /** Item counts across main inventory and offhand. */
    public static Map<Item, Integer> snapshot(Player player) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty()) out.merge(off.getItem(), off.getCount(), Integer::sum);
        return out;
    }

    /** Positive differences between two snapshots. */
    public static Map<Item, Integer> gained(Map<Item, Integer> before, Map<Item, Integer> after) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : after.entrySet()) {
            int delta = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (delta > 0) out.put(e.getKey(), delta);
        }
        return out;
    }

    public static String describe(Map<Item, Integer> counts) {
        if (counts.isEmpty()) return "nothing";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            if (!out.isEmpty()) out.append(", ");
            out.append(e.getValue()).append(' ').append(Names.id(e.getKey()));
        }
        return out.toString();
    }

    public static int find(Player player, Predicate<ItemStack> match) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && match.test(s)) return i;
        }
        return -1;
    }

    /** Puts the stack in {@code slot} into the hand, the way picking it in the hotbar would. */
    public static void holdSlot(Player player, int slot) {
        Inventory inv = player.getInventory();
        if (Inventory.isHotbarSlot(slot)) {
            inv.setSelectedSlot(slot);
        } else {
            inv.pickSlot(slot);
        }
    }

    /** Holds the first matching stack in the main hand; false when none is carried. */
    public static boolean hold(Player player, Predicate<ItemStack> match) {
        if (match.test(player.getMainHandItem()) && !player.getMainHandItem().isEmpty()) return true;
        int slot = find(player, match);
        if (slot < 0) return false;
        holdSlot(player, slot);
        return match.test(player.getMainHandItem());
    }

    public static int usedSlots(Player player) {
        int used = 0;
        for (int i = 0; i < MAIN_SIZE; i++) if (!player.getInventory().getItem(i).isEmpty()) used++;
        return used;
    }

    public static String equipment(Player player) {
        StringBuilder out = new StringBuilder();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = player.getItemBySlot(slot);
            if (s.isEmpty()) continue;
            if (!out.isEmpty()) out.append(", ");
            out.append(slot.getName()).append(": ").append(Names.stack(s));
        }
        return out.isEmpty() ? "none" : out.toString();
    }

    /** Every main-inventory stack, in slot order. */
    public static String list(Player player) {
        StringBuilder out = new StringBuilder();
        Inventory inv = player.getInventory();
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            out.append("  ").append(Names.stack(s));
            if (i == inv.getSelectedSlot()) out.append("  [in hand]");
            out.append('\n');
        }
        return out.toString();
    }
}
