package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** equip: wear armor, hold a tool or weapon, or put a shield in the off hand. Takes one tick. */
public final class EquipAction extends BodyAction {
    private final String itemText;

    public EquipAction(String call, String itemText) {
        super("equip", call);
        this.itemText = itemText;
    }

    @Override
    protected void onStart(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        Item item = Names.item(itemText);
        if (item == null) {
            failed("'" + itemText + "' is not an item name");
            return;
        }
        int slot = Inv.find(body, s -> s.is(item));
        if (slot < 0) {
            if (body.getOffhandItem().is(item)) {
                done(Names.id(item) + " is already in your off hand");
                return;
            }
            for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                if (body.getItemBySlot(es).is(item)) {
                    done("you already wear " + Names.id(item) + " (" + es.getName() + ")");
                    return;
                }
            }
            failed("you carry no " + Names.id(item));
            return;
        }
        ItemStack stack = body.getInventory().getItem(slot);
        EquipmentSlot target = body.getEquipmentSlotForItem(stack);
        if (item == Items.SHIELD) target = EquipmentSlot.OFFHAND;
        if (target.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || target == EquipmentSlot.OFFHAND) {
            ItemStack old = body.getItemBySlot(target).copy();
            body.setItemSlot(target, stack.copy());
            body.getInventory().setItem(slot, old);
            done("now wearing " + Names.id(item) + " in " + target.getName() + (old.isEmpty() ? "" : "; " + Names.id(old.getItem()) + " went to your inventory"));
            return;
        }
        Inv.holdSlot(body, slot);
        if (body.getMainHandItem().is(item)) done("now holding " + Names.stack(body.getMainHandItem()));
        else failed("could not take " + Names.id(item) + " into your hand");
    }

    @Override
    protected void onTick(ActionContext ctx) {
    }
}
