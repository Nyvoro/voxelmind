package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * smelt: loads a furnace within 24 blocks (or places a carried one) with an input
 * and a named fuel, waits next to it and takes the results. Leaving early keeps
 * the rest in the furnace; collect_furnace takes it out later.
 */
public final class SmeltAction extends BodyAction {
    private final String itemText;
    private final int amount;
    private final String fuelText;
    private Item input;
    private Item output;
    private Item fuel;
    private int toSmelt;
    private int cookTicks = 200;
    private BlockPos furnace;
    private String note = "";
    private boolean walking;
    private boolean loaded;
    private int taken;
    private int waitTicks;

    public SmeltAction(String call, String itemText, int amount, String fuelText) {
        super("smelt", call);
        this.itemText = itemText;
        this.amount = amount;
        this.fuelText = fuelText == null ? "" : fuelText.trim();
    }

    @Override
    protected void onStart(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        ServerLevel level = ctx.level();
        Item named = Names.item(itemText);
        if (named == null) {
            failed("'" + itemText + "' is not an item name");
            return;
        }
        List<RecipeHolder<?>> recipes = new ArrayList<>();
        for (RecipeHolder<?> h : level.getServer().getRecipeManager().getRecipes()) if (h.value() instanceof SmeltingRecipe) recipes.add(h);
        // The named item is either what goes in or what should come out.
        for (RecipeHolder<?> h : recipes) {
            SmeltingRecipe r = (SmeltingRecipe) h.value();
            if (Compat.smeltInput(r).test(new ItemStack(named))) {
                input = named;
                output = Compat.assemble(r, new SingleRecipeInput(new ItemStack(named))).getItem();
                cookTicks = Compat.cookingTime(r);
                break;
            }
        }
        if (input == null) {
            Set<Item> candidates = new LinkedHashSet<>();
            for (RecipeHolder<?> h : recipes) {
                SmeltingRecipe r = (SmeltingRecipe) h.value();
                Compat.ingredientItems(Compat.smeltInput(r)).forEach(holder -> {
                    Item in = holder.value();
                    if (Inv.count(body, in) > 0 && Compat.assemble(r, new SingleRecipeInput(new ItemStack(in))).is(named)) candidates.add(in);
                });
            }
            if (candidates.isEmpty()) {
                failed("you carry nothing that smelts into " + Names.id(named) + " (and " + Names.id(named) + " itself does not smelt)");
                return;
            }
            if (candidates.size() > 1) {
                StringBuilder list = new StringBuilder();
                for (Item c : candidates) list.append(list.isEmpty() ? "" : ", ").append(Names.id(c));
                failed("several carried items smelt into " + Names.id(named) + ": " + list + "; name the one to smelt");
                return;
            }
            input = candidates.iterator().next();
            output = named;
            for (RecipeHolder<?> h : recipes) {
                SmeltingRecipe r = (SmeltingRecipe) h.value();
                if (Compat.smeltInput(r).test(new ItemStack(input))) cookTicks = Compat.cookingTime(r);
            }
        }
        int carried = Inv.count(body, input);
        if (carried == 0) {
            failed("you carry no " + Names.id(input));
            return;
        }
        toSmelt = Math.min(carried, amount <= 0 ? Math.min(carried, 64) : Math.min(amount, 64));

        Set<Item> fuels = new LinkedHashSet<>();
        for (int i = 0; i < Inv.MAIN_SIZE; i++) {
            ItemStack s = body.getInventory().getItem(i);
            if (!s.isEmpty() && Compat.isFuel(level, s) && !s.is(input)) fuels.add(s.getItem());
        }
        if (!fuelText.isEmpty()) {
            fuel = Names.item(fuelText);
            if (fuel == null || !fuels.contains(fuel)) {
                failed("you carry no '" + fuelText + "' that burns");
                return;
            }
        } else if (fuels.size() == 1) {
            fuel = fuels.iterator().next();
        } else if (fuels.isEmpty()) {
            failed("you carry no fuel (coal, charcoal, planks, logs, sticks…)");
            return;
        } else {
            StringBuilder list = new StringBuilder();
            for (Item f : fuels) list.append(list.isEmpty() ? "" : ", ").append(Names.id(f));
            failed("name the fuel to burn; you carry: " + list);
            return;
        }

        furnace = Targets.nearestBlock(level, body.blockPosition(), s -> s.is(Blocks.FURNACE), 24);
        if (furnace == null) {
            if (Inv.count(body, Items.FURNACE) == 0) {
                failed("no furnace within 24 blocks and you carry none (a furnace is crafted from 8 cobblestone)");
                return;
            }
            BlockPos spot = Placing.freeSpotNextTo(body, level);
            if (spot == null || !Placing.place(body, level, Items.FURNACE, spot)) {
                failed("there was no free spot next to you to place your furnace");
                return;
            }
            furnace = spot;
            note = "placed your furnace at " + Names.pos(spot) + "; ";
        }
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        ServerLevel level = ctx.level();
        if (Moves.eyeDistance(nav.feet(), furnace) > 4.3) {
            if (!walking) {
                walking = true;
                nav.navigate(furnace, f -> Moves.eyeDistance(f, furnace) <= 3.8 && !f.equals(furnace));
            }
            if (nav.tick() == Navigator.Status.FAILED) failed(note + "could not reach the furnace at " + Names.pos(furnace) + ": " + nav.failure());
            return;
        }
        nav.stop();
        Moves.lookAt(body, Vec3.atCenterOf(furnace));
        if (!(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity f)) {
            failed("the furnace at " + Names.pos(furnace) + " is gone");
            return;
        }
        if (!loaded) {
            ItemStack in = f.getItem(0);
            if (!in.isEmpty() && !in.is(input)) {
                failed(note + "the furnace at " + Names.pos(furnace) + " is busy smelting " + Names.stack(in));
                return;
            }
            int burn = Compat.burnDuration(level, new ItemStack(fuel));
            int fuelNeeded = Math.max(1, (int) Math.ceil(toSmelt * (double) cookTicks / Math.max(1, burn)));
            ItemStack fuelSlot = f.getItem(1);
            if (!fuelSlot.isEmpty() && !fuelSlot.is(fuel)) fuelNeeded = 0;
            moveInto(body, f, 0, input, toSmelt);
            if (fuelNeeded > 0) moveInto(body, f, 1, fuel, fuelNeeded);
            f.setChanged();
            loaded = true;
        }
        ItemStack result = f.getItem(2);
        if (!result.isEmpty()) {
            int n = result.getCount();
            ItemStack copy = result.copy();
            if (body.getInventory().add(copy)) {
                f.setItem(2, ItemStack.EMPTY);
                taken += n;
            } else {
                result.setCount(copy.getCount());
                taken += n - copy.getCount();
            }
            f.awardUsedRecipesAndPopExperience(body);
        }
        if (taken >= toSmelt || (f.getItem(0).isEmpty() && f.getItem(2).isEmpty() && taken > 0)) {
            done(note + "smelted " + taken + " " + Names.id(input) + " into " + Names.id(output) + "; you now carry "
                    + Inv.count(body, output) + " " + Names.id(output));
            return;
        }
        if (++waitTicks > toSmelt * cookTicks + 400) {
            failed(note + "the furnace stopped: " + taken + " of " + toSmelt + " done; it holds " + Names.stack(f.getItem(0))
                    + " and fuel " + Names.stack(f.getItem(1)));
        }
    }

    private static void moveInto(CompanionPlayer body, AbstractFurnaceBlockEntity f, int slot, Item item, int count) {
        int left = count;
        for (int i = 0; i < Inv.MAIN_SIZE && left > 0; i++) {
            ItemStack s = body.getInventory().getItem(i);
            if (s.isEmpty() || !s.is(item)) continue;
            ItemStack target = f.getItem(slot);
            int room = target.isEmpty() ? s.getMaxStackSize() : target.getMaxStackSize() - target.getCount();
            int n = Math.min(Math.min(left, s.getCount()), room);
            if (n <= 0) break;
            if (target.isEmpty()) f.setItem(slot, s.split(n));
            else {
                target.grow(n);
                s.shrink(n);
            }
            left -= n;
        }
    }

    @Override
    public String progress() {
        if (input == null) return "";
        return "smelting " + Names.id(input) + " at " + (furnace == null ? "?" : Names.pos(furnace)) + ", " + taken + " of " + toSmelt + " done";
    }
}
