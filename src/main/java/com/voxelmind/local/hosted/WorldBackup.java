package com.voxelmind.local.hosted;

import com.voxelmind.local.VoxelMindLocal;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Before a God or Builder with console power touches a world for the first time on a day, the world
 * is saved and zipped to backups/&lt;save folder&gt;-voxelmind-&lt;date&gt;.zip, like the hosted mod did.
 * The newest {@link #KEEP} backups of a world are kept, so a world used every day does not fill the disk.
 */
public final class WorldBackup {
    private static final java.util.Set<Path> RUNNING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int KEEP = 3;

    private WorldBackup() {}

    /** Returns the backup path when one is started, or null when today's backup already exists. */
    public static Path ensureToday(MinecraftServer server) {
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String prefix = world.getFileName().toString().replaceAll("[^A-Za-z0-9 _.()-]", "_") + "-voxelmind-";
        Path dir = FabricLoader.getInstance().getGameDir().resolve("backups");
        Path target = dir.resolve(prefix + LocalDate.now() + ".zip");
        if (Files.exists(target) || !RUNNING.add(target)) return null;
        server.saveEverything(true, true, true);
        Thread thread = new Thread(() -> {
            try {
                if (zip(world, target)) prune(dir, prefix);
            } finally {
                RUNNING.remove(target);
            }
        }, "VoxelMind Local backup");
        thread.setDaemon(true);
        thread.start();
        return target;
    }

    /** Deletes all but the newest {@link #KEEP} backups of one world (the date in the name sorts them). */
    private static void prune(Path dir, String prefix) {
        try (Stream<Path> files = Files.list(dir)) {
            java.util.List<Path> backups = files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && n.endsWith(".zip");
            }).sorted().toList();
            for (int i = 0; i < backups.size() - KEEP; i++) {
                Files.deleteIfExists(backups.get(i));
                VoxelMindLocal.LOG.info("Old world backup removed: {}", backups.get(i).getFileName());
            }
        } catch (IOException e) {
            VoxelMindLocal.LOG.warn("Could not tidy {}", dir, e);
        }
    }

    private static boolean zip(Path world, Path target) {
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(partial); ZipOutputStream zip = new ZipOutputStream(out);
                 Stream<Path> files = Files.walk(world)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (!Files.isRegularFile(file) || file.getFileName().toString().equals("session.lock")) continue;
                    zip.putNextEntry(new ZipEntry(world.getFileName() + "/" + world.relativize(file).toString().replace('\\', '/')));
                    try {
                        Files.copy(file, zip);
                    } catch (IOException e) {
                        VoxelMindLocal.LOG.warn("Backup skipped {}: {}", file, e.getMessage());
                    }
                    zip.closeEntry();
                }
            }
            Files.move(partial, target);
            VoxelMindLocal.LOG.info("World backup written to {}", target);
            return true;
        } catch (IOException e) {
            VoxelMindLocal.LOG.error("World backup failed", e);
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
            }
            return false;
        }
    }
}
