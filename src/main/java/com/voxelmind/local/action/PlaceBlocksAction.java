package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Hands;
import com.voxelmind.local.body.Navigator;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * place_blocks: places a list of carried blocks, lowest first, walking into reach
 * as needed. Occupied cells, cells without a solid neighbour and blocks you run
 * out of are skipped; the result names each skipped cell with its reason, also
 * those the second pass did not retry.
 */
public final class PlaceBlocksAction extends BodyAction {
    /** Blocks per call the model can ask for. */
    public static final int MAX = 128;
    /** Cells listed per skip reason in the result; the rest are counted. */
    private static final int LISTED = 8;

    public record Entry(BlockPos pos, String block) {}

    private final List<Entry> requested;
    private final int limit;
    private final List<Entry> queue = new ArrayList<>();
    private int index;
    private int placed;
    /** Why each cell that is not placed was skipped, by cell, in the order they were skipped; kept across both passes. */
    private final Map<BlockPos, String> skipped = new LinkedHashMap<>();
    private final Map<Item, Integer> used = new LinkedHashMap<>();
    private boolean walking;
    private int settle;
    private int pass;
    private int alreadyThere;

    public PlaceBlocksAction(String call, List<Entry> entries) {
        this(call, entries, MAX);
    }

    /** With another limit than the model's per-call one, e.g. for build_shelter's whole plan. */
    public PlaceBlocksAction(String call, List<Entry> entries, int limit) {
        super("place_blocks", call);
        this.requested = entries;
        this.limit = limit;
    }

    @Override
    protected void onStart(ActionContext ctx) {
        if (requested.isEmpty()) {
            failed("no blocks given");
            return;
        }
        if (requested.size() > limit) {
            failed("at most " + limit + " blocks per call; got " + requested.size());
            return;
        }
        queue.addAll(requested);
        // Routes may dig through terrain, but never through the build itself or its materials.
        java.util.Set<Long> site = new java.util.HashSet<>();
        java.util.Set<net.minecraft.world.level.block.Block> materials = new java.util.HashSet<>();
        for (Entry e : requested) {
            site.add(e.pos().asLong());
            if (Names.item(e.block()) instanceof BlockItem bi) materials.add(bi.getBlock());
        }
        ServerLevel level = ctx.level();
        nav.protect(p -> site.contains(p.asLong()) || materials.contains(level.getBlockState(p).getBlock()));
        queue.sort(Comparator.comparingInt((Entry e) -> e.pos().getY())
                .thenComparingDouble(e -> e.pos().distSqr(ctx.body().blockPosition())));
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (Entry e : queue) {
            Item item = Names.item(e.block());
            if (!(item instanceof BlockItem)) {
                failed("'" + e.block() + "' is not a placeable block item");
                return;
            }
            need.merge(item, 1, Integer::sum);
        }
        StringBuilder short_ = new StringBuilder();
        for (Map.Entry<Item, Integer> n : need.entrySet()) {
            int have = Inv.count(ctx.body(), n.getKey());
            if (have < n.getValue()) short_.append(short_.isEmpty() ? "" : ", ").append(Names.id(n.getKey())).append(" needs ")
                    .append(n.getValue()).append(" (you carry ").append(have).append(")");
        }
        if (!short_.isEmpty() && need.size() == 1 && Inv.count(ctx.body(), need.keySet().iterator().next()) == 0) {
            failed("not enough blocks: " + short_);
        }
    }

    @Override
    protected void onTick(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        ServerLevel level = ctx.level();
        if (settle > 0) {
            settle--;
            return;
        }
        if (index >= queue.size()) {
            // A second pass places blocks that had no support until their neighbours existed.
            List<Entry> retry = new ArrayList<>();
            for (Entry e : queue) {
                Item item = Names.item(e.block());
                if (item instanceof BlockItem bi && !level.getBlockState(e.pos()).is(bi.getBlock()) && level.getBlockState(e.pos()).canBeReplaced()) retry.add(e);
            }
            if (pass++ == 0 && !retry.isEmpty() && retry.size() < queue.size()) {
                queue.clear();
                queue.addAll(retry);
                index = 0;
                for (Entry e : retry) skipped.remove(e.pos());
                return;
            }
            finish(ctx);
            return;
        }
        Entry e = queue.get(index);
        Item item = Names.item(e.block());
        BlockState current = level.getBlockState(e.pos());
        if (item instanceof BlockItem bi && current.is(bi.getBlock())) {
            if (pass == 0) alreadyThere++;
            index++;
            return;
        }
        if (!current.canBeReplaced()) {
            skip("occupied by " + Names.id(current.getBlock()));
            return;
        }
        if (Inv.count(body, item) == 0) {
            skip("ran out of " + Names.id(item));
            return;
        }
        if (body.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(e.pos()))) {
            // Step off the cell first.
            if (!walking) {
                walking = true;
                nav.navigate(e.pos(), f -> !f.equals(e.pos()) && !f.above().equals(e.pos()) && Moves.eyeDistance(f, e.pos()) <= 4.0);
            }
            if (nav.tick() != Navigator.Status.MOVING) {
                walking = false;
                if (nav.status() == Navigator.Status.FAILED) skip("you stood in the way");
            }
            return;
        }
        if (Moves.eyeDistance(nav.feet(), e.pos()) > 4.3) {
            if (!walking) {
                walking = true;
                nav.navigate(e.pos(), f -> Moves.eyeDistance(f, e.pos()) <= 3.9 && !f.equals(e.pos()) && !f.above().equals(e.pos()));
            }
            Navigator.Status s = nav.tick();
            if (s == Navigator.Status.FAILED) {
                walking = false;
                skip("could not get within reach");
            } else if (s == Navigator.Status.ARRIVED) {
                walking = false;
            }
            return;
        }
        walking = false;
        nav.stop();
        List<net.minecraft.world.entity.Entity> inTheWay = level.getEntities((net.minecraft.world.entity.Entity) null,
                new net.minecraft.world.phys.AABB(e.pos()), en -> en.isAlive() && en.blocksBuilding);
        if (!inTheWay.isEmpty()) {
            skip(Names.entity(inTheWay.getFirst()) + " stands there");
            return;
        }
        Moves.lookAt(body, Vec3.atCenterOf(e.pos()));
        if (Hands.place(body, level, item, e.pos())) {
            placed++;
            used.merge(item, 1, Integer::sum);
            index++;
            settle = 2;
        } else {
            skip("no solid neighbour to place against");
        }
    }

    private void skip(String reason) {
        skipped.put(queue.get(index).pos(), reason);
        index++;
    }

    private void finish(ActionContext ctx) {
        StringBuilder text = new StringBuilder("placed " + placed + " of " + requested.size() + " blocks");
        if (alreadyThere > 0) text.append("; ").append(alreadyThere).append(" were already in place");
        if (!used.isEmpty()) text.append(" (used ").append(Inv.describe(used)).append(')');
        if (!skipped.isEmpty()) {
            Map<String, List<BlockPos>> byReason = new LinkedHashMap<>();
            for (Map.Entry<BlockPos, String> s : skipped.entrySet()) byReason.computeIfAbsent(s.getValue(), r -> new ArrayList<>()).add(s.getKey());
            text.append("; skipped: ");
            int n = 0;
            for (Map.Entry<String, List<BlockPos>> r : byReason.entrySet()) {
                List<BlockPos> cells = r.getValue();
                text.append(n++ > 0 ? "; " : "").append(cells.size()).append("× ").append(r.getKey()).append(" at ");
                for (int i = 0; i < Math.min(LISTED, cells.size()); i++) text.append(i > 0 ? ", " : "").append(Names.pos(cells.get(i)));
                if (cells.size() > LISTED) text.append(" and ").append(cells.size() - LISTED).append(" more");
            }
        }
        text.append("; now standing at ").append(Names.pos(ctx.body().blockPosition()));
        if (placed > 0 || alreadyThere == requested.size()) done(text.toString());
        else failed(text.toString());
    }

    @Override
    public String progress() {
        return "placing blocks: " + placed + " of " + requested.size() + " placed";
    }
}
