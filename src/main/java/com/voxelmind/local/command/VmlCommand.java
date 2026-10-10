package com.voxelmind.local.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.companion.CompanionManager;
import com.voxelmind.local.config.LocalConfig;
import com.voxelmind.local.net.MenuPayloads;
import com.voxelmind.local.provider.ModelCatalog;
import com.voxelmind.local.provider.OpenRouterAccount;
import com.voxelmind.local.provider.Player2;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** /vml — summon, dismiss and configure companions. */
public final class VmlCommand {
    private VmlCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // The AI settings hold keys and the Sparks token: the singleplayer host, the console or an owner-level
        // operator may change them, never a command block or a datapack function (those run at level 2).
        Predicate<CommandSourceStack> admin = src -> Commands.hasPermission(Commands.LEVEL_OWNERS).test(src)
                || src.getPlayer() != null && src.getServer().isSingleplayerOwner(src.getPlayer().nameAndId());

        dispatcher.register(Commands.literal("vml")
                .executes(VmlCommand::menu)
                .then(Commands.literal("help").executes(VmlCommand::help))
                .then(Commands.literal("summon")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> summon(ctx, null, null))
                                .then(Commands.argument("character", StringArgumentType.greedyString())
                                        .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "character"), null)))))
                .then(Commands.literal("builder")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> summon(ctx, null, "builder"))
                                .then(Commands.argument("character", StringArgumentType.greedyString())
                                        .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "character"), "builder")))))
                .then(Commands.literal("god")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> summon(ctx, null, "god"))
                                .then(Commands.argument("character", StringArgumentType.greedyString())
                                        .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "character"), "god")))))
                .then(setting("campaign", "sandbox", "trials", "living", "chaos"))
                .then(setting("chaos", "1", "2", "3", "4", "5"))
                .then(setting("guests", "on", "off"))
                .then(setting("body", "allay", "bee", "vex", "parrot", "bat"))
                .then(Commands.literal("dismiss")
                        .executes(ctx -> dismiss(ctx, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> dismiss(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("forget")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> forget(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("list").executes(VmlCommand::list))
                .then(Commands.literal("character")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(VmlCommand::character))))
                .then(Commands.literal("mode")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("normal");
                                            builder.suggest("hardcore");
                                            return builder.buildFuture();
                                        })
                                        .executes(VmlCommand::mode))))
                .then(Commands.literal("worlds")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("worlds", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("this");
                                            builder.suggest("all");
                                            return builder.buildFuture();
                                        })
                                        .executes(VmlCommand::worlds))))
                .then(Commands.literal("skin")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("account", StringArgumentType.word())
                                        .executes(VmlCommand::skin))))
                .then(Commands.literal("status").executes(VmlCommand::status))
                .then(Commands.literal("sparks").executes(VmlCommand::sparks)
                        .then(Commands.literal("redeem").requires(admin)
                                .then(Commands.argument("code", StringArgumentType.greedyString()).executes(VmlCommand::redeem))))
                .then(Commands.literal("setup").executes(ctx -> setup(ctx, admin.test(ctx.getSource()))))
                .then(Commands.literal("test").requires(admin).executes(VmlCommand::test))
                .then(Commands.literal("login").requires(admin)
                        .executes(ctx -> login(ctx, null))
                        .then(Commands.argument("code", StringArgumentType.greedyString())
                                .executes(ctx -> login(ctx, StringArgumentType.getString(ctx, "code")))))
                .then(Commands.literal("player2").requires(admin).executes(VmlCommand::player2))
                .then(Commands.literal("key").requires(admin)
                        .then(Commands.argument("key", StringArgumentType.greedyString()).executes(VmlCommand::key)))
                .then(Commands.literal("models").requires(admin)
                        .executes(ctx -> models(ctx, ""))
                        .then(Commands.argument("search", StringArgumentType.greedyString())
                                .executes(ctx -> models(ctx, StringArgumentType.getString(ctx, "search")))))
                .then(Commands.literal("model").requires(admin)
                        .then(Commands.argument("model", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                                    if (ModelCatalog.cached().isEmpty()) ModelCatalog.fetch(false);
                                    if ("default".startsWith(typed)) builder.suggest("default");
                                    for (ModelCatalog.Model m : ModelCatalog.cached()) {
                                        if (m.tools() && m.id().toLowerCase(Locale.ROOT).contains(typed)) builder.suggest(m.id());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(VmlCommand::model)))
                .then(Commands.literal("endpoint").requires(admin)
                        .then(Commands.argument("endpoint", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    ENDPOINTS.keySet().forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(VmlCommand::endpoint)))
                .then(Commands.literal("reload").requires(admin).executes(VmlCommand::reload)));
    }

    /** /vml <key> <name> <value> for one God or Builder setting, with the values suggested. */
    private static LiteralArgumentBuilder<CommandSourceStack> setting(String key, String... values) {
        return Commands.literal(key)
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("value", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (String v : values) builder.suggest(v);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> hostedSetting(ctx, key))));
    }

    private static int hostedSetting(CommandContext<CommandSourceStack> ctx, String key) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        String value = StringArgumentType.getString(ctx, "value");
        Companion c = manager(ctx).find(owner.getUUID(), name);
        if (c == null) return fail(ctx, "You have no companion named " + name + ".");
        String error = manager(ctx).setHosted(c, key, value);
        if (error != null) return fail(ctx, error);
        ok(ctx, c.name() + ": " + key + " is " + value.toLowerCase(Locale.ROOT) + " now.");
        return 1;
    }

    /**
     * Player2 joules are used up: what that means and the three ways on, in the player's language
     * (translated on the client, English fallback inside the key's text).
     */
    public static MutableComponent joulesNotice(String name) {
        MutableComponent more = Component.translatableWithFallback("voxelmind_local.notice.joules.more", "[Player2 page]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(Player2.PROFILE_URL)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(Player2.PROFILE_URL))));
        MutableComponent other = Component.translatableWithFallback("voxelmind_local.notice.joules.other", "[Choose another AI]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand("/vml setup")));
        return Component.literal("[VoxelMind] ").withStyle(ChatFormatting.GOLD)
                .append(Component.translatableWithFallback("voxelmind_local.notice.joules",
                        "%s cannot think right now: your free Player2 AI power (joules) is used up. It recharges daily on its own; Player2 Patron gives more, or you switch to another AI.",
                        name).withStyle(ChatFormatting.GOLD))
                .append(" ").append(more).append(" ").append(other);
    }

    /** Name of the Sparks account this world thinks with (its token's account), or {@code fallback} before it is known. */
    public static String sparksAccount(String fallback) {
        String name = LocalConfig.get().voxelmindName;
        return name == null || name.isBlank() ? fallback : name;
    }

    /** Sparks used up: what that means and the ways on. The link names the account the world spends, not the companion's owner. */
    public static MutableComponent sparksNotice(String name, String owner) {
        String account = sparksAccount(owner);
        MutableComponent buy = Component.translatableWithFallback("voxelmind_local.notice.sparks.buy", "[Packs and prices]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(com.voxelmind.local.provider.VoxelMindApi.buyUrl(account))))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(com.voxelmind.local.provider.VoxelMindApi.buyUrl(account)))));
        MutableComponent other = Component.translatableWithFallback("voxelmind_local.notice.joules.other", "[Choose another AI]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand("/vml setup")));
        return Component.literal("[VoxelMind] ").withStyle(ChatFormatting.GOLD)
                .append(Component.translatableWithFallback("voxelmind_local.notice.sparks",
                        "%s cannot think right now: the Sparks of %s are used up. New free Sparks come tomorrow. Other ways to think: Player2, your own key, a local model.",
                        name, account).withStyle(ChatFormatting.GOLD))
                .append(" ").append(buy).append(" ").append(other);
    }

    /** The notice for whichever prepaid AI power ran out. */
    public static MutableComponent emptyNotice(String name, String owner) {
        return LocalConfig.get().isVoxelMind() ? sparksNotice(name, owner) : joulesNotice(name);
    }

    /** Clickable "[AI settings]" that opens the settings screen for the host, or says who sets up the AI elsewhere. */
    public static MutableComponent settingsLink() {
        return Component.translatableWithFallback("voxelmind_local.notice.settings", "[AI settings]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand("/vml setup"))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatableWithFallback("voxelmind_local.notice.settings.hover",
                        "Choose how your AI beings think: VoxelMind Sparks, Player2, your OpenRouter account or a local model"))));
    }

    /** "<name> has no AI connected yet" with the four ways and the settings link. */
    public static MutableComponent noAiNotice(String name) {
        return Component.literal("[VoxelMind] ").withStyle(ChatFormatting.GOLD)
                .append(Component.translatableWithFallback("voxelmind_local.notice.no_ai",
                        "%s has no AI connected yet. Choose VoxelMind Sparks (sign in with your Minecraft account, welcome Sparks included), Player2, your OpenRouter account or a local model:",
                        name).withStyle(ChatFormatting.GOLD))
                .append(" ").append(settingsLink());
    }

    private static CompanionManager manager(CommandContext<CommandSourceStack> ctx) {
        CompanionManager m = CompanionManager.get();
        if (m == null) throw new IllegalStateException("VoxelMind Local is not running");
        return m;
    }

    private static void ok(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), false);
    }

    private static void info(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Component.literal(text));
        return 0;
    }

    /** The companion menu for players whose game has this mod; the command list for everyone else. */
    private static int menu(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null && !(player instanceof CompanionPlayer) && MenuPayloads.open(player, "menu")) return 1;
        return help(ctx);
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        info(ctx, """
                VoxelMind Local — your AI companion lives in this world. Menu: /vml or the V key.
                /vml summon <name> [character]  bring a companion to you (new or existing)
                /vml builder <name> [character] a fairy builder: fills, whole buildings, undo
                /vml god <name> [character]     a bodiless game master for this world
                /vml campaign|chaos|guests <god> <value> · /vml body <builder> allay|bee|vex|parrot|bat
                /vml dismiss [name]             send it away (it remembers you)
                /vml character <name> <text>    describe its character
                /vml skin <name> <account>      wear the skin of a Minecraft account
                /vml mode <name> normal|hardcore  hardcore: one life, a death is final
                /vml worlds <name> this|all     only this world, or with you into all your worlds
                /vml list · /vml status · /vml sparks · /vml sparks redeem <code>
                /vml setup                      AI settings: sign-in, key, model
                /vml login · /vml key <key> · /vml models [search] · /vml model <id>
                /vml player2                    free AI with a Player2 account (sign in with a code)
                /vml endpoint openrouter|player2|voxelmind|ollama|lmstudio|<url> · /vml test · /vml reload
                /vml forget <name>              delete it and its memory
                Talk to it in normal chat; it hears you within 48 blocks.""");
        ctx.getSource().sendSuccess(() -> link("voxelmind_local.help.feature_request", "[Feature request on Discord]", com.voxelmind.local.VoxelMindLocal.FEATURE_REQUEST_URL)
                .append(" ").append(link("voxelmind_local.help.support", "[♥ Support VoxelMind on Ko-fi]", com.voxelmind.local.VoxelMindLocal.SUPPORT_URL)), false);
        return 1;
    }

    private static MutableComponent link(String key, String fallback, String url) {
        return Component.translatableWithFallback(key, fallback).withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
    }

    private static int summon(CommandContext<CommandSourceStack> ctx, String character, String kind) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        if (owner instanceof CompanionPlayer) return fail(ctx, "Companions cannot summon companions.");
        String name = StringArgumentType.getString(ctx, "name");
        String error = manager(ctx).summon(owner, name, character, kind);
        if (error != null && !error.endsWith("is here.")) return fail(ctx, error);
        ok(ctx, error != null ? error : name + " joined you.");
        if (!LocalConfig.get().hasCredentials()) {
            ctx.getSource().sendSuccess(() -> noAiNotice(name), false);
        }
        return 1;
    }

    private static Companion pick(CommandContext<CommandSourceStack> ctx, ServerPlayer owner, String name) {
        List<Companion> mine = manager(ctx).ownedBy(owner.getUUID());
        if (name != null) return manager(ctx).find(owner.getUUID(), name);
        for (Companion c : mine) if (c.present()) return c;
        return mine.isEmpty() ? null : mine.getFirst();
    }

    private static int dismiss(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Companion c = pick(ctx, owner, name);
        if (c == null) return fail(ctx, "You have no companion" + (name == null ? "" : " named " + name) + ".");
        manager(ctx).dismiss(c, true, true);
        ok(ctx, c.name() + " left. /vml summon " + c.name() + " brings it back.");
        return 1;
    }

    private static int forget(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Companion c = manager(ctx).find(owner.getUUID(), name);
        if (c == null) return fail(ctx, "You have no companion named " + name + ".");
        manager(ctx).delete(c);
        ok(ctx, c.name() + " and its memory were deleted.");
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        StringBuilder out = new StringBuilder("Companions:");
        for (Companion c : manager(ctx).all()) {
            out.append("\n- ").append(c.name()).append(" (").append(c.state.hosted() ? c.state.kind + ", " : "").append("owner ").append(c.state.ownerName)
                    .append(!c.state.hosted() && c.state.hardcore() ? ", hardcore" : "").append(") ")
                    .append(c.state.fallen() ? "fell for good" : c.hosted != null ? (c.hosted.active() ? "in the world, turn #" + c.hosted.turns() : "away")
                            : c.body() != null ? "in the world, " + c.bodyActionLine() : "away");
        }
        if (manager(ctx).all().isEmpty()) out.append(" none yet. /vml summon <name>");
        info(ctx, out.toString());
        return 1;
    }

    private static int character(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        Companion c = manager(ctx).find(owner.getUUID(), name);
        if (c == null) return fail(ctx, "You have no companion named " + name + ".");
        c.state.persona = StringArgumentType.getString(ctx, "text");
        c.event("note", "your owner described your character anew: \"" + c.state.persona + "\"", true, null);
        manager(ctx).markDirty();
        ok(ctx, c.name() + "'s character is updated.");
        return 1;
    }

    private static int worlds(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        String which = StringArgumentType.getString(ctx, "worlds").toLowerCase(Locale.ROOT);
        Companion c = manager(ctx).find(owner.getUUID(), name);
        if (c == null) return fail(ctx, "You have no companion named " + name + ".");
        String scope = which.equals("all") ? "global" : which.equals("this") ? "world" : which;
        String error = manager(ctx).setScope(c, scope);
        if (error != null) return fail(ctx, error);
        ok(ctx, scope.equals("global")
                ? c.name() + " goes with you into all your worlds now. Memories and character travel; each world keeps its own inventory."
                : c.name() + " lives in this world only now.");
        return 1;
    }

    private static int mode(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        String mode = StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT);
        Companion c = manager(ctx).find(owner.getUUID(), name);
        if (c == null) return fail(ctx, "You have no companion named " + name + ".");
        String error = manager(ctx).setMode(c, mode);
        if (error != null) return fail(ctx, error);
        ok(ctx, c.name() + (mode.equals("hardcore") ? " lives in hardcore mode now: one life, a death is final."
                : " lives in normal mode now: it respawns after a death."));
        return 1;
    }

    private static int skin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        String account = StringArgumentType.getString(ctx, "account");
        Companion c = manager(ctx).find(owner.getUUID(), name);
        if (c == null) return fail(ctx, "You have no companion named " + name + ".");
        info(ctx, "Looking up the skin of " + account + "…");
        var server = ctx.getSource().getServer();
        com.voxelmind.local.companion.Skins.fetch(account).whenComplete((skin, error) -> server.execute(() -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                owner.sendSystemMessage(Component.literal("Could not load the skin of " + account + ": " + cause.getMessage()).withStyle(ChatFormatting.RED));
                return;
            }
            CompanionManager m = CompanionManager.get();
            if (m == null) return;
            m.applySkin(c, skin);
            owner.sendSystemMessage(Component.literal(c.name() + " now wears the skin of " + skin.sourceName() + ".").withStyle(ChatFormatting.GREEN));
        }));
        return 1;
    }

    /** "54.0 Sparks + 10 free today (≈ 64 min of play with the standard model)". */
    public static String sparks(com.voxelmind.local.provider.VoxelMindApi.Balance b) {
        String free = b.freeCapReached() ? "today's free Sparks are used up for everyone" : String.format(Locale.ROOT, "%.0f free today", b.free());
        return String.format(Locale.ROOT, "%.1f Sparks + %s (≈ %.0f min of play with the standard model)", b.sparks(), free, b.total());
    }

    /** Sparks balance, the shop link and, for the host, the token for a dedicated server. */
    private static int sparks(CommandContext<CommandSourceStack> ctx) {
        LocalConfig cfg = LocalConfig.get();
        var source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        String token = cfg.voxelmindKey == null ? "" : cfg.voxelmindKey.trim();
        if (!cfg.isVoxelMind() || token.isEmpty()) {
            String name = player == null ? "" : player.getGameProfile().name();
            source.sendSuccess(() -> Component.translatableWithFallback("voxelmind_local.sparks.about",
                    "VoxelMind Sparks: our own AI service, one Spark is about one minute of play. Sign in with your Minecraft account under /vml setup → VoxelMind Sparks (welcome Sparks included). Packs and prices:")
                    .append(" ").append(buyLink(name)), false);
            return 1;
        }
        com.voxelmind.local.provider.VoxelMindApi.balance(token).whenComplete((b, error) -> source.getServer().execute(() -> {
            if (error != null) {
                source.sendFailure(Component.literal("VoxelMind: " + message(error)));
                return;
            }
            // Packs for the account this world spends (the token's), whoever asks.
            MutableComponent line = Component.literal("VoxelMind (" + b.name() + "): " + sparks(b) + " ").append(buyLink(b.name()));
            boolean host = player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
            if (host) {
                line.append(" ").append(Component.translatableWithFallback("voxelmind_local.sparks.account", "[Account]").withStyle(style -> style
                        .withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(com.voxelmind.local.provider.VoxelMindApi.accountUrl(token))))
                        .withHoverEvent(new HoverEvent.ShowText(Component.translatableWithFallback("voxelmind_local.sparks.account.hover",
                                "Balance, spending per day and credits. A private link: it contains your sign-in.")))));
                line.append(" ").append(Component.translatableWithFallback("voxelmind_local.sparks.copy", "[Copy token for a server]").withStyle(style -> style
                        .withColor(ChatFormatting.GRAY).withUnderlined(true)
                        .withClickEvent(new ClickEvent.CopyToClipboard(token))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("On a dedicated server: /vml endpoint voxelmind, then /vml key <token>")))));
            }
            source.sendSuccess(() -> line, false);
        }));
        return 1;
    }

    private static MutableComponent buyLink(String name) {
        return Component.translatableWithFallback("voxelmind_local.notice.sparks.buy", "[Packs and prices]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(com.voxelmind.local.provider.VoxelMindApi.buyUrl(name))))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(com.voxelmind.local.provider.VoxelMindApi.buyUrl(name)))));
    }

    /** Redeems a Sparks code (e.g. from the email to former subscribers) for the account this world thinks with. */
    private static int redeem(CommandContext<CommandSourceStack> ctx) {
        LocalConfig cfg = LocalConfig.get();
        var source = ctx.getSource();
        String token = cfg.voxelmindKey == null ? "" : cfg.voxelmindKey.trim();
        if (token.isEmpty()) {
            return fail(ctx, "Sign in with your Minecraft account first: /vml setup → VoxelMind Sparks. Or redeem the code for any name at "
                    + com.voxelmind.local.provider.VoxelMindApi.SITE + "/sparks/redeem");
        }
        String code = StringArgumentType.getString(ctx, "code").trim();
        com.voxelmind.local.provider.VoxelMindApi.redeem(token, code).whenComplete((sparks, error) -> source.getServer().execute(() -> {
            if (error != null) source.sendFailure(Component.literal("VoxelMind: " + message(error)));
            else source.sendSuccess(() -> Component.translatableWithFallback("voxelmind_local.sparks.redeemed",
                    "%s Sparks were added to the account of %s. /vml sparks shows the balance.",
                    String.format(Locale.ROOT, "%.0f", sparks), sparksAccount("this world")).withStyle(ChatFormatting.GREEN), false);
        }));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        LocalConfig cfg = LocalConfig.get();
        String key = cfg.effectiveApiKey();
        String masked = key.isEmpty() ? "none" : key.substring(0, Math.min(8, key.length())) + "…";
        ModelCatalog.Model listed = ModelCatalog.find(cfg.model);
        info(ctx, "Endpoint: " + cfg.baseUrl + "\nModel: " + (cfg.isPlayer2() ? "chosen by Player2" : cfg.isVoxelMind() ? "VoxelMind Standard" : cfg.model) + (listed != null ? " (" + ModelCatalog.priceLine(listed) + ")" : "")
                + (cfg.effectiveMemoryModel().equals(cfg.model) ? "" : "\nMemory model: " + cfg.effectiveMemoryModel())
                + (cfg.isOpenRouter() && cfg.providerOrder != null && !cfg.providerOrder.isEmpty()
                        ? "\nProviders: " + String.join(" → ", cfg.providerOrder) : "")
                + "\nKey: " + masked
                + "\nRequest limit: " + cfg.maxRequestsPerMinute + "/min · Config: " + LocalConfig.path());
        if (cfg.isPlayer2() && !key.isEmpty()) {
            var source = ctx.getSource();
            Player2.balance(key).whenComplete((b, error) -> source.getServer().execute(() -> {
                if (error != null) source.sendFailure(Component.literal("Player2: " + message(error)));
                else source.sendSuccess(() -> Component.literal("Player2: " + balance(b)), false);
            }));
        }
        if (cfg.isVoxelMind() && !key.isEmpty()) {
            var source = ctx.getSource();
            com.voxelmind.local.provider.VoxelMindApi.balance(key).whenComplete((b, error) -> source.getServer().execute(() -> {
                if (error != null) source.sendFailure(Component.literal("VoxelMind: " + message(error)));
                else source.sendSuccess(() -> Component.literal("VoxelMind: " + sparks(b)), false);
            }));
        }
        if (cfg.isOpenRouter() && !key.isEmpty()) {
            var source = ctx.getSource();
            OpenRouterAccount.status(key).whenComplete((st, error) -> source.getServer().execute(() -> {
                if (error != null) {
                    source.sendFailure(Component.literal("OpenRouter: " + message(error)));
                    return;
                }
                source.sendSuccess(() -> Component.literal("OpenRouter: " + credit(st)), false);
            }));
        }
        return 1;
    }

    /** "$4.20 credit left · $0.31 used today", from whatever OpenRouter reported. */
    public static String credit(OpenRouterAccount.KeyStatus st) {
        StringBuilder out = new StringBuilder();
        if (!Double.isNaN(st.balance())) out.append(String.format(Locale.ROOT, "$%.2f credit left", st.balance()));
        if (!Double.isNaN(st.limitRemaining())) {
            if (!out.isEmpty()) out.append(" · ");
            out.append(String.format(Locale.ROOT, "key limit: $%.2f left", st.limitRemaining()));
        }
        if (!Double.isNaN(st.usageToday())) {
            if (!out.isEmpty()) out.append(" · ");
            out.append(String.format(Locale.ROOT, "$%.2f used today", st.usageToday()));
        }
        return out.isEmpty() ? "key accepted" : out.toString();
    }

    /** "87 joules · Patron", as Player2 reports it. */
    public static String balance(Player2.Balance b) {
        return b.joules() + " joules" + (b.tier().isBlank() ? " (free tier, recharges daily)" : " · " + b.tier());
    }

    /** Code sign-in with Player2; the player approves in any browser, so it works on dedicated servers too. */
    private static int player2(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        info(ctx, "Starting the Player2 sign-in…");
        Player2.startDeviceLogin().whenComplete((login, error) -> source.getServer().execute(() -> {
            if (error != null) {
                source.sendFailure(Component.literal("Player2: " + message(error)));
                return;
            }
            MutableComponent link = Component.literal("[Open Player2]").withStyle(style -> style
                    .withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenUrl(login.url()))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(login.url().toString()))));
            source.sendSuccess(() -> Component.literal("Open ").append(link)
                    .append(" and confirm the code " + login.userCode() + ". Waiting…"), false);
            login.key().whenComplete((key, failed) -> source.getServer().execute(() -> {
                if (failed != null) {
                    source.sendFailure(Component.literal("Player2 sign-in did not finish: " + message(failed)));
                    return;
                }
                Player2.adopt(key);
                source.sendSuccess(() -> Component.literal("Connected to Player2. Companions think with Player2 now.")
                        .withStyle(ChatFormatting.GREEN), false);
            }));
        }));
        return 1;
    }

    private static String message(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        if (cause instanceof java.net.ConnectException || cause instanceof java.net.http.HttpConnectTimeoutException) {
            return "the service cannot be reached right now (no connection). Check the internet connection and try again.";
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    private static int setup(CommandContext<CommandSourceStack> ctx, boolean mayChange) {
        ServerPlayer player = ctx.getSource().getPlayer();
        boolean host = player != null && ctx.getSource().getServer().isSingleplayerOwner(player.nameAndId());
        if (host && MenuPayloads.open(player, "settings")) return 1;
        if (!mayChange) {
            info(ctx, "The AI of this world is set up by its host or an operator (/vml status shows which one it uses).");
            return 1;
        }
        // On a dedicated server the settings belong to the server, not to this player's game.
        info(ctx, "Settings of this server: VoxelMind Sparks with /vml endpoint voxelmind and /vml key <token> (\"Copy token\" in the "
                + "singleplayer settings), Player2 with /vml player2, OpenRouter with /vml login or /vml key <key> and /vml models, "
                + "or /vml endpoint ollama|lmstudio|<url> for a local model. /vml test checks it.");
        return 1;
    }

    /** One tiny request with a single tool, so an operator sees whether key, model and tool calls work. */
    private static int test(CommandContext<CommandSourceStack> ctx) {
        LocalConfig cfg = LocalConfig.get();
        if (!cfg.hasCredentials()) return fail(ctx, "No key yet: /vml login or /vml key <key>.");
        var source = ctx.getSource();
        String model = cfg.model;
        String label = cfg.modelLabel();
        info(ctx, "Asking " + label + " at " + cfg.baseUrl + "…");
        com.voxelmind.local.mind.LlmClient.probe(cfg.baseUrl, cfg.effectiveApiKey(), model).whenComplete((response, error) ->
                source.getServer().execute(() -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        source.sendFailure(Component.literal("Test failed: " + com.voxelmind.local.mind.LlmClient.explain(cause)));
                        return;
                    }
                    boolean waved = response.toolCalls().stream().anyMatch(call -> call.name().equals("wave"));
                    String seconds = String.format(Locale.ROOT, "%.1f s", response.latencyMs() / 1000.0);
                    if (waved) {
                        source.sendSuccess(() -> Component.literal("Test ok: " + label + " answered with a tool call in " + seconds + ".")
                                .withStyle(ChatFormatting.GREEN), false);
                    } else {
                        source.sendFailure(Component.literal("Test: " + label + " answered in " + seconds
                                + " but called no tool. Companions need tool calls to act; try another model."));
                    }
                }));
        return 1;
    }

    private static int login(CommandContext<CommandSourceStack> ctx, String code) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (code == null) {
            var url = OpenRouterAccount.startHeadlessLogin(player.getUUID());
            MutableComponent link = Component.literal("[Sign in with OpenRouter]").withStyle(style -> style
                    .withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenUrl(url))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(url.toString()))));
            ctx.getSource().sendSuccess(() -> Component.literal("Open ").append(link)
                    .append(", sign in, then copy the code OpenRouter shows and run /vml login <code>."), false);
            return 1;
        }
        var exchange = OpenRouterAccount.finishHeadlessLogin(player.getUUID(), code);
        if (exchange == null) return fail(ctx, "Start with /vml login first; the code belongs to that sign-in.");
        var source = ctx.getSource();
        info(ctx, "Checking the code with OpenRouter…");
        exchange.whenComplete((key, error) -> source.getServer().execute(() -> {
            if (error != null) {
                source.sendFailure(Component.literal(message(error) + " Run /vml login again."));
                return;
            }
            OpenRouterAccount.adopt(key);
            source.sendSuccess(() -> Component.literal("Signed in. The key is saved in " + LocalConfig.path() + ".")
                    .withStyle(ChatFormatting.GREEN), false);
        }));
        return 1;
    }

    private static int models(CommandContext<CommandSourceStack> ctx, String search) {
        if (LocalConfig.get().isPlayer2()) {
            info(ctx, "Player2 chooses the model from your Player2 account; there is nothing to pick here. "
                    + "/vml endpoint openrouter switches back to a model of your choice.");
            return 1;
        }
        var source = ctx.getSource();
        ModelCatalog.fetch(false).whenComplete((list, error) -> source.getServer().execute(() -> {
            if (error != null) {
                source.sendFailure(Component.literal("Could not list models: " + message(error)));
                return;
            }
            List<ModelCatalog.Model> hits = list.stream().filter(m -> m.tools() && ModelCatalog.matches(m, search)).toList();
            MutableComponent out = Component.literal(hits.size() + " models with tool calls"
                    + (search.isBlank() ? "" : " matching \"" + search + "\"") + (hits.size() > 12 ? ", first 12" : "") + " (click to use):");
            for (ModelCatalog.Model m : hits.subList(0, Math.min(12, hits.size()))) {
                boolean current = m.id().equals(LocalConfig.get().model);
                out.append(Component.literal("\n" + (current ? "▶ " : "- ")).withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(m.id()).withStyle(style -> style
                                .withColor(current ? ChatFormatting.GREEN : ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent.RunCommand("/vml model " + m.id()))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal(m.name())))))
                        .append(Component.literal("  " + ModelCatalog.priceLine(m) + (m.tested() ? " · tested" : ""))
                                .withStyle(ChatFormatting.GRAY));
            }
            if (hits.isEmpty()) out.append(Component.literal("\nnone; try another word, e.g. /vml models flash"));
            source.sendSuccess(() -> out, false);
        }));
        return 1;
    }

    private static int key(CommandContext<CommandSourceStack> ctx) {
        LocalConfig config = LocalConfig.get();
        config.setKey(StringArgumentType.getString(ctx, "key"));
        if (config.isVoxelMind()) config.voxelmindName = "";
        LocalConfig.save();
        ok(ctx, "Key for " + config.baseUrl + " saved to " + LocalConfig.path() + ".");
        if (config.isVoxelMind()) {
            // A token pasted on a server: learn whose Sparks it spends, for the links and notices.
            var source = ctx.getSource();
            String token = config.voxelmindKey;
            com.voxelmind.local.provider.VoxelMindApi.balance(token).whenComplete((b, error) -> source.getServer().execute(() -> {
                if (error != null) {
                    source.sendFailure(Component.literal("VoxelMind: " + message(error)));
                    return;
                }
                if (!token.equals(LocalConfig.get().voxelmindKey)) return;
                LocalConfig.get().voxelmindName = b.name();
                LocalConfig.save();
                source.sendSuccess(() -> Component.literal("This world now thinks with the Sparks of " + b.name() + ": " + sparks(b) + ".")
                        .withStyle(ChatFormatting.GREEN), false);
            }));
        }
        return 1;
    }

    private static int model(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "model").trim();
        if (id.equalsIgnoreCase("default")) id = LocalConfig.DEFAULT_MODEL;
        LocalConfig.get().selectModel(id);
        LocalConfig.save();
        ModelCatalog.Model listed = ModelCatalog.find(id);
        String note = "";
        if (listed != null && !listed.tools()) note = " Warning: the endpoint does not list tool calls for it; the companion needs them to act.";
        else if (listed == null && !ModelCatalog.cached().isEmpty()) note = " Note: the endpoint does not list this id; check /vml models.";
        else if (listed != null) note = " " + ModelCatalog.priceLine(listed) + ".";
        ok(ctx, "Model set to " + id + "." + note);
        return 1;
    }

    /** Short names for the usual endpoints; anything else is taken as a base URL. */
    public static final Map<String, String> ENDPOINTS = Map.of(
            "openrouter", LocalConfig.OPENROUTER_URL,
            "player2", Player2.WEB,
            "ollama", "http://localhost:11434/v1",
            "lmstudio", "http://localhost:1234/v1",
            "voxelmind", com.voxelmind.local.provider.VoxelMindApi.BASE);

    private static int endpoint(CommandContext<CommandSourceStack> ctx) {
        String arg = StringArgumentType.getString(ctx, "endpoint").trim();
        String url = ENDPOINTS.getOrDefault(arg.toLowerCase(Locale.ROOT), arg);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return fail(ctx, "Use voxelmind, player2, openrouter, ollama, lmstudio or a URL like http://localhost:11434/v1");
        }
        LocalConfig.get().selectEndpoint(url);
        LocalConfig.save();
        LocalConfig config = LocalConfig.get();
        ok(ctx, "Endpoint set to " + config.baseUrl + (config.isPlayer2()
                ? (config.hasCredentials() ? "." : ". Connect your Player2 account with /vml player2.")
                : config.isVoxelMind()
                ? (config.hasCredentials() ? "." : ". Add the token with /vml key <token> (\"Copy token\" in the singleplayer AI settings).")
                : ". Pick one of its models with /vml models."));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        LocalConfig.load();
        ok(ctx, "Config reloaded from " + LocalConfig.path() + ".");
        return 1;
    }
}
