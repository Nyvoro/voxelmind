package com.voxelmind.local.action;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.body.Hands;
import com.voxelmind.local.world.Inv;
import com.voxelmind.local.world.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * build_shelter: a 5×5 hut with walls two blocks high, a two-high door gap and a
 * flat roof (55 blocks on level ground) of one carried material, centred on a ground
 * spot. The ground is found per column as survey finds it, so trees are not ground;
 * walls reach down to it where it dips, which takes more blocks. Nothing is built when
 * tree trunks or branches stand in the hut's cells: the result names them. Carried out
 * like place_blocks, without its per-call limit.
 */
public final class BuildShelterAction extends BodyAction {
    private final String materialText;
    private final String centerText;
    private PlaceBlocksAction builder;
    private String plan = "";

    public BuildShelterAction(String call, String material, String center) {
        super("build_shelter", call);
        this.materialText = material == null ? "" : material.trim();
        this.centerText = center == null ? "" : center.trim();
    }

    @Override
    protected void onStart(ActionContext ctx) {
        CompanionPlayer body = ctx.body();
        ServerLevel level = ctx.level();
        Item material;
        if (!materialText.isEmpty()) {
            material = Names.item(materialText);
            if (!(material instanceof BlockItem) || Inv.count(body, material) == 0) {
                failed("you carry no placeable '" + materialText + "'");
                return;
            }
        } else {
            Set<Item> options = new LinkedHashSet<>();
            for (int i = 0; i < Inv.MAIN_SIZE; i++) {
                ItemStack s = body.getInventory().getItem(i);
                if (!s.isEmpty() && (Hands.isScaffold(s) || Names.id(s.getItem()).endsWith("_planks")) && Inv.count(body, s.getItem()) >= 20) options.add(s.getItem());
            }
            if (options.size() != 1) {
                StringBuilder list = new StringBuilder();
                for (Item o : options) list.append(list.isEmpty() ? "" : ", ").append(Names.id(o)).append(" ×").append(Inv.count(body, o));
                failed(options.isEmpty() ? "you carry no building material (about 55 blocks of planks, cobblestone, dirt…)"
                        : "name the material to build with; you carry: " + list);
                return;
            }
            material = options.iterator().next();
        }

        BlockPos center;
        if (!centerText.isEmpty()) {
            int[] xz = Names.coords2(centerText);
            BlockPos xyz = Names.coords(centerText);
            if (xyz != null) center = new BlockPos(xyz.getX(), 0, xyz.getZ());
            else if (xz != null) center = new BlockPos(xz[0], 0, xz[1]);
            else {
                failed("center must be \"x z\" or \"x y z\"");
                return;
            }
        } else {
            Direction facing = Direction.fromYRot(body.getYRot());
            center = body.blockPosition().relative(facing, 4);
        }
        int base = Integer.MIN_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            base = Math.max(base, SurveyAction.ground(level, center.getX() + dx, center.getZ() + dz) + 1);
        }
        // Door on the side that faces the owner, or the body.
        ServerPlayer owner = ctx.owner();
        BlockPos faceTo = owner != null && owner.level() == level ? owner.blockPosition() : body.blockPosition();
        int ddx = faceTo.getX() - center.getX();
        int ddz = faceTo.getZ() - center.getZ();
        Direction doorSide = Math.abs(ddx) > Math.abs(ddz) ? (ddx > 0 ? Direction.EAST : Direction.WEST) : (ddz > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockPos door = center.relative(doorSide, 2);

        String id = Names.id(material);
        List<PlaceBlocksAction.Entry> entries = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (wall) {
                    int ground = SurveyAction.ground(level, x, z) + 1;
                    boolean isDoor = x == door.getX() && z == door.getZ();
                    for (int y = Math.min(ground, base); y <= base + 1; y++) {
                        if (isDoor && y >= base) continue;
                        entries.add(new PlaceBlocksAction.Entry(new BlockPos(x, y, z), id));
                    }
                }
                entries.add(new PlaceBlocksAction.Entry(new BlockPos(x, base + 2, z), id));
            }
        }
        String where = "a 5×5 shelter centred on (" + center.getX() + "," + base + "," + center.getZ() + ")";
        // Trees in the hut's cells (walls, roof and the room inside): a trunk or branch is no ground to build on or around.
        List<String> trees = new ArrayList<>();
        List<BlockPos> cells = new ArrayList<>();
        for (PlaceBlocksAction.Entry e : entries) cells.add(e.pos());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = base; y <= base + 1; y++) {
            cells.add(new BlockPos(center.getX() + dx, y, center.getZ() + dz));
        }
        cells.add(door);
        cells.add(door.above());
        for (BlockPos cell : cells) {
            BlockState state = level.getBlockState(cell);
            if (state.is(BlockTags.LOGS)) trees.add(Names.id(state.getBlock()) + " at " + Names.pos(cell));
        }
        if (!trees.isEmpty()) {
            failed("nothing built: tree trunks or branches stand in the cells of " + where + ": "
                    + String.join(", ", trees.subList(0, Math.min(8, trees.size()))) + (trees.size() > 8 ? " and " + (trees.size() - 8) + " more" : ""));
            return;
        }
        int needed = entries.size();
        int carried = Inv.count(body, material);
        if (carried < needed) {
            failed(where + " needs " + needed + " " + id + " (55 on level ground; walls reach down where the ground dips); you carry " + carried);
            return;
        }
        plan = "5×5 " + id + " shelter centred on (" + center.getX() + "," + base + "," + center.getZ() + "), door facing "
                + doorSide.getName() + " at (" + door.getX() + "," + base + "," + door.getZ() + ")";
        builder = new PlaceBlocksAction(call(), entries, Integer.MAX_VALUE);
        builder.start(ctx);
        if (builder.finished()) finishFrom(builder);
    }

    @Override
    protected void onTick(ActionContext ctx) {
        builder.tick();
        if (builder.finished()) finishFrom(builder);
    }

    private void finishFrom(PlaceBlocksAction b) {
        String text = plan + ": " + b.result();
        if (b.outcome() == Outcome.DONE) done(text);
        else failed(text);
    }

    @Override
    protected void onCleanup(ActionContext ctx) {
        if (builder != null && !builder.finished()) builder.preempt("stopped");
    }

    @Override
    public String progress() {
        return builder == null ? "" : "building a shelter: " + builder.progress();
    }
}
