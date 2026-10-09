package com.voxelmind.local.hosted;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;

/**
 * Snapshots of the regions the Builder edits, so "undo" can put every block and block entity back.
 * Bounded like the hosted mod: 10 entries, one million cells in total, 300 000 cells per edit.
 */
public final class EditHistory {
    public static final int MAX_EDIT_CELLS = 300_000;
    private static final int MAX_ENTRIES = 10;
    private static final long MAX_TOTAL_CELLS = 1_000_000;

    public static final class Entry {
        public final String id;
        public final String label;
        public final ServerLevel level;
        public final BlockPos min;
        public final BlockPos max;
        public final long at = System.currentTimeMillis();
        final BlockState[] states;
        final @Nullable CompoundTag[] entities;
        public int changed = -1;
        public boolean errors;

        Entry(String id, String label, ServerLevel level, BlockPos min, BlockPos max, BlockState[] states, @Nullable CompoundTag[] entities) {
            this.id = id;
            this.label = label;
            this.level = level;
            this.min = min;
            this.max = max;
            this.states = states;
            this.entities = entities;
        }

        public long cells() {
            return states.length;
        }

        int sx() {
            return max.getX() - min.getX() + 1;
        }

        int sz() {
            return max.getZ() - min.getZ() + 1;
        }

        BlockPos pos(int i) {
            int sx = sx();
            int sz = sz();
            int x = i % sx;
            int z = (i / sx) % sz;
            int y = i / (sx * sz);
            return new BlockPos(min.getX() + x, min.getY() + y, min.getZ() + z);
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private int counter;

    public static long cells(BlockPos a, BlockPos b) {
        return (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
    }

    /** Records the region before an edit; null when it is too large to undo. */
    public @Nullable Entry snapshot(String label, ServerLevel level, BlockPos a, BlockPos b) {
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        long cells = cells(min, max);
        if (cells > MAX_EDIT_CELLS) return null;
        BlockState[] states = new BlockState[(int) cells];
        CompoundTag[] entities = new CompoundTag[(int) cells];
        Entry probe = new Entry("e" + (++counter), label, level, min, max, states, entities);
        for (int i = 0; i < cells; i++) {
            BlockPos pos = probe.pos(i);
            states[i] = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) entities[i] = be.saveWithFullMetadata(level.registryAccess());
        }
        entries.addLast(probe);
        long total = 0;
        for (Entry e : entries) total += e.cells();
        while (entries.size() > MAX_ENTRIES || total > MAX_TOTAL_CELLS && entries.size() > 1) {
            total -= entries.removeFirst().cells();
        }
        return probe;
    }

    /** Cells whose block or block entity differs from the snapshot now. */
    public static int countChanged(Entry e) {
        int changed = 0;
        for (int i = 0; i < e.states.length; i++) {
            BlockPos pos = e.pos(i);
            if (!e.level.getBlockState(pos).equals(e.states[i])) {
                changed++;
                continue;
            }
            BlockEntity be = e.level.getBlockEntity(pos);
            CompoundTag now = be == null ? null : be.saveWithFullMetadata(e.level.registryAccess());
            if (!Objects.equals(now, e.entities[i])) changed++;
        }
        return changed;
    }

    public @Nullable Entry find(String target) {
        if (target == null || target.isBlank() || target.equalsIgnoreCase("last")) return entries.peekLast();
        for (Entry e : entries) if (e.id.equalsIgnoreCase(target.trim())) return e;
        return null;
    }

    /** Puts the snapshot back and drops the entry; returns how many cells were restored. */
    public int undo(Entry e) {
        int restored = 0;
        for (int i = 0; i < e.states.length; i++) {
            BlockPos pos = e.pos(i);
            BlockState was = e.states[i];
            BlockState now = e.level.getBlockState(pos);
            if (!now.equals(was)) {
                e.level.removeBlockEntity(pos);
                e.level.setBlock(pos, was, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                restored++;
            }
            CompoundTag tag = e.entities[i];
            if (tag != null) {
                BlockEntity be = BlockEntity.loadStatic(pos, was, tag, e.level.registryAccess());
                if (be != null) e.level.setBlockEntity(be);
            }
        }
        entries.remove(e);
        return restored;
    }

    public void drop(Entry e) {
        entries.remove(e);
    }

    public Iterable<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
    }

    /** Removes snapshots of a level that is gone. */
    public void prune(ServerLevel level) {
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) if (it.next().level == level) it.remove();
    }
}
