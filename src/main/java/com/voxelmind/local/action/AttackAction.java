package com.voxelmind.local.action;

import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Targets;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

import java.util.ArrayList;
import java.util.List;

/**
 * attack: fights one creature or player. Against a hostile mob the encounter
 * continues with the next hostile within 5 blocks until none is left.
 */
public final class AttackAction extends BodyAction {
    private static final int MAX_ENCOUNTER = 6;
    private final String targetText;
    private Fighter fighter;
    private boolean encounter;
    private final List<String> killed = new ArrayList<>();
    private String weapon = "";

    public AttackAction(String call, String targetText) {
        super("attack", call);
        this.targetText = targetText;
    }

    @Override
    protected void onStart(ActionContext ctx) {
        Targets.Resolution res = Targets.resolve(ctx.body(), ctx.ownerId(), targetText, false, false);
        if (!(res.target() instanceof Targets.EntityTarget et) || !(et.entity() instanceof LivingEntity living)) {
            failed("cannot attack '" + targetText + "': " + (res.target() == null ? res.error() : "it is not a living creature"));
            return;
        }
        if (living == ctx.body()) {
            failed("you cannot attack yourself");
            return;
        }
        encounter = living instanceof Enemy;
        weapon = ctx.body().getMainHandItem().isEmpty() ? "bare hands" : Names.id(ctx.body().getMainHandItem().getItem());
        fighter = new Fighter(ctx, nav, living);
    }

    @Override
    protected void onTick(ActionContext ctx) {
        Fighter.State s = fighter.tick();
        String name = Names.entity(fighter.target());
        switch (s) {
            case KILLED -> {
                killed.add(name);
                if (encounter && killed.size() < MAX_ENCOUNTER) {
                    LivingEntity next = Targets.nearestLiving(ctx.body(), e -> e instanceof Enemy && e != ctx.body(), 5.0);
                    if (next != null) {
                        fighter = new Fighter(ctx, nav, next);
                        return;
                    }
                }
                done("killed " + String.join(", ", killed) + " with " + weapon + "; HP " + hp(ctx));
            }
            case LOST -> {
                if (!killed.isEmpty()) done("killed " + String.join(", ", killed) + "; then lost sight of " + name + "; HP " + hp(ctx));
                else failed("lost " + name + " after " + fighter.hits() + " hits; HP " + hp(ctx));
            }
            case UNREACHABLE -> failed("couldn't physically reach " + name + " for combat after " + fighter.hits() + " hits; HP " + hp(ctx));
            default -> {
            }
        }
    }

    /** The creature being fought now, or null before the action started. */
    public @org.jspecify.annotations.Nullable LivingEntity target() {
        return fighter == null ? null : fighter.target();
    }

    private static String hp(ActionContext ctx) {
        return Math.round(ctx.body().getHealth()) + "/20";
    }

    @Override
    public String progress() {
        if (fighter == null) return "";
        return "fighting " + Names.entity(fighter.target()) + " (" + fighter.hits() + " hits"
                + (killed.isEmpty() ? "" : ", killed " + String.join(", ", killed)) + ")";
    }
}
