package com.voxelmind.local.net;

import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.companion.Companion;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Server → client: a companion said something the player heard. Clients with this mod can speak it
 * aloud (voice output); everyone else still reads it in chat. Sending is passive delivery only.
 */
public final class Speech {
    public record Line(String id, String name, String kind, String text) implements CustomPacketPayload {
        public static final Type<Line> TYPE = new Type<>(Identifier.fromNamespaceAndPath(VoxelMindLocal.MOD_ID, "speech"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Line> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), Line::id,
                ByteBufCodecs.stringUtf8(64), Line::name,
                ByteBufCodecs.stringUtf8(16), Line::kind,
                ByteBufCodecs.stringUtf8(2048), Line::text,
                Line::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private Speech() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Line.TYPE, Line.CODEC);
    }

    public static void say(Companion c, String text, List<ServerPlayer> heardBy) {
        String clean = text.length() > 2048 ? text.substring(0, 2048) : text;
        Line line = new Line(c.state.id.toString(), c.name(), c.state.kind, clean);
        for (ServerPlayer p : heardBy) {
            if (ServerPlayNetworking.canSend(p, Line.TYPE)) ServerPlayNetworking.send(p, line);
        }
    }
}
