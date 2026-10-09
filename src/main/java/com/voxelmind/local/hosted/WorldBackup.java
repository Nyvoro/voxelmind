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
 * is saved and zipped to backups/&lt;world&gt;-voxelmind-&lt;date&gt;.zip, like the hosted mod did.
 */
public final class WorldBackup {
    private static final java.util.Set<Path> RUNNING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private WorldBackup() {}

    /** Returns the backup path when one is started, or null when today's backup already exists. */
    public static Path ensureToday(MinecraftServer server) {
        String level = server.getWorldData().getLevelName().replaceAll("[^A-Za-z0-9 _.-]", "_");
        Path dir = FabricLoader.getInstance().getGameDir().resolve("backups");
        Path target = dir.resolve(level + "-voxelmind-" + LocalDate.now() + ".zip");
        if (Files.exists(target) || !RUNNING.add(target)) return null;
        server.saveEverything(true, true, true);
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Thread thread = new Thread(() -> {
            try {
                zip(world, target);
            } finally {
                RUNNING.remove(target);
            }
        }, "VoxelMind Local backup");
        thread.setDaemon(true);
        thread.start();
        return target;
    }

    private static void zip(Path world, Path target) {
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
        } catch (IOException e) {
            VoxelMindLocal.LOG.error("World backup failed", e);
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
            }
        }
    }
}
