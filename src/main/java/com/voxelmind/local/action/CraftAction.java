package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * craft: makes items from materials in the inventory with the world's recipes.
 * 3×3 recipes use a crafting table within 24 blocks; if there is none and one is
 * carried, it is placed next to the body first.
 */
public final class CraftAction extends BodyAction {
    private final String itemText;
    private final int amount;
    private Item item;
    private List<RecipeHolder<CraftingRecipe>> recipes;
    private int made;
    private final Map<Item, Integer> consumed = new LinkedHashMap<>();
    private BlockPos table;
    private String tableNote = "";
    private boolean walking;
    private int cooldown;

    public CraftAction(String call, String itemText, int amount) {
        super("craft", call);
        this.itemText = itemText;
        this.amount = Math.max(1, Math.min(64, amount));
    }

    @Override
    protected void onStart(ActionContext ctx) {
        item = Names.item(itemText);
        if (item == null) {
            failed("'" + itemText + "' is not an item name");
            return;
        }
        recipes = Crafting.recipesFor(ctx.level(), item);
        if (recipes.isEmpty()) {
            String id = Names.id(item);
            boolean smelted = id.endsWith("_ingot") || id.startsWith("cooked_") || id.equals("glass") || id.equals("charcoal")
                    || id.equals("smooth_stone") || id.equals("brick");
            failed("no crafting recipe makes " + id + (smelted ? "; it is made by smelting in a furnace" : ""));
        }
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        if (made >= amount) {
            finish(ctx, null);
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        Map<Item, String> firstMissing = null;
        Crafting.Plan plan = null;
        for (RecipeHolder<CraftingRecipe> r : recipes) {
            Map<Item, String> missing = new LinkedHashMap<>();
            plan = Crafting.plan(body, r, missing);
            if (plan != null) break;
            if (firstMissing == null || (!missing.isEmpty() && missing.size() < firstMissing.size())) firstMissing = missing;
        }
        if (plan == null) {
            String needs = firstMissing == null || firstMissing.isEmpty() ? "the recipe's materials" : String.join("; ", firstMissing.values());
            finish(ctx, "not enough materials for " + Names.id(item) + ": needs " + needs);
            return;
        }
        if (plan.needsTable() && !nearTable(ctx)) return;
        if (plan.needsTable()) Moves.lookAt(body, Vec3.atCenterOf(table));
        Map<Item, Integer> used = Crafting.craftOnce(body, plan);
        used.forEach((k, v) -> consumed.merge(k, v, Integer::sum));
        made += plan.result().getCount();
        cooldown = 4;
    }

    /** True when a crafting table is within reach; otherwise moves toward one or places a carried one. */
    private boolean nearTable(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        if (table != null && !ctx.level().getBlockState(table).is(Blocks.CRAFTING_TABLE)) table = null;
        if (table == null) {
            table = Targets.nearestBlock(ctx.level(), body.blockPosition(), s -> s.is(Blocks.CRAFTING_TABLE), 24);
            if (table == null) {
                if (Inv.count(body, Items.CRAFTING_TABLE) == 0) {
                    finish(ctx, "this recipe needs a crafting table: none within 24 blocks and you carry none");
                    return false;
                }
                BlockPos spot = Placing.freeSpotNextTo(body, ctx.level());
                if (spot == null || !Placing.place(body, ctx.level(), Items.CRAFTING_TABLE, spot)) {
                    finish(ctx, "this recipe needs a crafting table and there was no free spot next to you to place yours");
                    return false;
                }
                table = spot;
                tableNote = "placed your crafting_table at " + Names.pos(spot) + "; ";
                return false;
            }
        }
        if (Moves.eyeDistance(nav.feet(), table) <= 4.3) {
            nav.stop();
            walking = false;
            return true;
        }
        if (!walking) {
            walking = true;
            nav.navigate(table, f -> Moves.eyeDistance(f, table) <= 3.8 && !f.equals(table));
        }
        if (nav.tick() == Navigator.Status.FAILED) finish(ctx, "could not reach the crafting table at " + Names.pos(table) + ": " + nav.failure());
        return false;
    }

    private void finish(ActionContext ctx, String stop) {
        String text = tableNote + "crafted " + made + " " + Names.id(item)
                + (consumed.isEmpty() ? "" : " (used " + Inv.describe(consumed) + ")")
                + "; you now carry " + Inv.count(ctx.body(), item) + " " + Names.id(item);
        if (stop == null) done(text);
        else if (made > 0) done(text + "; stopped: " + stop);
        else failed("crafted nothing: " + stop);
    }

    @Override
    public String progress() {
        return item == null ? "" : "crafting " + Names.id(item) + ", " + made + "/" + amount + " made";
    }
}
