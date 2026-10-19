package com.voxelmind.local.body;

import com.mojang.authlib.GameProfile;
import com.voxelmind.local.compat.Compat;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The companion's body: a real server-side player without a client.
 *
 * <p>Real players are simulated by their client and the server only replays the
 * reported position. A companion has no client, so this body runs the player's
 * own physics every tick from the inputs in {@link #motor}.
 */
public class CompanionPlayer extends ServerPlayer {
    public final Motor motor = new Motor();
    private @Nullable BodyListener listener;
    /** Motion the server sent this player's client (a hit's knockback), applied on the next tick as a client does. */
    private @Nullable Vec3 receivedMotion;

    public CompanionPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, com.voxelmind.local.compat.Compat.companionClientInformation());
    }

    public void setListener(@Nullable BodyListener listener) {
        this.listener = listener;
    }

    public @Nullable BodyListener listener() {
        return listener;
    }

    /** The server sent this body's client a new motion (a player's hit knocks a player back that way). */
    public void receiveMotion(Vec3 motion) {
        receivedMotion = motion;
    }

    @Override
    public void tick() {
        super.tick();
        if (isRemoved()) return;
        // A real client confirms a dimension change (portal, end gateway, teleport) with its next packet; the
        // body has none, so it confirms itself. Otherwise it stays invulnerable and can never use a portal again.
        if (isChangingDimension()) hasChangedDimension();
        if (receivedMotion != null) {
            setDeltaMovement(receivedMotion);
            receivedMotion = null;
        }
        applyMotor();
        doTick();
        Compat.level(this).getChunkSource().move(this);
    }

    private void applyMotor() {
        if (isDeadOrDying()) {
            xxa = 0;
            zza = 0;
            setJumping(false);
            return;
        }
        if (motor.targetYaw != null) {
            float yaw = rotateTowards(getYRot(), motor.targetYaw, 45f);
            setYRot(yaw);
            setYHeadRot(yaw);
        }
        if (motor.targetPitch != null) {
            setXRot(rotateTowards(getXRot(), motor.targetPitch, 30f));
        }
        xxa = motor.strafe;
        zza = motor.forward;
        setJumping(motor.jump);
        setSprinting(motor.sprint && motor.forward > 0 && getFoodData().getFoodLevel() > 6);
        setShiftKeyDown(motor.sneak);
    }

    private static float rotateTowards(float current, float target, float maxStep) {
        float delta = Mth.wrapDegrees(target - current);
        return current + Mth.clamp(delta, -maxStep, maxStep);
    }

    //? if >=1.21.2 {
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        float before = getHealth();
        boolean hurt = super.hurtServer(level, source, damage);
        if (hurt && listener != null) listener.onHurt(source, before - getHealth());
        return hurt;
    }
    //?} else {
    /*@Override
    public boolean hurt(DamageSource source, float damage) {
        float before = getHealth();
        boolean hurt = super.hurt(source, damage);
        if (hurt && listener != null) listener.onHurt(source, before - getHealth());
        return hurt;
    }
    *///?}

    @Override
    public void die(DamageSource source) {
        Component deathMessage = getCombatTracker().getDeathMessage();
        super.die(source);
        motor.clear();
        if (listener != null) listener.onDeath(source, deathMessage);
    }

    @Override
    public void sendChatMessage(OutgoingChatMessage message, boolean filtered, ChatType.Bound chatType) {
        if (listener != null) listener.onDirectMessage(chatType.name(), message.content(), chatType);
    }

    /** Movement inputs the body applies on its next physics tick. */
    public static final class Motor {
        public float forward;
        public float strafe;
        public boolean jump;
        public boolean sprint;
        public boolean sneak;
        public @Nullable Float targetYaw;
        public @Nullable Float targetPitch;

        public void clear() {
            forward = 0;
            strafe = 0;
            jump = false;
            sprint = false;
            sneak = false;
            targetYaw = null;
            targetPitch = null;
        }
    }

    /** Body events the companion's mind observes. */
    public interface BodyListener {
        void onHurt(DamageSource source, float healthLost);

        void onDeath(DamageSource source, Component deathMessage);

        void onDirectMessage(Component sender, Component content, ChatType.Bound chatType);
    }
}
