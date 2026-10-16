package com.voxelmind.local.body;

import com.voxelmind.local.compat.Compat;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * The network side of a companion body. There is no client behind it, so outgoing
 * packets are dropped and no inbound protocol is ever installed. The one exception is
 * the motion the server sends a player for its own body (the knockback of a hit by a
 * player): a real client applies it to its movement, so the body takes it over as well.
 */
public final class CompanionConnection extends Connection {
    private final ServerPlayer player;

    public CompanionConnection(ServerPlayer player) {
        super(PacketFlow.SERVERBOUND);
        this.player = player;
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
        if (packet instanceof ClientboundSetEntityMotionPacket motion && player instanceof CompanionPlayer body
                && Compat.motionEntityId(motion) == body.getId()) {
            body.receiveMotion(Compat.motion(motion));
        }
    }

    @Override
    public void runOnceConnected(Consumer<Connection> action) {
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T packetListener) {
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
    }

    @Override
    public void handleDisconnection() {
    }
}
