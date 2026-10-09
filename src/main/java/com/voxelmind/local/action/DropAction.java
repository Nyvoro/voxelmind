package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Targets;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * drop: throws items out of the inventory, toward a player when one is named.
 * The result reports what left the inventory; whether anyone picked it up is not observed here.
 */
public final class DropAction extends BodyAction {
    private final String itemText;
    private final int requested;
    private final String targetText;
    private Item item;
    private Entity recipient;
    private int before;
    private boolean thrown;

    public DropAction(String call, String itemText, int count, String targetText) {
        super("drop", call);
        this.itemText = itemText;
        this.requested = count;
        this.targetText = targetText == null ? "" : targetText.trim();
    }

    @Override
    protected void onStart(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        item = Names.item(itemText);
        if (item == null) {
            failed("'" + itemText + "' is not an item name");
            return;
        }
        before = Inv.count(body, item);
        if (before == 0) {
            failed("you carry no " + Names.id(item));
            return;
        }
        if (!targetText.isEmpty() && !targetText.equalsIgnoreCase("here")) {
            Targets.Resolution res = Targets.resolve(body, ctx.ownerId(), targetText, false, false);
            if (res.target() instanceof Targets.EntityTarget et) recipient = et.entity();
            else {
                failed("cannot hand items to '" + targetText + "': " + res.error());
                return;
            }
        }
    }

    /** Items thrown to a player are meant for that player, like /give's overflow drops. */
    private void reserve(net.minecraft.world.entity.item.ItemEntity entity) {
        if (entity != null && recipient instanceof net.minecraft.world.entity.player.Player player) entity.setTarget(player.getUUID());
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        if (recipient != null && !thrown) {
            double distance = body.distanceTo(recipient);
            if (distance > 1.4) {
                if (nav.status() != com.voxelmind.local.body.Navigator.Status.MOVING || ticks % 20 == 0) nav.navigateNear(recipient.blockPosition(), 1.0);
                com.voxelmind.local.body.Navigator.Status s = nav.tick();
                if (s == com.voxelmind.local.body.Navigator.Status.FAILED && distance > 3.0) {
                    failed("could not walk to " + Names.entity(recipient) + ": " + nav.failure());
                    return;
                }
                if (ticks > 20 * 30 && distance > 3.0) {
                    failed("could not get close to " + Names.entity(recipient));
                    return;
                }
                // Close enough once the route ends within throwing range.
                if (s == com.voxelmind.local.body.Navigator.Status.MOVING || distance > 3.0) return;
            }
            nav.stop();
            // Aim at the feet so the throw lands where they stand.
            Vec3 aim = recipient.position().add(0, 0.2, 0);
            Moves.lookAt(body, aim);
            if (!Moves.aimed(body, aim) && ticks < 60) return;
        }
        int toDrop = requested < 0 ? before : Math.min(requested, before);
        int left = toDrop;
        for (int i = 0; i < Inv.MAIN_SIZE && left > 0; i++) {
            ItemStack s = body.getInventory().getItem(i);
            if (s.isEmpty() || !s.is(item)) continue;
            int n = Math.min(left, s.getCount());
            ItemStack out = body.getInventory().removeItem(i, n);
            reserve(body.drop(out, false, true));
            left -= n;
        }
        ItemStack off = body.getOffhandItem();
        if (left > 0 && off.is(item)) {
            int n = Math.min(left, off.getCount());
            reserve(body.drop(off.split(n), false, true));
            left -= n;
        }
        thrown = true;
        int after = Inv.count(body, item);
        String to = recipient != null ? " toward " + Names.entity(recipient) + " (" + Math.round(body.distanceTo(recipient)) + "m away"
                + (recipient instanceof net.minecraft.world.entity.player.Player ? "; only they can pick it up" : "") + ")" : " on the ground";
        done("dropped " + (before - after) + "× " + Names.id(item) + to + " (" + after + " left); pickup not observed");
    }
}
