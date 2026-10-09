package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.Set;

/** eat: eats one piece of a named food the body carries. */
public final class EatAction extends BodyAction {
    private final String itemText;
    private Item food;
    private int foodBefore;
    private float hpBefore;
    private int countBefore;
    private boolean started;

    public EatAction(String call, String itemText) {
        super("eat", call);
        this.itemText = itemText == null ? "" : itemText;
    }

    @Override
    protected void onStart(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        Set<Item> foods = new LinkedHashSet<>();
        for (int i = 0; i < Inv.MAIN_SIZE; i++) {
            ItemStack s = body.getInventory().getItem(i);
            if (!s.isEmpty() && s.has(DataComponents.FOOD)) foods.add(s.getItem());
        }
        if (foods.isEmpty()) {
            failed("you carry no food");
            return;
        }
        if (itemText.isBlank()) {
            if (foods.size() > 1) {
                StringBuilder names = new StringBuilder();
                for (Item f : foods) names.append(names.isEmpty() ? "" : ", ").append(Names.id(f));
                failed("name which food to eat; you carry: " + names);
                return;
            }
            food = foods.iterator().next();
        } else {
            food = Names.item(itemText);
            if (food == null || !foods.contains(food)) {
                failed("you carry no '" + itemText + "' to eat");
                return;
            }
        }
        foodBefore = body.getFoodData().getFoodLevel();
        hpBefore = body.getHealth();
        countBefore = Inv.count(body, food);
        if (!Inv.hold(body, s -> s.is(food))) {
            failed("could not take " + Names.id(food) + " into your hand");
            return;
        }
        ItemStack stack = body.getMainHandItem();
        InteractionResult result = body.gameMode.useItem(body, ctx.level(), stack, InteractionHand.MAIN_HAND);
        if (!body.isUsingItem()) {
            if (body.getFoodData().getFoodLevel() >= 20 && result != InteractionResult.CONSUME) {
                failed("you are not hungry (food 20/20); " + Names.id(food) + " can only be eaten when hungry");
            } else {
                finishReport(ctx);
            }
            return;
        }
        started = true;
    }

    @Override
    protected void onTick(ActionContext ctx) {
        if (started && !ctx.body().isUsingItem()) finishReport(ctx);
        if (ticks > 100) failed("eating did not finish");
    }

    private void finishReport(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        int after = Inv.count(body, food);
        if (after >= countBefore) {
            failed("did not eat " + Names.id(food) + " (food " + body.getFoodData().getFoodLevel() + "/20)");
            return;
        }
        done("ate " + Names.id(food) + " (food " + foodBefore + "→" + body.getFoodData().getFoodLevel() + "; " + after
                + " left; HP " + Math.round(hpBefore) + "→" + Math.round(body.getHealth()) + ")");
    }

    @Override
    public String progress() {
        return food == null ? "" : "eating " + Names.id(food);
    }
}
