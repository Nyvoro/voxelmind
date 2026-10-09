package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.Vec3;

/** use_chest: view, store into or take out of the nearest chest or barrel (or one at given coordinates). */
public final class ChestAction extends BodyAction {
    private final String mode;
    private final String itemText;
    private final int count;
    private final String posText;
    private BlockPos chest;
    private boolean walking;

    public ChestAction(String call, String mode, String itemText, int count, String posText) {
        super("use_chest", call);
        this.mode = Names.normalize(mode == null ? "view" : mode);
        this.itemText = itemText == null ? "" : itemText.trim();
        this.count = count;
        this.posText = posText == null ? "" : posText.trim();
    }

    @Override
    protected void onStart(ActionContext ctx) {
        if (!mode.equals("view") && !mode.equals("store") && !mode.equals("retrieve")) {
            failed("action must be view, store or retrieve");
            return;
        }
        if (!mode.equals("view") && itemText.isEmpty()) {
            failed("name the item to " + mode);
            return;
        }
        if (!posText.isEmpty()) {
            chest = Names.coords(posText);
            if (chest == null) {
                failed("pos must be \"x y z\"");
                return;
            }
        } else {
            chest = Targets.nearestBlock(ctx.level(), ctx.body().blockPosition(),
                    (p, s) -> s.getBlock() instanceof ChestBlock || ctx.level().getBlockEntity(p) instanceof BarrelBlockEntity, 24);
            if (chest == null) failed("no chest or barrel within 24 blocks");
        }
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        if (Moves.eyeDistance(nav.feet(), chest) > 4.3) {
            if (!walking) {
                walking = true;
                nav.navigate(chest, f -> Moves.eyeDistance(f, chest) <= 3.8 && !f.equals(chest));
            }
            if (nav.tick() == Navigator.Status.FAILED) failed("could not reach the chest at " + Names.pos(chest) + ": " + nav.failure());
            return;
        }
        nav.stop();
        Moves.lookAt(body, Vec3.atCenterOf(chest));
        Container c = Containers.open(ctx.level(), chest);
        if (c == null) {
            failed("there is no chest at " + Names.pos(chest));
            return;
        }
        String where = " the chest at " + Names.pos(chest);
        switch (mode) {
            case "view" -> done(Names.pos(chest) + " contains: " + Containers.describe(Containers.contents(c)));
            case "store" -> {
                Item item = Names.item(itemText);
                if (item == null) {
                    failed("'" + itemText + "' is not an item name");
                    return;
                }
                int want = count < 0 ? Integer.MAX_VALUE : Math.max(1, count);
                int moved = 0;
                for (int i = 0; i < Inv.MAIN_SIZE && moved < want; i++) {
                    ItemStack s = body.getInventory().getItem(i);
                    if (s.isEmpty() || !s.is(item)) continue;
                    moved += Containers.insert(c, s, want - moved);
                }
                if (moved == 0) failed(Inv.count(body, item) == 0 ? "you carry no " + Names.id(item) : where + " is full");
                else done("stored " + moved + "× " + Names.id(item) + " in" + where + " (you still carry " + Inv.count(body, item) + ")");
            }
            default -> {
                Item item = Names.item(itemText);
                if (item == null) {
                    failed("'" + itemText + "' is not an item name");
                    return;
                }
                int want = count < 0 ? Integer.MAX_VALUE : Math.max(1, count);
                int moved = 0;
                for (int i = 0; i < c.getContainerSize() && moved < want; i++) {
                    ItemStack s = c.getItem(i);
                    if (s.isEmpty() || !s.is(item)) continue;
                    ItemStack taken = s.split(Math.min(want - moved, s.getCount()));
                    int n = taken.getCount();
                    if (!body.getInventory().add(taken)) {
                        s.grow(taken.getCount());
                        n -= taken.getCount();
                        moved += n;
                        break;
                    }
                    moved += n;
                }
                c.setChanged();
                if (moved == 0) failed(where + " has no " + Names.id(item) + " (or your inventory is full); it contains: " + Containers.describe(Containers.contents(c)));
                else done("took " + moved + "× " + Names.id(item) + " from" + where + " (you now carry " + Inv.count(body, item) + ")");
            }
        }
    }
}
