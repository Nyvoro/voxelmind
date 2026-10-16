package com.voxelmind.local.hosted;

import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.compat.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runs one command with console authority at a position and records what the server answered: the
 * feedback lines verbatim, failures, and vanilla no-ops ("No blocks were filled"), which are not errors.
 */
public final class CommandRunner {
    private static final int MAX_FEEDBACK = 16_000;
    private static final Set<String> NO_OP_KEYS = Set.of("commands.fill.failed", "commands.setblock.failed", "commands.clone.failed");

    public record Result(boolean ok, String text, boolean noOp, int value) {}

    private CommandRunner() {}

    /** Replaces {owner}/{player} and {x}/{y}/{z} with the host and their block position. */
    public static String placeholders(String command, ServerPlayer host) {
        return command.replace("{owner}", host.getGameProfile().name())
                .replace("{player}", host.getGameProfile().name())
                .replace("{x}", Integer.toString(host.blockPosition().getX()))
                .replace("{y}", Integer.toString(host.blockPosition().getY()))
                .replace("{z}", Integer.toString(host.blockPosition().getZ()));
    }

    public static Result runAt(MinecraftServer server, ServerLevel level, Vec3 pos, Vec2 rotation, String sourceName, String command) {
        List<Component> lines = new ArrayList<>();
        int[] callbacks = new int[3]; // ok, failed, sum
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                lines.add(message);
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        // Admin level so /tick works; the allowlist in CommandPolicy is the boundary.
        CommandSourceStack source = new CommandSourceStack(capture, pos, rotation, level, LevelBasedPermissionSet.ADMIN,
                sourceName, Component.literal(sourceName), server, null)
                .withCallback((success, value) -> {
                    if (success) callbacks[0]++;
                    else callbacks[1]++;
                    callbacks[2] += value;
                });
        boolean thrown = false;
        try {
            server.getCommands().performPrefixedCommand(source, CommandPolicy.normalize(command));
        } catch (Exception e) {
            thrown = true;
            lines.add(Component.literal("exception: " + e.getMessage()).withStyle(ChatFormatting.RED));
            VoxelMindLocal.LOG.warn("Command failed: {}", command, e);
        }
        boolean red = false;
        boolean noOp = false;
        StringBuilder text = new StringBuilder();
        for (Component line : lines) {
            TextColor color = line.getStyle().getColor();
            if (color != null && color.equals(TextColor.fromLegacyFormat(ChatFormatting.RED))) red = true;
            if (noOpKey(line)) noOp = true;
            if (!text.isEmpty()) text.append('\n');
            text.append(line.getString());
        }
        String out = text.toString();
        if (out.length() > MAX_FEEDBACK) {
            out = out.substring(0, MAX_FEEDBACK) + "\n[Feedback truncated; query a specific NBT path for omitted data.]";
        }
        if (out.isEmpty()) {
            out = callbacks[0] + callbacks[1] == 0
                    ? "No command execution reported: 0 execution callbacks; no server feedback. An execute selector may have matched no entities."
                    : "No server feedback. Execution callbacks: " + callbacks[0] + " successful, " + callbacks[1] + " failed; sum of returned values: " + callbacks[2] + ".";
        }
        boolean ok = !thrown && (!red || noOp) && (callbacks[1] == 0 || callbacks[0] > 0 || noOp);
        return new Result(ok, out, noOp, callbacks[2]);
    }

    /**
     * Whether the message is a vanilla no-op ("No blocks were filled": the blocks already were so). A failure
     * arrives as an empty red line with the translatable message as its sibling, so the whole tree is searched.
     */
    static boolean noOpKey(Component c) {
        if (c.getContents() instanceof TranslatableContents t) {
            if (NO_OP_KEYS.contains(t.getKey())) return true;
            for (Object arg : t.getArgs()) if (arg instanceof Component a && noOpKey(a)) return true;
        }
        for (Component sibling : c.getSiblings()) if (noOpKey(sibling)) return true;
        return false;
    }

    /** Runs at the host player's live position, rotation and dimension. */
    public static Result runAtHost(ServerPlayer host, String sourceName, String command) {
        return runAt(Compat.level(host).getServer(), Compat.level(host), host.position(), new Vec2(host.getXRot(), host.getYRot()),
                sourceName, placeholders(command, host));
    }
}
