package com.voxelmind.local.companion;

import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.action.ActionContext;
import com.voxelmind.local.action.BodyAction;
import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.config.LocalConfig;
import com.voxelmind.local.hosted.BuilderMind;
import com.voxelmind.local.hosted.GodMind;
import com.voxelmind.local.hosted.HostedMind;
import com.voxelmind.local.mind.Mind;
import com.voxelmind.local.mind.Timeline;
import com.voxelmind.local.world.Names;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** One companion at runtime: its body, the action it is carrying out, and its mind. */
public final class Companion implements CompanionPlayer.BodyListener {
    public static final int SAY_RANGE = 48;

    public final CompanionState state;
    private final CompanionManager manager;
    private @Nullable CompanionPlayer body;
    private final ActionContext ctx;
    private @Nullable BodyAction current;
    private long currentStartedAt;
    private @Nullable LastAction last;
    public final Mind mind;
    /** The God or Builder session of a hosted kind; null for a companion with a player body. */
    public final @Nullable HostedMind hosted;
    private int deadTicks;

    public record LastAction(String tool, String call, String outcome, String result, long at) {}

    Companion(CompanionManager manager, CompanionState state) {
        this.manager = manager;
        this.state = state;
        this.ctx = new ActionContext(() -> this.body, state.owner);
        this.mind = new Mind(this);
        this.hosted = switch (state.kind == null ? "companion" : state.kind) {
            case "god" -> new GodMind(this);
            case "builder" -> new BuilderMind(this);
            default -> null;
        };
    }

    public CompanionManager manager() {
        return manager;
    }

    public @Nullable CompanionPlayer body() {
        return body;
    }

    public boolean present() {
        if (hosted != null) return hosted.active();
        return body != null && !body.isRemoved() || body != null && body.isDeadOrDying();
    }

    public String name() {
        return state.name;
    }

    public @Nullable ServerPlayer owner() {
        return manager.server().getPlayerList().getPlayer(state.owner);
    }

    void attach(CompanionPlayer body) {
        this.body = body;
        body.setListener(this);
        deadTicks = 0;
    }

    void detach() {
        if (current != null && !current.finished()) {
            current.preempt("interrupted: you left the world");
            record(false);
        }
        if (body != null) body.setListener(null);
        body = null;
    }

    // ---- ticking ----

    void tick(MinecraftServer server) {
        if (hosted != null) {
            hosted.tick(server);
            return;
        }
        if (body == null) return;
        if (body.isDeadOrDying()) {
            if (++deadTicks == 30) {
                if (state.fallen()) manager.fall(this);
                else respawn();
            }
            return;
        }
        if (current != null) {
            current.tick();
            if (current.finished()) record(true);
        }
        mind.tick(server);
    }

    private void respawn() {
        if (body == null) return;
        try {
            body.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        } catch (Exception e) {
            VoxelMindLocal.LOG.error("Respawn of {} failed", state.name, e);
        }
    }

    /** Called with the new body object Minecraft creates on respawn. */
    void onRespawned(CompanionPlayer newBody) {
        attach(newBody);
        Compat.acceptPlayerLoad(newBody);
        event("respawn", "you respawned at " + Names.pos(newBody.blockPosition()) + " in "
                + newBody.level().dimension().identifier().getPath() + " with HP " + Math.round(newBody.getHealth()), false, null);
        mind.wake("respawned", true, true, 500);
    }

    // ---- body actions ----

    public void startAction(BodyAction action) {
        if (body == null) return;
        if (current != null && !current.finished()) {
            current.preempt("replaced by your newer choice " + action.call());
            record(false);
        }
        current = action;
        currentStartedAt = System.currentTimeMillis();
        action.start(ctx);
        if (action.finished()) record(true);
    }

    public void stopAction(String call) {
        if (current != null && !current.finished()) {
            current.preempt("stopped by " + call);
            record(false);
            event("result", call + ": stopped; standing at " + Names.pos(body.blockPosition()), false, "done");
        } else {
            if (body != null) body.motor.clear();
            event("result", call + ": nothing was running; standing still at " + (body == null ? "?" : Names.pos(body.blockPosition())), false, "done");
        }
    }

    private void record(boolean wake) {
        BodyAction a = current;
        if (a == null || a.outcome() == null) return;
        current = null;
        last = new LastAction(a.tool(), a.call(), a.outcome().id, a.result(), System.currentTimeMillis());
        event("result", a.call() + ": " + a.result(), false, a.outcome().id);
        boolean longRunning = a.ticksRunning() >= 20 * 10;
        if (wake) mind.wake("result of " + a.tool() + " (" + a.outcome().id + ")", true, longRunning, 400);
    }

    /** True when exactly this call is the body action running now. */
    public boolean isRunning(String call) {
        return current != null && !current.finished() && current.call().equals(call);
    }

    public boolean actionRunning() {
        return current != null && !current.finished();
    }

    /** The [BODY ACTION] line. */
    public String bodyActionLine() {
        long now = System.currentTimeMillis();
        if (current != null && !current.finished()) {
            String progress = current.progress();
            return current.call() + " running for " + ((now - currentStartedAt) / 1000) + "s"
                    + (progress.isEmpty() ? "" : " — " + progress) + "; completion not yet reported";
        }
        if (last != null) return "none running; last finished: " + last.tool() + " (" + last.outcome() + "), " + Timeline.age(now - last.at());
        return "none running";
    }

    public @Nullable LastAction lastAction() {
        return last;
    }

    /** The running body action as the menu shows it; null when idle. */
    public record Activity(String call, long seconds, String progress) {}

    public @Nullable Activity activity() {
        if (current == null || current.finished()) return null;
        return new Activity(current.call(), (System.currentTimeMillis() - currentStartedAt) / 1000, current.progress());
    }

    // ---- events ----

    public Timeline.Event event(String kind, String text, boolean fromOwner, @Nullable String outcome) {
        Timeline.Event e = state.timeline.add(kind, text, fromOwner, outcome);
        manager.markDirty();
        return e;
    }

    /** A player or companion said something this companion could hear. */
    public void hear(String speaker, boolean fromOwner, String text, String channel) {
        String who = speaker + (fromOwner ? " (your owner" + (channel.equals("public") ? "" : ", " + channel) + ")" : channel.equals("public") ? "" : " (" + channel + ")");
        event(channel.equals("whisper") ? "whisper_in" : "chat_in", who + ": \"" + text + "\"", fromOwner, null);
        boolean mentioned = text.toLowerCase().contains(state.name.toLowerCase());
        if (fromOwner || channel.equals("whisper") || mentioned) {
            mind.wake("chat from " + speaker, true, true, 300);
        }
    }

    /** Sends a chat line from this companion; returns who could hear it. */
    public String say(String text, String mode, String target) {
        if (body == null) return "nobody (you are not in the world)";
        String clean = text.replace('\n', ' ').trim();
        if (clean.length() > 256) clean = clean.substring(0, 256);
        if (clean.isEmpty()) return "nobody (empty message)";
        MinecraftServer server = body.level().getServer();
        List<ServerPlayer> audience = new ArrayList<>();
        Component line;
        switch (mode == null ? "say" : mode) {
            case "whisper" -> {
                ServerPlayer to = target == null ? null : server.getPlayerList().getPlayerByName(target);
                if (to == null) {
                    event("chat_out", clean + "\" (whisper to '" + target + "' failed: no such player online)", false, null);
                    return "nobody";
                }
                audience.add(to);
                line = Component.translatable("commands.message.display.incoming", body.getDisplayName(), Component.literal(clean))
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            }
            case "yell" -> {
                audience.addAll(server.getPlayerList().getPlayers());
                line = Component.translatable("chat.type.text", body.getDisplayName(), Component.literal(clean));
            }
            default -> {
                for (ServerPlayer p : Compat.level(body).players()) {
                    if (p.distanceTo(body) <= SAY_RANGE) audience.add(p);
                }
                line = Component.translatable("chat.type.text", body.getDisplayName(), Component.literal(clean));
            }
        }
        List<String> heard = new ArrayList<>();
        for (ServerPlayer p : audience) {
            if (p == body) continue;
            if (p instanceof CompanionPlayer peer) {
                Companion other = manager.byBody(peer);
                if (other != null) other.hear(state.name, false, clean, mode.equals("whisper") ? "whisper" : "public");
            } else {
                p.sendSystemMessage(line);
            }
            heard.add(p.getGameProfile().name());
        }
        String audienceText = heard.isEmpty() ? "nobody within " + SAY_RANGE + " blocks heard it" : "heard by " + String.join(", ", heard);
        event("chat_out", clean + "\" (" + mode + ", " + audienceText + ")", false, null);
        return audienceText;
    }

    // ---- body listener ----

    @Override
    public void onHurt(DamageSource source, float healthLost) {
        if (body == null) return;
        Entity attacker = source.getEntity();
        String from = attacker != null ? Names.entity(attacker) + " [#" + attacker.getId() + "]" : source.getMsgId();
        event("damage", "took " + String.format("%.1f", healthLost) + " damage from " + from + "; HP now " + Math.round(body.getHealth()) + "/20", false, null);
        boolean fighting = current != null && !current.finished() && current.tool().equals("attack");
        if (!fighting) mind.wake("hurt by " + from, true, false, 150);
    }

    @Override
    public void onDeath(DamageSource source, Component deathMessage) {
        if (current != null && !current.finished()) {
            current.preempt("interrupted: you died");
            record(false);
        }
        event("death", "you died: " + deathMessage.getString() + "; your items dropped where you died unless the world keeps inventory"
                + (state.hardcore() ? "; you were in hardcore mode, so this was your one life" : ""), false, null);
        if (state.hardcore()) {
            state.fallenAt = System.currentTimeMillis();
            state.fallenBy = deathMessage.getString();
        }
        manager.markDirty();
        deadTicks = 0;
    }

    @Override
    public void onDirectMessage(Component sender, Component content, ChatType.Bound chatType) {
        // Public player chat reaches every player body too; it is heard through the range check in CompanionManager.onChat.
        if (chatType.chatType().is(ChatType.CHAT) || chatType.chatType().is(ChatType.MSG_COMMAND_OUTGOING)
                || chatType.chatType().is(ChatType.TEAM_MSG_COMMAND_OUTGOING)) {
            return;
        }
        String speaker = sender.getString();
        ServerPlayer from = body == null ? null : body.level().getServer().getPlayerList().getPlayerByName(speaker);
        boolean owner = from != null && from.getUUID().equals(state.owner);
        String channel = chatType.chatType().is(ChatType.MSG_COMMAND_INCOMING) ? "whisper"
                : chatType.chatType().is(ChatType.TEAM_MSG_COMMAND_INCOMING) ? "team message"
                : chatType.chatType().is(ChatType.EMOTE_COMMAND) ? "emote" : "announcement";
        hear(speaker, owner, content.getString(), channel);
    }

    /** One system notice to the owner, e.g. a missing key. Not part of the companion's own speech. */
    public void notifyOwner(String text) {
        notifyOwner(text, false);
    }

    /** Technical notice for the owner, optionally with a link to the AI settings. */
    public void notifyOwner(Component message) {
        ServerPlayer owner = owner();
        if (owner != null) owner.sendSystemMessage(message);
        VoxelMindLocal.LOG.info("[{}] notice: {}", state.name, message.getString());
    }

    public void notifyOwner(String text, boolean settingsLink) {
        ServerPlayer owner = owner();
        if (owner != null) {
            var message = Component.literal("[VoxelMind Local] " + text).withStyle(ChatFormatting.GOLD);
            if (settingsLink) message.append(" ").append(com.voxelmind.local.command.VmlCommand.settingsLink());
            owner.sendSystemMessage(message);
        }
        VoxelMindLocal.LOG.info("[{}] notice: {}", state.name, text);
    }

    public String ownerName() {
        return state.ownerName;
    }

    public LocalConfig config() {
        return LocalConfig.get();
    }
}
