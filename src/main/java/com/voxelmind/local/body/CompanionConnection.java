package com.voxelmind.local.body;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * The network side of a companion body. There is no client behind it, so every
 * outgoing packet is dropped and no inbound protocol is ever installed.
 */
public final class CompanionConnection extends Connection {
    public CompanionConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
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
