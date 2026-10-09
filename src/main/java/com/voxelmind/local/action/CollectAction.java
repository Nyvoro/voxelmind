package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** collect: picks up dropped items within 16 blocks — all of them, or one named item. */
public final class CollectAction extends BodyAction {
    private static final double RANGE = 16;
    private final String itemText;
    private Item item;
    private Map<Item, Integer> before;
    private ItemEntity current;
    private int idle;

    public CollectAction(String call, String itemText) {
        super("collect", call);
        this.itemText = itemText == null ? "" : itemText.trim();
    }

    @Override
    protected void onStart(ActionContext ctx) {
        if (!itemText.isEmpty()) {
            item = Names.item(itemText);
            if (item == null) {
                failed("'" + itemText + "' is not an item name");
                return;
            }
        }
        before = Inv.snapshot(ctx.body());
        if (candidates(ctx).isEmpty()) {
            failed("no dropped " + (item == null ? "items" : Names.id(item)) + " within " + (int) RANGE + " blocks");
        }
    }

    private List<ItemEntity> candidates(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        return ctx.level().getEntitiesOfClass(ItemEntity.class, new AABB(body.blockPosition()).inflate(RANGE),
                e -> e.isAlive() && (item == null || e.getItem().is(item)));
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        if (body.getInventory().getFreeSlot() < 0 && ticks % 20 == 0) {
            Map<Item, Integer> gained = Inv.gained(before, Inv.snapshot(body));
            done("inventory is full; picked up " + Inv.describe(gained));
            return;
        }
        if (current == null || !current.isAlive()) {
            List<ItemEntity> items = candidates(ctx);
            if (items.isEmpty()) {
                if (++idle > 15) {
                    Map<Item, Integer> gained = Inv.gained(before, Inv.snapshot(body));
                    if (gained.isEmpty()) failed("picked up nothing");
                    else done("picked up " + Inv.describe(gained));
                }
                return;
            }
            idle = 0;
            current = items.stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElseThrow();
            nav.navigateNear(current.blockPosition(), 0.8);
        }
        if (nav.tick() == Navigator.Status.FAILED) {
            current = null;
        } else if (nav.status() == Navigator.Status.ARRIVED && current != null && body.distanceToSqr(current) > 2.0) {
            nav.navigateNear(current.blockPosition(), 0.5);
        }
        if (ticks > 20 * 45) {
            Map<Item, Integer> gained = Inv.gained(before, Inv.snapshot(body));
            done("stopped after 45s; picked up " + Inv.describe(gained));
        }
    }

    @Override
    public String progress() {
        if (ctx == null || before == null) return "";
        return "collecting; so far picked up " + Inv.describe(Inv.gained(before, Inv.snapshot(ctx.body())));
    }
}
