package com.voxelmind.local.client;

import com.mojang.authlib.GameProfile;
import com.voxelmind.local.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Menu avatars with the same skin the companion wears in the world: borrowed textures, or the default for its id. */
public final class Faces {
    private static final Map<String, PlayerSkin> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> REQUESTED = new ConcurrentHashMap<>();

    private Faces() {}

    public static PlayerSkin of(MenuState.Mate mate) {
        if (mate.skinValue().isBlank()) return DefaultPlayerSkin.get(mate.id());
        String key = mate.id() + ":" + mate.skinValue().hashCode();
        PlayerSkin skin = LOADED.get(key);
        if (skin != null) return skin;
        if (REQUESTED.putIfAbsent(key, true) == null) {
            GameProfile profile = Compat.texturedProfile(mate.id(), mate.name(), mate.skinValue(),
                    mate.skinSignature().isBlank() ? null : mate.skinSignature());
            Minecraft.getInstance().getSkinManager().get(profile)
                    .thenAccept(result -> result.ifPresent(s -> LOADED.put(key, s)));
        }
        return DefaultPlayerSkin.get(mate.id());
    }

    /** The card portrait: the player face of a companion, the creature of a builder, a star for a god. */
    public static void portrait(GuiGraphicsExtractor g, MenuState.Mate mate, int x, int y, int size) {
        if (!mate.hosted()) {
            PlayerFaceExtractor.extractRenderState(g, of(mate), x, y, size);
            return;
        }
        g.fill(x, y, x + size, y + size, mate.kind().equals("god") ? 0x55E0B040 : 0x5540C0E0);
        ItemStack icon = new ItemStack(switch (mate.kind().equals("god") ? "god" : mate.bodyType()) {
            case "god" -> Items.NETHER_STAR;
            case "bee" -> Items.BEE_SPAWN_EGG;
            case "vex" -> Items.VEX_SPAWN_EGG;
            case "parrot" -> Items.PARROT_SPAWN_EGG;
            case "bat" -> Items.BAT_SPAWN_EGG;
            default -> Items.ALLAY_SPAWN_EGG;
        });
        g.item(icon, x + (size - 16) / 2, y + (size - 16) / 2);
    }
}
