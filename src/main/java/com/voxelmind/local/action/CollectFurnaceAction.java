package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/** collect_furnace: takes finished items out of the nearest furnace that has some (or one at given coordinates). */
public final class CollectFurnaceAction extends BodyAction {
    private final String posText;
    private BlockPos furnace;
    private boolean walking;

    public CollectFurnaceAction(String call, String posText) {
        super("collect_furnace", call);
        this.posText = posText == null ? "" : posText.trim();
    }

    @Override
    protected void onStart(ActionContext ctx) {
        if (!posText.isEmpty()) {
            furnace = Names.coords(posText);
            if (furnace == null) failed("pos must be \"x y z\"");
            return;
        }
        furnace = Targets.nearestBlock(ctx.level(), ctx.body().blockPosition(),
                (p, s) -> ctx.level().getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f && !f.getItem(2).isEmpty(), 24);
        if (furnace == null) failed("no furnace with finished items within 24 blocks");
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        if (Moves.eyeDistance(nav.feet(), furnace) > 4.3) {
            if (!walking) {
                walking = true;
                nav.navigate(furnace, f -> Moves.eyeDistance(f, furnace) <= 3.8 && !f.equals(furnace));
            }
            if (nav.tick() == Navigator.Status.FAILED) failed("could not reach the furnace at " + Names.pos(furnace) + ": " + nav.failure());
            return;
        }
        nav.stop();
        Moves.lookAt(body, Vec3.atCenterOf(furnace));
        if (!(ctx.level().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity f)) {
            failed("there is no furnace at " + Names.pos(furnace));
            return;
        }
        ItemStack result = f.getItem(2);
        String state = "it still holds " + Names.stack(f.getItem(0)) + " to smelt and fuel " + Names.stack(f.getItem(1));
        if (result.isEmpty()) {
            failed("the furnace at " + Names.pos(furnace) + " has nothing finished; " + state);
            return;
        }
        String what = Names.stack(result);
        ItemStack copy = result.copy();
        body.getInventory().add(copy);
        f.setItem(2, copy);
        f.awardUsedRecipesAndPopExperience(body);
        done("took " + what + (copy.isEmpty() ? "" : " (" + copy.getCount() + " did not fit)") + " from the furnace at " + Names.pos(furnace) + "; " + state);
    }
}
