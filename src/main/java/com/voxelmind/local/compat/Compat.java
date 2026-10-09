package com.voxelmind.local.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.Holder;
//? if <26.1 {
/*import net.minecraft.core.RegistryAccess;
*///?}
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Calls whose shape differs between Minecraft versions and cannot be bridged by a plain
 * rename (see tools/versioned-renames.gradle for those). The checked-in bodies are the 26.2 ones;
 * Stonecutter swaps the //? blocks per target. docs/VERSIONS.md lists every place with version code.
 */
public final class Compat {
    private Compat() {}

    /** The result item of {@code recipe} for {@code input}. */
    public static <I extends RecipeInput> ItemStack assemble(Recipe<I> recipe, I input) {
        //? if >=26.1 {
        return recipe.assemble(input);
        //?} else {
        /*return recipe.assemble(input, RegistryAccess.EMPTY);
        *///?}
    }

    /** The items an ingredient accepts. */
    public static Stream<Holder<Item>> ingredientItems(Ingredient ingredient) {
        //? if >=1.21.4 {
        return ingredient.items();
        //?} else if >=1.21.2 {
        /*return ingredient.items().stream();
        *///?} else {
        /*return java.util.Arrays.stream(ingredient.getItems()).map(ItemStack::getItemHolder);
        *///?}
    }

    /** The ingredients of a crafting recipe in the order the recipe lists them (shapeless: all of them). */
    public static List<Ingredient> ingredients(CraftingRecipe recipe) {
        //? if >=1.21.2 {
        return recipe.placementInfo().ingredients();
        //?} else {
        /*return recipe.getIngredients();
        *///?}
    }

    /** The grid cells of a shaped recipe, empty where the pattern leaves a gap. */
    public static List<Optional<Ingredient>> shapedCells(ShapedRecipe recipe) {
        //? if >=1.21.2 {
        return recipe.getIngredients();
        //?} else {
        /*List<Optional<Ingredient>> cells = new java.util.ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) cells.add(ingredient.isEmpty() ? Optional.empty() : Optional.of(ingredient));
        return cells;
        *///?}
    }

    /** What a smelting recipe takes in. */
    public static Ingredient smeltInput(SmeltingRecipe recipe) {
        //? if >=1.21.2 {
        return recipe.input();
        //?} else {
        /*return recipe.getIngredients().get(0);
        *///?}
    }

    /** Ticks a smelting recipe needs per item. */
    public static int cookingTime(SmeltingRecipe recipe) {
        //? if >=1.21.2 {
        return recipe.cookingTime();
        //?} else {
        /*return recipe.getCookingTime();
        *///?}
    }

    /** Whether a furnace burns this item as fuel. */
    public static boolean isFuel(ServerLevel level, ItemStack stack) {
        //? if >=1.21.2 {
        return level.fuelValues().isFuel(stack);
        //?} else {
        /*return net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(stack);
        *///?}
    }

    /** Ticks one unit of this fuel burns in a furnace. */
    public static int burnDuration(ServerLevel level, ItemStack stack) {
        //? if >=1.21.2 {
        return level.fuelValues().burnDuration(stack);
        //?} else {
        /*return net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.getFuel().getOrDefault(stack.getItem(), 0);
        *///?}
    }

    /** A game profile carrying a signed {@code textures} property: the skin a companion borrows from an account. */
    public static GameProfile texturedProfile(UUID id, String name, String value, @Nullable String signature) {
        //? if >=1.21.9 {
        com.google.common.collect.Multimap<String, com.mojang.authlib.properties.Property> map =
                com.google.common.collect.LinkedHashMultimap.create();
        map.put("textures", new com.mojang.authlib.properties.Property("textures", value, signature));
        return new GameProfile(id, name, new com.mojang.authlib.properties.PropertyMap(map));
        //?} else {
        /*GameProfile profile = new GameProfile(id, name);
        profile.getProperties().put("textures", new com.mojang.authlib.properties.Property("textures", value, signature));
        return profile;
        *///?}
    }

    /** Tells the server the (client-less) body has finished loading, like a real client's loaded packet. */
    public static void acceptPlayerLoad(ServerPlayer body) {
        //? if >=1.21.4 {
        body.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        //?}
    }

    /** The server level a player is in (ServerPlayer.level() only returns ServerLevel from 1.21.6 on). */
    public static ServerLevel level(ServerPlayer player) {
        //? if >=1.21.6 {
        return player.level();
        //?} else {
        /*return player.serverLevel();
        *///?}
    }

    /**
     * Loads the saved player data of a companion into its freshly built body (nothing happens when the
     * companion has none yet). Returns whether data was found.
     */
    public static boolean loadPlayerData(MinecraftServer server, ServerPlayer body, UUID id, String name) {
        //? if >=1.21.9 {
        try (net.minecraft.util.ProblemReporter.ScopedCollector reporter =
                     new net.minecraft.util.ProblemReporter.ScopedCollector(com.mojang.logging.LogUtils.getLogger())) {
            java.util.Optional<net.minecraft.nbt.CompoundTag> data =
                    server.getPlayerList().loadPlayerData(new net.minecraft.server.players.NameAndId(id, name));
            if (data.isEmpty()) return false;
            body.load(net.minecraft.world.level.storage.TagValueInput.create(reporter, server.registryAccess(), data.get()));
            return true;
        }
        //?} else if >=1.21.6 {
        /*// PlayerList.load reads the data and applies it to the body itself.
        try (net.minecraft.util.ProblemReporter.ScopedCollector reporter =
                     new net.minecraft.util.ProblemReporter.ScopedCollector(com.mojang.logging.LogUtils.getLogger())) {
            return server.getPlayerList().load(body, reporter).isPresent();
        }
        *///?} else {
        /*// PlayerList.load reads the data and applies it to the body itself.
        return server.getPlayerList().load(body).isPresent();
        *///?}
    }
}
