package com.voxelmind.local.net;

import com.voxelmind.local.VoxelMindLocal;
import com.voxelmind.local.companion.CompanionManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * The companion menu's channel. The client asks for a snapshot of its own companions and the server
 * answers with facts only; every change the menu makes goes through the normal /vml commands.
 */
public final class MenuPayloads {
    /** Snapshots stay well under the 1 MiB custom-payload limit. */
    private static final int MAX_JSON = 1 << 18;

    private MenuPayloads() {}

    /** Server → client: open a screen ("menu" or "settings"). */
    public record OpenScreen(String screen) implements CustomPacketPayload {
        public static final Type<OpenScreen> TYPE = new Type<>(id("open_screen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreen> CODEC =
                StreamCodec.composite(ByteBufCodecs.stringUtf8(32), OpenScreen::screen, OpenScreen::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: send me the current snapshot. */
    public record RequestSnapshot() implements CustomPacketPayload {
        public static final RequestSnapshot INSTANCE = new RequestSnapshot();
        public static final Type<RequestSnapshot> TYPE = new Type<>(id("request_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestSnapshot> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → client: the owner's companions as JSON (see {@link Snapshots}). */
    public record Snapshot(String json) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(id("snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC =
                StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_JSON), Snapshot::json, Snapshot::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(VoxelMindLocal.MOD_ID, path);
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(OpenScreen.TYPE, OpenScreen.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Snapshot.TYPE, Snapshot.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RequestSnapshot.TYPE, RequestSnapshot.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestSnapshot.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> {
                CompanionManager manager = CompanionManager.get();
                if (manager == null || !ServerPlayNetworking.canSend(player, Snapshot.TYPE)) return;
                String json = Snapshots.of(manager, player).toString();
                if (json.length() <= MAX_JSON) ServerPlayNetworking.send(player, new Snapshot(json));
            });
        });
    }

    /** Opens a screen on the player's client when it has this mod; false otherwise. */
    public static boolean open(ServerPlayer player, String screen) {
        if (!ServerPlayNetworking.canSend(player, OpenScreen.TYPE)) return false;
        ServerPlayNetworking.send(player, new OpenScreen(screen));
        return true;
    }
}
