package com.voxelmind.local.action;

import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Shaped and shapeless crafting from a player's own inventory, with the real recipe book of the world. */
final class Crafting {
    private static final Map<MinecraftServer, Map<Item, List<RecipeHolder<CraftingRecipe>>>> INDEX = new WeakHashMap<>();

    private Crafting() {}

    /** A filled grid ready to craft once. */
    record Plan(RecipeHolder<CraftingRecipe> recipe, int width, int height, List<ItemStack> cells, boolean needsTable, ItemStack result) {}

    /** Why no plan could be made: the closest recipe and what is missing for it. */
    record Shortfall(String text) {}

    @SuppressWarnings("unchecked")
    static synchronized List<RecipeHolder<CraftingRecipe>> recipesFor(ServerLevel level, Item item) {
        Map<Item, List<RecipeHolder<CraftingRecipe>>> index = INDEX.computeIfAbsent(level.getServer(), server -> {
            Map<Item, List<RecipeHolder<CraftingRecipe>>> map = new HashMap<>();
            for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
                if (!(holder.value() instanceof ShapedRecipe) && !(holder.value() instanceof ShapelessRecipe)) continue;
                ItemStack result = hypotheticalResult((CraftingRecipe) holder.value());
                if (result.isEmpty()) continue;
                map.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add((RecipeHolder<CraftingRecipe>) holder);
            }
            return map;
        });
        return index.getOrDefault(item, List.of());
    }

    private static List<Optional<Ingredient>> cellsOf(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) return Compat.shapedCells(shaped);
        List<Optional<Ingredient>> out = new ArrayList<>();
        for (Ingredient i : Compat.ingredients(recipe)) out.add(Optional.of(i));
        return out;
    }

    private static int[] dims(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) return new int[]{shaped.getWidth(), shaped.getHeight()};
        int n = Compat.ingredients(recipe).size();
        if (n <= 1) return new int[]{1, 1};
        if (n <= 4) return new int[]{2, 2};
        return new int[]{3, 3};
    }

    private static ItemStack hypotheticalResult(CraftingRecipe recipe) {
        try {
            int[] d = dims(recipe);
            List<Optional<Ingredient>> cells = cellsOf(recipe);
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < d[0] * d[1]; i++) {
                if (i < cells.size() && cells.get(i).isPresent()) {
                    Optional<Holder<Item>> first = Compat.ingredientItems(cells.get(i).get()).findFirst();
                    items.add(first.map(h -> new ItemStack(h.value())).orElse(ItemStack.EMPTY));
                } else {
                    items.add(ItemStack.EMPTY);
                }
            }
            return Compat.assemble(recipe, CraftingInput.of(d[0], d[1], items));
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * Picks materials from the inventory for one craft of {@code recipe}, preferring
     * the most plentiful matching stack for each cell. Returns null when short.
     */
    static @Nullable Plan plan(Player player, RecipeHolder<CraftingRecipe> holder, Map<Item, String> missingOut) {
        CraftingRecipe recipe = holder.value();
        int[] d = dims(recipe);
        List<Optional<Ingredient>> cells = cellsOf(recipe);
        Map<Item, Integer> carried = Inv.snapshot(player);
        Map<Item, Integer> available = new HashMap<>(carried);
        describeNeeds(cells, carried, missingOut);
        List<ItemStack> grid = new ArrayList<>();
        boolean ok = true;
        for (int i = 0; i < d[0] * d[1]; i++) {
            if (i >= cells.size() || cells.get(i).isEmpty()) {
                grid.add(ItemStack.EMPTY);
                continue;
            }
            Ingredient ingredient = cells.get(i).get();
            Item pick = null;
            int best = 0;
            for (Map.Entry<Item, Integer> e : available.entrySet()) {
                if (e.getValue() > best && ingredient.test(new ItemStack(e.getKey()))) {
                    best = e.getValue();
                    pick = e.getKey();
                }
            }
            if (pick == null) {
                ok = false;
                grid.add(ItemStack.EMPTY);
                continue;
            }
            available.merge(pick, -1, Integer::sum);
            grid.add(new ItemStack(pick));
        }
        if (!ok) return null;
        CraftingInput input = CraftingInput.of(d[0], d[1], grid);
        if (!recipe.matches(input, player.level())) return null;
        ItemStack result = Compat.assemble(recipe, input);
        if (result.isEmpty()) return null;
        boolean needsTable = d[0] > 2 || d[1] > 2;
        return new Plan(holder, d[0], d[1], grid, needsTable, result);
    }

    /**
     * For every ingredient the recipe needs more of than is carried, one line like
     * "3 planks (any variant, e.g. oak_planks) — you have 2".
     */
    private static void describeNeeds(List<Optional<Ingredient>> cells, Map<Item, Integer> carried, Map<Item, String> out) {
        Map<Ingredient, Integer> needed = new LinkedHashMap<>();
        for (Optional<Ingredient> cell : cells) cell.ifPresent(i -> needed.merge(i, 1, Integer::sum));
        for (Map.Entry<Ingredient, Integer> e : needed.entrySet()) {
            Ingredient ingredient = e.getKey();
            int have = 0;
            for (Map.Entry<Item, Integer> c : carried.entrySet()) if (ingredient.test(new ItemStack(c.getKey()))) have += c.getValue();
            if (have >= e.getValue()) continue;
            Item first = Compat.ingredientItems(ingredient).findFirst().map(Holder::value).orElse(null);
            if (first == null) continue;
            String name = Names.id(first);
            long variants = Compat.ingredientItems(ingredient).count();
            String label = variants > 1 ? name.replaceFirst("^[a-z]+_", "") + " (any variant, e.g. " + name + ")" : name;
            out.put(first, e.getValue() + " " + label + " — you have " + have);
        }
    }

    /** Removes the planned materials, adds the result and any remainders (e.g. empty buckets). */
    static Map<Item, Integer> craftOnce(Player player, Plan plan) {
        Map<Item, Integer> consumed = new LinkedHashMap<>();
        CraftingInput input = CraftingInput.of(plan.width(), plan.height(), plan.cells());
        NonNullList<ItemStack> remainders = plan.recipe().value().getRemainingItems(input);
        for (ItemStack cell : plan.cells()) {
            if (cell.isEmpty()) continue;
            int slot = Inv.find(player, s -> s.is(cell.getItem()));
            if (slot >= 0) {
                player.getInventory().removeItem(slot, 1);
            } else if (player.getOffhandItem().is(cell.getItem())) {
                player.getOffhandItem().shrink(1);
            }
            consumed.merge(cell.getItem(), 1, Integer::sum);
        }
        ItemStack result = plan.result().copy();
        if (!player.getInventory().add(result)) player.drop(result, false);
        for (ItemStack rest : remainders) {
            if (!rest.isEmpty() && !player.getInventory().add(rest.copy())) player.drop(rest.copy(), false);
        }
        return consumed;
    }
}
