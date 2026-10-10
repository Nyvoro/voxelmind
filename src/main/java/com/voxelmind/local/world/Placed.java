package com.voxelmind.local.world;

import com.voxelmind.local.VoxelMindLocal;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The blocks players placed, as facts: per dimension the block as placed, who placed it (any player, companion
 * bodies included) and when. Recorded from {@code BlockItem.place} (mixin/BlockItemMixin) and from the Builder's
 * edits; carried along when the world changes a placed block's form where it lies (grass on dirt, a stripped log,
 * weathered or waxed copper, hardened concrete, a right-click that turns it into another block), when a piston
 * moves it and when it falls; forgotten when a player breaks the block, and lazily whenever the world no longer
 * holds it (explosions, commands). Only blocks placed since the mod was installed are known; nothing is said about
 * others. One index per running server, kept in {@code <world>/voxelmind-local/placed.dat} and written with every
 * world save. Server thread only; the file itself is written on a background thread.
 */
public final class Placed {
    /**
     * One placed cell: the block now there, the placer's id, the time (epoch ms), and whether it is a pillar step the
     * placer's own route built (a later route of that body may dig it down again).
     */
    public record Entry(Block block, UUID placer, long at, boolean route) {
        public Entry(Block block, UUID placer, long at) {
            this(block, placer, at, false);
        }

        /** The same placement, in the form the world holds now. */
        Entry as(Block now) {
            return now == block ? this : new Entry(now, placer, at, route);
        }
    }

    /** Placed blocks in a cube: how many, and how many per placer name and per block id now in the world. */
    public record Summary(int total, Map<String, Integer> byPlacer, Map<String, Integer> byBlock) {
        static final Summary NONE = new Summary(0, Map.of(), Map.of());
    }

    private static final int MAGIC = 0x564D4C50; // "VMLP"
    /** 2 added the route flag per entry; files of version 1 still load. */
    private static final int VERSION = 2;
    /** Dirt turns into grass, path, farmland or mud and back where it lies; it is still the block a player placed. */
    private static final Set<Block> DIRT_LIKE = Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT, Blocks.DIRT_PATH, Blocks.FARMLAND, Blocks.MUD);
    /** Each block's kind (see {@link #kind}), worked out once per block. */
    private static final Map<Block, Block> KINDS = new ConcurrentHashMap<>();
    /** One writer for placed.dat: writes never overlap, and the server thread only copies the index. */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoxelMind placed.dat writer");
        t.setDaemon(true);
        return t;
    });
    private static @Nullable Placed running;

    private final @Nullable MinecraftServer server;
    private final Path file;
    private final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Entry>> dimensions = new HashMap<>();
    /** Placer id to the name it last placed under. */
    private final Map<UUID, String> names = new HashMap<>();
    /** Entries of placed blocks that are falling now, by the falling block's id. */
    private final Map<UUID, Carried> falling = new HashMap<>();
    private boolean dirty;
    private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);

    private record Carried(ResourceKey<Level> dimension, Entry entry) {}

    /** An index on its own file, not bound to a server (tests). */
    public Placed(Path file) {
        this(null, file);
    }

    private Placed(@Nullable MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
    }

    // ---- lifecycle: bound to one server ----

    public static void start(MinecraftServer server) {
        Placed index = new Placed(server, server.getWorldPath(LevelResource.ROOT).resolve("voxelmind-local").resolve("placed.dat"));
        index.load();
        running = index;
        VoxelMindLocal.LOG.info("Loaded {} placed block(s) from {}", index.size(), index.file);
    }

    /**
     * Writes the index of this server when it changed: with every world save (autosave, /save-all, the pause save, a
     * backup) and every companion save. The server thread only copies the index; the file is written in the background.
     */
    public static void save(MinecraftServer server) {
        Placed index = running;
        if (index != null && index.server == server && index.dirty) index.saveInBackground();
    }

    public static void stop(MinecraftServer server) {
        Placed index = running;
        if (index == null || index.server != server) return;
        try {
            index.pending.join();
        } catch (RuntimeException e) {
            VoxelMindLocal.LOG.error("A background write of {} failed", index.file, e);
        }
        if (index.dirty) index.save();
        running = null;
    }

    /** The index of the server this level belongs to; null on the client and outside a running server. */
    public static @Nullable Placed of(Level level) {
        Placed index = running;
        return index != null && level instanceof ServerLevel server && server.getServer() == index.server ? index : null;
    }

    // ---- recording ----

    /** A player placed the block now at {@code pos} (BlockItemMixin). */
    public static void onPlace(ServerLevel level, BlockPos pos, ServerPlayer player) {
        Placed index = of(level);
        if (index != null) index.record(level, pos, player.getUUID(), player.getGameProfile().name());
    }

    /** A player broke {@code state} at {@code pos}. */
    public static void onBreak(Level level, BlockPos pos, BlockState state) {
        Placed index = of(level);
        if (index != null) index.forget(level, pos, state);
    }

    /**
     * A player's right-click at {@code pos} (ServerPlayerGameModeMixin) found {@code before} there and the world now holds
     * another block: a placed block turned into another form by the click (a stripped log, waxed or scraped copper, a
     * carved pumpkin, a filled cauldron or pot) is still the block that player placed. A door's other half goes with it.
     */
    public static void onUsed(ServerLevel level, BlockPos pos, BlockState before) {
        Placed index = of(level);
        if (index == null || before.isAir()) return;
        BlockState now = level.getBlockState(pos);
        if (now.is(before.getBlock()) || now.isAir()) return;
        index.converted(level.dimension(), pos, before.getBlock(), now);
        BlockPos other = partner(now, pos);
        if (other != null) index.converted(level.dimension(), other, before.getBlock(), level.getBlockState(other));
    }

    private void converted(ResourceKey<Level> dimension, BlockPos pos, Block was, BlockState now) {
        Long2ObjectOpenHashMap<Entry> cells = dimensions.get(dimension);
        Entry e = cells == null ? null : cells.get(pos.asLong());
        // No entry, or one the click wrote anew (a block placed into this cell): nothing to carry over.
        if (e == null || now.isAir() || !sameKind(e.block(), was) || now.is(e.block())) return;
        cells.put(pos.asLong(), e.as(now.getBlock()));
        dirty = true;
    }

    /**
     * A piston moves {@code pushed} one cell along {@code direction} and destroys {@code destroyed}
     * (PistonBaseBlockMixin, before the blocks move): the entries move with their blocks.
     */
    public static void onPistonMove(ServerLevel level, List<BlockPos> pushed, List<BlockPos> destroyed, Direction direction) {
        Placed index = of(level);
        Long2ObjectOpenHashMap<Entry> cells = index == null ? null : index.dimensions.get(level.dimension());
        if (cells == null || cells.isEmpty()) return;
        List<BlockPos> to = new ArrayList<>();
        List<Entry> moving = new ArrayList<>();
        for (BlockPos p : pushed) {
            Entry e = cells.remove(p.asLong());
            if (e == null) continue;
            index.dirty = true;
            BlockState state = level.getBlockState(p);
            if (!sameKind(state.getBlock(), e.block())) continue;
            to.add(p.relative(direction));
            moving.add(e.as(state.getBlock()));
        }
        for (BlockPos p : destroyed) if (cells.remove(p.asLong()) != null) index.dirty = true;
        // Only after every source cell was emptied: a block can move into the cell another one leaves.
        for (int i = 0; i < to.size(); i++) cells.put(to.get(i).asLong(), moving.get(i));
    }

    /** A placed block started to fall (a new falling block entered the world where it stood): its entry falls with it. */
    public static void onFallStart(ServerLevel level, FallingBlockEntity block) {
        Placed index = of(level);
        if (index == null || block.time != 0) return;
        Long2ObjectOpenHashMap<Entry> cells = index.dimensions.get(level.dimension());
        BlockPos from = block.blockPosition();
        Entry e = cells == null ? null : cells.get(from.asLong());
        if (e == null || !sameKind(block.getBlockState().getBlock(), e.block()) || level.getBlockState(from).is(e.block())) return;
        cells.remove(from.asLong());
        index.falling.put(block.getUUID(), new Carried(level.dimension(), e));
        index.dirty = true;
    }

    /** A falling block left the world: where it landed as a block, the placement it carried is recorded there. */
    public static void onFallEnd(ServerLevel level, FallingBlockEntity block) {
        Placed index = of(level);
        if (index == null || index.falling.isEmpty()) return;
        Entity.RemovalReason reason = block.getRemovalReason();
        // Unloaded with its chunk, it falls on when the chunk loads again.
        if (reason == null || !reason.shouldDestroy()) return;
        Carried carried = index.falling.remove(block.getUUID());
        if (carried == null || !carried.dimension().equals(level.dimension())) return;
        BlockPos at = block.blockPosition();
        BlockState now = level.getBlockState(at);
        if (!sameKind(now.getBlock(), carried.entry().block())) return;
        index.dimensions.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>()).put(at.asLong(), carried.entry().as(now.getBlock()));
        index.dirty = true;
    }

    /** Records the block now at {@code pos} as placed by this player, with the other cell of a two-block block. */
    public void record(ServerLevel level, BlockPos pos, UUID placer, String name) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;
        long now = System.currentTimeMillis();
        put(level.dimension(), pos, state.getBlock(), placer, name, now);
        BlockPos other = partner(state, pos);
        if (other != null && level.getBlockState(other).is(state.getBlock())) put(level.dimension(), other, state.getBlock(), placer, name, now);
    }

    public void put(ResourceKey<Level> dimension, BlockPos pos, Block block, UUID placer, String name, long at) {
        names.put(placer, name);
        dimensions.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>()).put(pos.asLong(), new Entry(block, placer, at));
        dirty = true;
    }

    /** Forgets this one cell, e.g. one an edit set to air. */
    public void remove(ResourceKey<Level> dimension, BlockPos pos) {
        Long2ObjectOpenHashMap<Entry> cells = dimensions.get(dimension);
        if (cells != null && cells.remove(pos.asLong()) != null) dirty = true;
    }

    /** Puts an entry read earlier with {@link #entry} back, or forgets the cell when it had none (undoing an edit). */
    public void restore(ResourceKey<Level> dimension, BlockPos pos, @Nullable Entry entry) {
        if (entry == null) {
            remove(dimension, pos);
            return;
        }
        dimensions.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>()).put(pos.asLong(), entry);
        dirty = true;
    }

    /** Forgets {@code pos} and, for a two-block block ({@code state} is what was there), its other cell. */
    public void forget(Level level, BlockPos pos, BlockState state) {
        Long2ObjectOpenHashMap<Entry> cells = dimensions.get(level.dimension());
        if (cells == null) return;
        boolean changed = cells.remove(pos.asLong()) != null;
        BlockPos other = partner(state, pos);
        if (other != null) changed |= cells.remove(other.asLong()) != null;
        if (changed) dirty = true;
    }

    /** The body's own route put the block at {@code pos} down as a pillar step (Navigator): that route step is marked. */
    public static void markRouteStep(Level level, BlockPos pos, UUID body) {
        Placed index = of(level);
        Long2ObjectOpenHashMap<Entry> cells = index == null ? null : index.dimensions.get(level.dimension());
        Entry e = cells == null ? null : cells.get(pos.asLong());
        if (e == null || e.route() || !e.placer().equals(body)) return;
        cells.put(pos.asLong(), new Entry(e.block(), e.placer(), e.at(), true));
        index.dirty = true;
    }

    /** Whether the block at {@code pos} is still a pillar step that this body's own route built. */
    public static boolean isRouteStep(Level level, BlockPos pos, UUID body) {
        Placed index = of(level);
        Long2ObjectOpenHashMap<Entry> cells = index == null ? null : index.dimensions.get(level.dimension());
        Entry e = cells == null ? null : cells.get(pos.asLong());
        return e != null && e.route() && e.placer().equals(body) && index.holding(level, cells, pos, e) != null;
    }

    /** The other cell of a door, tall plant or bed; null for one-cell blocks. */
    public static @Nullable BlockPos partner(BlockState state, BlockPos pos) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)) return pos.relative(BedBlock.getConnectedDirection(state));
        return null;
    }

    /** The upper half of a door or tall plant, or a bed's head: counted once with the cell the player placed. */
    private static boolean secondCell(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                || state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
    }

    // ---- kinds: the forms one placed block takes where it lies ----

    /** Whether {@code a} and {@code b} are forms of the same block (see {@link #kind}). */
    public static boolean sameKind(Block a, Block b) {
        return a == b || kind(a) == kind(b);
    }

    /**
     * One block for all forms the world turns a block into where it lies: dirt for dirt, grass, path, farmland and mud;
     * the first copper form for weathered and waxed copper; the log for its stripped form; concrete for its powder; the
     * pumpkin for a carved one. Any other block is its own kind.
     */
    static Block kind(Block block) {
        return KINDS.computeIfAbsent(block, Placed::findKind);
    }

    private static Block findKind(Block block) {
        if (DIRT_LIKE.contains(block)) return Blocks.DIRT;
        Block b = HoneycombItem.WAX_OFF_BY_BLOCK.get().getOrDefault(block, block);
        b = WeatheringCopper.getFirst(b);
        if (b == Blocks.CARVED_PUMPKIN) return Blocks.PUMPKIN;
        Identifier id = BuiltInRegistries.BLOCK.getKey(b);
        String path = id.getPath();
        String other = path.startsWith("stripped_") ? path.substring("stripped_".length())
                : path.endsWith("_concrete_powder") ? path.substring(0, path.length() - "_powder".length()) : null;
        if (other != null) {
            Optional<Block> found = BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath(id.getNamespace(), other));
            if (found.isPresent()) return found.get();
        }
        return b;
    }

    // ---- queries ----

    /** Who placed the block at {@code pos}, or null when no player is known to have placed what is there now. */
    public static @Nullable String placer(Level level, BlockPos pos) {
        Placed index = of(level);
        if (index == null) return null;
        Long2ObjectOpenHashMap<Entry> cells = index.dimensions.get(level.dimension());
        Entry e = cells == null ? null : cells.get(pos.asLong());
        if (e == null || index.holding(level, cells, pos, e) == null) return null;
        return index.names.getOrDefault(e.placer(), "?");
    }

    public static boolean isPlaced(Level level, BlockPos pos) {
        return placer(level, pos) != null;
    }

    /** The placed blocks in the cube of {@code radius} around {@code center}; a door or bed counts once. */
    public static Summary summary(Level level, BlockPos center, int radius) {
        Placed index = of(level);
        Long2ObjectOpenHashMap<Entry> cells = index == null ? null : index.dimensions.get(level.dimension());
        if (cells == null || cells.isEmpty()) return Summary.NONE;
        int total = 0;
        Map<String, Integer> byPlacer = new HashMap<>();
        Map<String, Integer> byBlock = new HashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    Entry e = cells.get(cursor.asLong());
                    if (e == null) continue;
                    BlockState now = index.holding(level, cells, cursor, e);
                    if (now == null || secondCell(now)) continue;
                    total++;
                    byPlacer.merge(index.names.getOrDefault(e.placer(), "?"), 1, Integer::sum);
                    byBlock.merge(Names.id(now.getBlock()), 1, Integer::sum);
                }
            }
        }
        return total == 0 ? Summary.NONE : new Summary(total, byPlacer, byBlock);
    }

    /** "Gasade 140, Lysa 2" (separator " ") or "spruce_planks ×80, glass ×12" (" ×"): the largest {@code limit} counts. */
    public static String list(Map<String, Integer> counts, String separator, int limit) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.<String, Integer>comparingByKey()));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            if (i > 0) out.append(", ");
            out.append(sorted.get(i).getKey()).append(separator).append(sorted.get(i).getValue());
        }
        return out.toString();
    }

    /**
     * The world's block at {@code pos} while it is still the placed one, in whatever form the world gave it there (the
     * entry follows that form); otherwise the entry is forgotten. A block a piston is moving is held by the moving piston.
     */
    private @Nullable BlockState holding(Level level, Long2ObjectOpenHashMap<Entry> cells, BlockPos pos, Entry e) {
        if (!level.isLoaded(pos)) return null;
        BlockState now = level.getBlockState(pos);
        if (now.is(e.block())) return now;
        if (now.is(Blocks.MOVING_PISTON) && level.getBlockEntity(pos) instanceof PistonMovingBlockEntity moving
                && sameKind(moving.getMovedState().getBlock(), e.block())) {
            return moving.getMovedState();
        }
        if (!now.isAir() && sameKind(now.getBlock(), e.block())) {
            cells.put(pos.asLong(), e.as(now.getBlock()));
            dirty = true;
            return now;
        }
        cells.remove(pos.asLong());
        dirty = true;
        return null;
    }

    /** The raw entry without checking the world (tests, and the Builder's undo snapshots). */
    public @Nullable Entry entry(ResourceKey<Level> dimension, BlockPos pos) {
        Long2ObjectOpenHashMap<Entry> cells = dimensions.get(dimension);
        return cells == null ? null : cells.get(pos.asLong());
    }

    public @Nullable String name(UUID placer) {
        return names.get(placer);
    }

    public int size() {
        int n = 0;
        for (Long2ObjectOpenHashMap<Entry> cells : dimensions.values()) n += cells.size();
        return n;
    }

    // ---- persistence: GZIP'd DataOutputStream, block ids and placers as palettes ----

    /** Reads the file; a file that cannot be read is kept aside and the index starts empty. */
    public void load() {
        dimensions.clear();
        names.clear();
        dirty = false;
        if (!Files.exists(file)) return;
        // The raw stream is a resource of its own: a file that is not GZIP fails in the GZIPInputStream constructor.
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            if (in.readInt() != MAGIC) throw new IOException("not a placed-blocks file");
            int version = in.readInt();
            if (version != 1 && version != VERSION) throw new IOException("unknown version " + version);
            // Palettes grow while they are read: a damaged count must not allocate before the data runs out.
            List<Block> blocks = new ArrayList<>();
            for (int i = 0, n = count(in); i < n; i++) {
                Identifier id = Identifier.tryParse(in.readUTF());
                // A block id this game does not know (a removed mod) can never match the world again.
                blocks.add(id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null));
            }
            List<UUID> placers = new ArrayList<>();
            for (int i = 0, n = count(in); i < n; i++) {
                UUID placer = new UUID(in.readLong(), in.readLong());
                placers.add(placer);
                names.put(placer, in.readUTF());
            }
            int dimensionCount = count(in);
            for (int d = 0; d < dimensionCount; d++) {
                Identifier dimensionId = Identifier.tryParse(in.readUTF());
                if (dimensionId == null) throw new IOException("bad dimension id");
                Long2ObjectOpenHashMap<Entry> cells = new Long2ObjectOpenHashMap<>();
                int n = count(in);
                for (int i = 0; i < n; i++) {
                    int x = in.readInt();
                    int y = in.readInt();
                    int z = in.readInt();
                    Block block = blocks.get(in.readInt());
                    UUID placer = placers.get(in.readInt());
                    long at = in.readLong();
                    boolean route = version >= 2 && in.readBoolean();
                    if (block != null) cells.put(BlockPos.asLong(x, y, z), new Entry(block, placer, at, route));
                }
                if (!cells.isEmpty()) dimensions.put(ResourceKey.create(Registries.DIMENSION, dimensionId), cells);
            }
        } catch (Exception e) {
            dimensions.clear();
            names.clear();
            Path aside = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
            VoxelMindLocal.LOG.error("Could not read {}; it is kept as {} and placed blocks are recorded anew", file, aside.getFileName(), e);
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                VoxelMindLocal.LOG.error("Could not keep {} aside", file, moveError);
            }
        }
    }

    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 100_000_000) throw new IOException("bad count " + n);
        return n;
    }

    /** What the file is written from: a copy of the index (entries are immutable, so copying the maps is enough). */
    private record Snapshot(Map<UUID, String> names, Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Entry>> dimensions) {}

    private Snapshot snapshot() {
        Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Entry>> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceKey<Level>, Long2ObjectOpenHashMap<Entry>> d : dimensions.entrySet()) copy.put(d.getKey(), d.getValue().clone());
        return new Snapshot(new HashMap<>(names), copy);
    }

    /** Copies the index on this thread and writes the copy on the writer thread; a failed write marks it changed again. */
    private void saveInBackground() {
        Snapshot snapshot = snapshot();
        dirty = false;
        MinecraftServer owner = server;
        pending = pending.thenRunAsync(() -> {
            if (!write(file, snapshot) && owner != null) owner.execute(() -> dirty = true);
        }, WRITER);
    }

    /** Writes the whole index now, on this thread. */
    public void save() {
        if (write(file, snapshot())) dirty = false;
    }

    /** Writes to a temp file and moves it over the old one: a crash mid-write keeps the previous file. */
    private static boolean write(Path file, Snapshot snapshot) {
        Map<Block, Integer> blockIds = new LinkedHashMap<>();
        Map<UUID, Integer> placerIds = new LinkedHashMap<>();
        for (Long2ObjectOpenHashMap<Entry> cells : snapshot.dimensions().values()) {
            for (Entry e : cells.values()) {
                blockIds.putIfAbsent(e.block(), blockIds.size());
                placerIds.putIfAbsent(e.placer(), placerIds.size());
            }
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FastGzip(raw), 1 << 16))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(blockIds.size());
                for (Block block : blockIds.keySet()) out.writeUTF(BuiltInRegistries.BLOCK.getKey(block).toString());
                out.writeInt(placerIds.size());
                for (UUID placer : placerIds.keySet()) {
                    out.writeLong(placer.getMostSignificantBits());
                    out.writeLong(placer.getLeastSignificantBits());
                    out.writeUTF(snapshot.names().getOrDefault(placer, "?"));
                }
                out.writeInt(snapshot.dimensions().size());
                for (Map.Entry<ResourceKey<Level>, Long2ObjectOpenHashMap<Entry>> dimension : snapshot.dimensions().entrySet()) {
                    out.writeUTF(dimension.getKey().identifier().toString());
                    out.writeInt(dimension.getValue().size());
                    for (Long2ObjectMap.Entry<Entry> cell : dimension.getValue().long2ObjectEntrySet()) {
                        long pos = cell.getLongKey();
                        out.writeInt(BlockPos.getX(pos));
                        out.writeInt(BlockPos.getY(pos));
                        out.writeInt(BlockPos.getZ(pos));
                        out.writeInt(blockIds.get(cell.getValue().block()));
                        out.writeInt(placerIds.get(cell.getValue().placer()));
                        out.writeLong(cell.getValue().at());
                        out.writeBoolean(cell.getValue().route());
                    }
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            VoxelMindLocal.LOG.error("Could not write {}", file, e);
            return false;
        }
    }

    /** GZIP at the fastest level: the index compresses well anyway, and a large one is written in a fraction of the time. */
    private static final class FastGzip extends GZIPOutputStream {
        FastGzip(OutputStream out) throws IOException {
            super(out, 1 << 16);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }
}
