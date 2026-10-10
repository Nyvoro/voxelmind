package com.voxelmind.local.world;

import com.voxelmind.local.VoxelMindLocal;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The blocks players placed, as facts: per dimension the block as placed, who placed it (any player, companion
 * bodies included) and when. Recorded from {@code BlockItem.place} (mixin/BlockItemMixin), forgotten when a player
 * breaks the block, and lazily whenever the world no longer holds it (explosions, pistons, commands). Only blocks
 * placed since the mod was installed are known; nothing is said about others. One index per running server,
 * kept in {@code <world>/voxelmind-local/placed.dat}. Server thread only.
 */
public final class Placed {
    /** One placed cell: the block as placed, the placer's id and the time (epoch ms). */
    public record Entry(Block block, UUID placer, long at) {}

    /** Placed blocks in a cube: how many, and how many per placer name and per block id now in the world. */
    public record Summary(int total, Map<String, Integer> byPlacer, Map<String, Integer> byBlock) {
        static final Summary NONE = new Summary(0, Map.of(), Map.of());
    }

    private static final int MAGIC = 0x564D4C50; // "VMLP"
    private static final int VERSION = 1;
    /** Dirt turns into grass, path or farmland and back where it lies; it is still the block a player placed. */
    private static final Set<Block> DIRT_LIKE = Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT, Blocks.DIRT_PATH, Blocks.FARMLAND);
    private static @Nullable Placed running;

    private final @Nullable MinecraftServer server;
    private final Path file;
    private final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Entry>> dimensions = new HashMap<>();
    /** Placer id to the name it last placed under. */
    private final Map<UUID, String> names = new HashMap<>();
    private boolean dirty;

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

    /** Writes the index of this server when it changed; called whenever companion state is saved. */
    public static void save(MinecraftServer server) {
        Placed index = running;
        if (index != null && index.server == server && index.dirty) index.save();
    }

    public static void stop(MinecraftServer server) {
        Placed index = running;
        if (index == null || index.server != server) return;
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

    /** Forgets {@code pos} and, for a two-block block ({@code state} is what was there), its other cell. */
    public void forget(Level level, BlockPos pos, BlockState state) {
        Long2ObjectOpenHashMap<Entry> cells = dimensions.get(level.dimension());
        if (cells == null) return;
        boolean changed = cells.remove(pos.asLong()) != null;
        BlockPos other = partner(state, pos);
        if (other != null) changed |= cells.remove(other.asLong()) != null;
        if (changed) dirty = true;
    }

    /** The other cell of a door, tall plant or bed; null for one-cell blocks. */
    private static @Nullable BlockPos partner(BlockState state, BlockPos pos) {
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

    /** The world's block at {@code pos} while it is still the placed one; otherwise the entry is forgotten. */
    private @Nullable BlockState holding(Level level, Long2ObjectOpenHashMap<Entry> cells, BlockPos pos, Entry e) {
        if (!level.isLoaded(pos)) return null;
        BlockState now = level.getBlockState(pos);
        if (now.is(e.block()) || DIRT_LIKE.contains(e.block()) && DIRT_LIKE.contains(now.getBlock())) return now;
        cells.remove(pos.asLong());
        dirty = true;
        return null;
    }

    /** The raw entry without checking the world (tests). */
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
            if (version != VERSION) throw new IOException("unknown version " + version);
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
                    if (block != null) cells.put(BlockPos.asLong(x, y, z), new Entry(block, placer, at));
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

    /** Writes the whole index to a temp file and moves it over the old one: a crash mid-write keeps the previous file. */
    public void save() {
        Map<Block, Integer> blockIds = new LinkedHashMap<>();
        Map<UUID, Integer> placerIds = new LinkedHashMap<>();
        for (Long2ObjectOpenHashMap<Entry> cells : dimensions.values()) {
            for (Entry e : cells.values()) {
                blockIds.putIfAbsent(e.block(), blockIds.size());
                placerIds.putIfAbsent(e.placer(), placerIds.size());
            }
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream raw = Files.newOutputStream(tmp);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(blockIds.size());
                for (Block block : blockIds.keySet()) out.writeUTF(BuiltInRegistries.BLOCK.getKey(block).toString());
                out.writeInt(placerIds.size());
                for (UUID placer : placerIds.keySet()) {
                    out.writeLong(placer.getMostSignificantBits());
                    out.writeLong(placer.getLeastSignificantBits());
                    out.writeUTF(names.getOrDefault(placer, "?"));
                }
                out.writeInt(dimensions.size());
                for (Map.Entry<ResourceKey<Level>, Long2ObjectOpenHashMap<Entry>> dimension : dimensions.entrySet()) {
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
                    }
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            VoxelMindLocal.LOG.error("Could not write {}", file, e);
        }
    }
}
