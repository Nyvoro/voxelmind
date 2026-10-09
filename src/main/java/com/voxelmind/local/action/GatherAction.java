package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import com.voxelmind.local.world.Resources;
import com.voxelmind.local.world.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * gather: collects a quantity of a resource by mining exposed matching blocks
 * nearby, or by hunting the creature that drops it. Counts what actually
 * arrives in the inventory.
 */
public final class GatherAction extends BodyAction {
    private static final int SEARCH = 24;
    private final String resourceText;
    private final int amount;
    private Resources.Resource resource;
    private Map<Item, Integer> start;
    private final Set<Long> skip = new HashSet<>();
    private BlockBreaker breaker;
    private Fighter fighter;
    private BlockPos killSpot;
    private int collectTicks;
    private int failures;
    private String lastFailure = "";
    private int blocksMined;
    private int kills;

    public GatherAction(String call, String resourceText, int amount) {
        super("gather", call);
        this.resourceText = resourceText;
        this.amount = Math.max(1, Math.min(64, amount));
    }

    @Override
    protected void onStart(ActionContext ctx) {
        resource = Resources.parse(resourceText);
        if (resource == null) {
            failed("'" + resourceText + "' is not a resource I can gather (try wood, stone, ore, coal, iron, a block name, beef, wool…)");
            return;
        }
        start = Inv.snapshot(ctx.body());
        if (resource.blocks() != null && resource.needsTool()) {
            BlockPos sample = Targets.nearestBlock(ctx.level(), ctx.body().blockPosition(), resource.blocks(), SEARCH);
            if (sample != null) {
                BlockState state = ctx.level().getBlockState(sample);
                if (!canHarvest(ctx.body(), state)) {
                    failed(Names.id(state.getBlock()) + " drops nothing without a suitable tool (a pickaxe of the right tier); you carry none");
                }
            }
        }
    }

    private static boolean canHarvest(CompanionPlayer body, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        for (int i = 0; i < Inv.MAIN_SIZE; i++) {
            ItemStack s = body.getInventory().getItem(i);
            if (!s.isEmpty() && s.isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    private Map<Item, Integer> counted(ActionContext ctx) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : Inv.gained(start, Inv.snapshot(ctx.body())).entrySet()) {
            if (resource.counts().test(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private int total(Map<Item, Integer> counts) {
        int n = 0;
        for (int v : counts.values()) n += v;
        return n;
    }

    @Override
    protected void onTick(ActionContext ctx) {
        Map<Item, Integer> counts = counted(ctx);
        int have = total(counts);
        if (have >= amount && breaker == null && collectTicks == 0) {
            done("inventory gained " + have + " of requested " + amount + " " + resource.label() + " (" + Inv.describe(counts) + ")");
            return;
        }
        if (ctx.body().getInventory().getFreeSlot() < 0 && Inv.usedSlots(ctx.body()) >= Inv.MAIN_SIZE) {
            stopPartial(ctx, counts, "your inventory is full");
            return;
        }

        if (breaker != null) {
            BlockBreaker.State s = breaker.tick();
            if (s == BlockBreaker.State.DONE) {
                blocksMined++;
                failures = 0;
                breaker = null;
            } else if (s == BlockBreaker.State.FAILED) {
                skip.add(breaker.target().asLong());
                lastFailure = breaker.failure();
                breaker = null;
                if (++failures >= 3) stopPartial(ctx, counts, "three attempts in a row failed, last: " + lastFailure);
            }
            return;
        }
        if (fighter != null) {
            Fighter.State s = fighter.tick();
            if (s == Fighter.State.KILLED) {
                kills++;
                killSpot = fighter.target().blockPosition();
                fighter = null;
                collectTicks = 60;
            } else if (s != Fighter.State.ENGAGING) {
                lastFailure = (s == Fighter.State.LOST ? "lost " : "could not reach ") + Names.entity(fighter.target());
                fighter = null;
                if (++failures >= 3) stopPartial(ctx, counts, lastFailure);
            }
            return;
        }
        if (collectTicks > 0) {
            collectDrops(ctx);
            return;
        }

        if (resource.blocks() != null) {
            BlockPos next = Targets.nearestBlock(ctx.level(), ctx.body().blockPosition(),
                    (p, st) -> resource.blocks().test(st) && !skip.contains(p.asLong()) && Resources.exposed(ctx.level(), p), SEARCH);
            if (next == null) {
                stopPartial(ctx, counts, "no more exposed " + resource.label() + " within " + SEARCH + " blocks");
                return;
            }
            breaker = new BlockBreaker(ctx, nav, next);
        } else {
            LivingEntity prey = Targets.nearestLiving(ctx.body(), e -> e.getType() == resource.creature() && !e.isBaby(), 40);
            if (prey == null) {
                stopPartial(ctx, counts, "no adult " + Names.id(resource.creature()) + " within 40 blocks");
                return;
            }
            fighter = new Fighter(ctx, nav, prey);
        }
    }

    private void collectDrops(ActionContext ctx) {
        collectTicks--;
        CompanionPlayer body = ctx.body();
        List<ItemEntity> items = ctx.level().getEntitiesOfClass(ItemEntity.class, new AABB(killSpot).inflate(5), ItemEntity::isAlive);
        if (items.isEmpty()) {
            collectTicks = 0;
            nav.stop();
            return;
        }
        ItemEntity nearest = items.stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElseThrow();
        if (nav.status() != Navigator.Status.MOVING) nav.navigateNear(nearest.blockPosition(), 0.6);
        nav.tick();
    }

    private void stopPartial(ActionContext ctx, Map<Item, Integer> counts, String reason) {
        int have = total(counts);
        String text = "inventory gained " + have + " of requested " + amount + " " + resource.label()
                + (counts.isEmpty() ? "" : " (" + Inv.describe(counts) + ")") + "; stopped: " + reason;
        if (have > 0) done(text);
        else failed(text);
    }

    @Override
    public String progress() {
        if (ctx == null || resource == null || start == null) return "";
        int have = total(counted(ctx));
        return "gathering " + resource.label() + ": " + have + " of " + amount + " so far"
                + (blocksMined > 0 ? ", " + blocksMined + " blocks mined" : "") + (kills > 0 ? ", " + kills + " killed" : "");
    }
}
