package dev.darcosse.common.cobblemonsafari.command;

import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.access.EntryResult;
import dev.darcosse.common.cobblemonsafari.access.SafariAccess;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.credit.SafariCredit;
import dev.darcosse.common.cobblemonsafari.dex.SafariDex;
import dev.darcosse.common.cobblemonsafari.points.SafariPoints;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.park.SafariBorder;
import dev.darcosse.common.cobblemonsafari.reset.LiveReset;
import dev.darcosse.common.cobblemonsafari.reset.ResetMode;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.reset.SafariMapReset;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.session.SessionEndReason;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedBoard;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.stats.PlayerSafariStats;
import dev.darcosse.common.cobblemonsafari.stats.SafariStatsStore;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code /safari} command tree.
 *
 * <p>Players see a single command, {@code /safari}, telling them their time
 * left, banked time and points: everything else they do goes through the
 * gates, the NPCs (whose dialogues call {@link SafariDialogueFunctions}) and
 * the wall screens. The rest is for operators, who look after players.
 *
 * <p>Operator commands bypass entry requirements on purpose: they are the
 * recovery tool when a paid entry or ticket system is misconfigured.
 *
 * <pre>
 * /safari                                    own time left, banked time and Safari points
 * /safari info &lt;player&gt;                     (op) the same for another player
 * /safari enter [targets]                    (op) start a session paid with the targets' banked time
 * /safari enter &lt;targets&gt; &lt;seconds&gt;          (op) start a session of an explicit length, for testing
 * /safari exit &lt;targets&gt;                   (op) end a session
 * /safari time add|remove|set &lt;targets&gt; &lt;seconds&gt;   (op)
 * /safari credit add &lt;targets&gt; &lt;seconds&gt;     (op) bank time for players
 * /safari points add &lt;targets&gt; &lt;amount&gt;      (op) give Safari points
 * /safari shop                               (op) open the shop anywhere, to check its configuration
 * /safari stats &lt;player&gt;                    (op) lifetime hunting record
 * /safari top                                (op) best hunters
 * /safari today                              (op) special Pokémon of the rotation
 * /safari reload                             (op) reload the config
 * /safari reset                              (op) make a new map due (next start, or LIVE as soon as the Safari is empty)
 * /safari reset force                        (op) renew the map now, while the server runs
 * /safari rotate                             (op) start a new rotation now
 * </pre>
 */
public final class SafariCommands {

    /** Vanilla "gamemaster" level, the same as /tp and /give. */
    private static final int OP_LEVEL = 2;

    /** Source id stored on sessions started from this command. */
    private static final String COMMAND_SOURCE = CobblemonSafari.MOD_ID + ":command";

    private static final String ARG_TARGETS = "targets";
    private static final String ARG_SECONDS = "seconds";
    private static final String ARG_AMOUNT = "amount";
    private static final String ARG_TARGET = "player";
    private static final String ARG_HABITAT = "habitat";

    /**
     * Rows shown by /safari top. More rows make the command scroll the chat;
     * the full data stays available per player through /safari stats.
     */
    private static final int TOP_SIZE = 10;

    private SafariCommands() {
    }

    /** Registers the command tree. Called from each loader's command registration event. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("safari")
                .executes(SafariCommands::status)
                .then(Commands.literal("info")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .then(Commands.argument(ARG_TARGET, EntityArgument.player())
                                .executes(ctx -> info(ctx, EntityArgument.getPlayer(ctx, ARG_TARGET)))))
                .then(Commands.literal("enter")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(ctx -> enterWithCredit(ctx, List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument(ARG_TARGETS, EntityArgument.players())
                                .executes(ctx -> enterWithCredit(ctx, EntityArgument.getPlayers(ctx, ARG_TARGETS)))
                                .then(Commands.argument(ARG_SECONDS, IntegerArgumentType.integer(1))
                                        .executes(ctx -> enter(ctx, EntityArgument.getPlayers(ctx, ARG_TARGETS),
                                                Ticks.fromSeconds(IntegerArgumentType.getInteger(ctx, ARG_SECONDS)))))))
                .then(Commands.literal("exit")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .then(Commands.argument(ARG_TARGETS, EntityArgument.players())
                                .executes(ctx -> exit(ctx, EntityArgument.getPlayers(ctx, ARG_TARGETS)))))
                .then(Commands.literal("time")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .then(timeChange("add", TimeOperation.ADD))
                        .then(timeChange("remove", TimeOperation.REMOVE))
                        .then(timeChange("set", TimeOperation.SET)))
                .then(Commands.literal("credit")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .then(Commands.literal("add")
                                .then(Commands.argument(ARG_TARGETS, EntityArgument.players())
                                        .then(Commands.argument(ARG_SECONDS, IntegerArgumentType.integer(1))
                                                .executes(SafariCommands::addCredit)))))
                .then(Commands.literal("points")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .then(Commands.literal("add")
                                .then(Commands.argument(ARG_TARGETS, EntityArgument.players())
                                        .then(Commands.argument(ARG_AMOUNT, IntegerArgumentType.integer(1))
                                                .executes(SafariCommands::addPoints)))))
                .then(Commands.literal("shop")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(ctx -> SafariPlayerActions.openShop(ctx.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("stats")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .then(Commands.argument(ARG_TARGET, EntityArgument.player())
                                .executes(ctx -> showStats(ctx, EntityArgument.getPlayer(ctx, ARG_TARGET)))))
                // Every player: their own Safari Pokédex.
                .then(Commands.literal("dex")
                        .executes(SafariCommands::showDex)
                        .then(Commands.argument(ARG_HABITAT, StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        SafariDex.habitats().keySet().stream().map(biome -> biome.location().getPath()),
                                        builder))
                                .executes(ctx -> showDexHabitat(ctx, StringArgumentType.getString(ctx, ARG_HABITAT)))))
                .then(Commands.literal("top")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(SafariCommands::showTop))
                .then(Commands.literal("today")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(SafariCommands::showToday))
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(SafariCommands::reload))
                .then(Commands.literal("reset")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(SafariCommands::scheduleReset)
                        .then(Commands.literal("force")
                                .executes(SafariCommands::forceReset)))
                .then(Commands.literal("rotate")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(SafariCommands::rotate)));
    }

    /** What /safari time does to the remaining time. */
    private enum TimeOperation { ADD, REMOVE, SET }

    /** Builds one of the add/remove/set time sub-commands. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> timeChange(
            String name, TimeOperation operation) {
        // SET accepts 0 (immediate expiry); ADD and REMOVE with 0 would be a no-op typo.
        int min = operation == TimeOperation.SET ? 0 : 1;
        return Commands.literal(name)
                .requires(s -> s.hasPermission(OP_LEVEL))
                .then(Commands.argument(ARG_TARGETS, EntityArgument.players())
                        .then(Commands.argument(ARG_SECONDS, IntegerArgumentType.integer(min))
                                .executes(ctx -> changeTime(ctx, operation))));
    }

    /** /safari: the player's own time left, banked time and Safari points. */
    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        for (Component line : SafariPlayerActions.statusLines(player, true)) {
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    /** /safari info: the same for another player, for operators looking after them. */
    private static int info(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        for (Component line : SafariPlayerActions.statusLines(target, false)) {
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    /**
     * Starts sessions for the targets, each paid with their own banked time.
     *
     * <p>Entry requirements are bypassed (operator recovery tool) but the
     * time is not: a player with an empty bank stays out, so no operator, the
     * owner of a single-player world included, can skip tickets this way.
     * Except a player exempt by game mode, who enters with a frozen clock at
     * zero, exactly as through a gate.
     */
    private static int enterWithCredit(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        int entered = 0;
        for (ServerPlayer target : targets) {
            int before = SafariCredit.balance(target);
            boolean free = before <= 0 && SafariSessionManager.isClockExempt(target);
            if (before <= 0 && !free) {
                ctx.getSource().sendFailure(Component.translatable(key("command.enter.no_credit"),
                        target.getDisplayName()));
                continue;
            }
            EntryResult result = SafariCredit.enter(target, true);
            if (result.success()) {
                entered++;
                int spent = before - SafariCredit.balance(target);
                if (free) {
                    ctx.getSource().sendSuccess(() -> Component.translatable(key("command.enter.exempt"),
                            target.getDisplayName()), true);
                } else {
                    ctx.getSource().sendSuccess(() -> Component.translatable(key("command.enter.success"),
                            target.getDisplayName(), Ticks.format(spent)), true);
                }
            } else if (result.reason() != null) {
                ctx.getSource().sendFailure(Component.translatable(key("command.enter.failed"),
                        target.getDisplayName(), result.reason()));
            }
        }
        return entered;
    }

    /** Starts sessions of an explicit length for the targets, bypassing requirements and the bank. */
    private static int enter(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets,
                             int durationTicks) {
        int entered = 0;
        for (ServerPlayer target : targets) {
            EntryResult result = SafariAccess.requestEntry(target, durationTicks, COMMAND_SOURCE, true);
            if (result.success()) {
                entered++;
                ctx.getSource().sendSuccess(() -> Component.translatable(key("command.enter.success"),
                        target.getDisplayName(), Ticks.format(durationTicks)), true);
            } else if (result.reason() != null) {
                ctx.getSource().sendFailure(Component.translatable(key("command.enter.failed"),
                        target.getDisplayName(), result.reason()));
            }
        }
        return entered;
    }

    /** Ends the targets' sessions and sends them out. */
    private static int exit(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        int ended = 0;
        for (ServerPlayer target : targets) {
            if (SafariSessionManager.end(target, SessionEndReason.ADMIN)) {
                ended++;
                ctx.getSource().sendSuccess(() -> Component.translatable(key("command.exit.success"),
                        target.getDisplayName()), true);
            } else {
                ctx.getSource().sendFailure(Component.translatable(key("command.no_session"),
                        target.getDisplayName()));
            }
        }
        return ended;
    }

    /** Gives Safari points to every target, up to the configured cap. */
    private static int addPoints(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int amount = IntegerArgumentType.getInteger(ctx, ARG_AMOUNT);
        int changed = 0;
        for (ServerPlayer target : EntityArgument.getPlayers(ctx, ARG_TARGETS)) {
            SafariPoints.add(target, amount);
            int balance = SafariPoints.balance(target);
            changed++;
            ctx.getSource().sendSuccess(() -> Component.translatable(key("command.points.changed"),
                    target.getDisplayName(), SafariPointsIcon.amount(balance)), true);
        }
        return changed;
    }

    /** Banks time for every target, as if they had used tickets. */
    private static int addCredit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int ticks = Ticks.fromSeconds(IntegerArgumentType.getInteger(ctx, ARG_SECONDS));
        int changed = 0;
        for (ServerPlayer target : EntityArgument.getPlayers(ctx, ARG_TARGETS)) {
            SafariCredit.add(target, ticks);
            int banked = SafariCredit.balance(target);
            changed++;
            ctx.getSource().sendSuccess(() -> Component.translatable(key("command.credit.changed"),
                    target.getDisplayName(), Ticks.format(banked)), true);
        }
        return changed;
    }

    /** Applies a time change to every target and reports the new remaining time. */
    private static int changeTime(CommandContext<CommandSourceStack> ctx, TimeOperation operation)
            throws CommandSyntaxException {
        int ticks = Ticks.fromSeconds(IntegerArgumentType.getInteger(ctx, ARG_SECONDS));
        int changed = 0;
        for (ServerPlayer target : EntityArgument.getPlayers(ctx, ARG_TARGETS)) {
            int remaining = switch (operation) {
                case ADD -> SafariSessionManager.addTime(target, ticks);
                case REMOVE -> SafariSessionManager.addTime(target, -ticks);
                case SET -> SafariSessionManager.setTime(target, ticks);
            };
            if (remaining < 0) {
                ctx.getSource().sendFailure(Component.translatable(key("command.no_session"),
                        target.getDisplayName()));
                continue;
            }
            changed++;
            ctx.getSource().sendSuccess(() -> Component.translatable(key("command.time.changed"),
                    target.getDisplayName(), Ticks.format(remaining)), true);
        }
        return changed;
    }

    /** Prints a player's lifetime Safari record. */
    private static int showStats(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        PlayerSafariStats stats = SafariStatsStore.get(ctx.getSource().getServer()).find(target.getUUID());
        if (stats == null || stats.sessions() == 0 && stats.totalCatches() == 0) {
            ctx.getSource().sendFailure(Component.translatable(key("command.stats.none"), target.getDisplayName()));
            return 0;
        }
        // Ticks hunted can exceed an int over years of play; format via seconds.
        int seconds = (int) Math.min(Integer.MAX_VALUE, stats.ticksHunted() / Ticks.PER_SECOND);
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.stats.header"), target.getDisplayName())
                .withStyle(ChatFormatting.GOLD), false);
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.stats.games"),
                stats.sessions(), Ticks.format(Ticks.fromSeconds(seconds))), false);
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.stats.catches"),
                stats.totalCatches(), stats.bestSessionCatches()), false);
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.stats.species"),
                stats.speciesCaught().size(), stats.shinyCatches()), false);
        return stats.totalCatches();
    }

    /** /safari dex: the player's Safari Pokédex, habitat by habitat. */
    private static int showDex(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        Map<ResourceKey<Biome>, List<Species>> habitats = SafariDex.habitats();
        if (habitats.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(key("command.dex.empty")));
            return 0;
        }
        Set<String> caught = SafariDex.caughtBy(server, player);
        int caughtCount = SafariDex.caughtCount(server, player);
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.dex.header"), caughtCount,
                SafariDex.total()).withStyle(ChatFormatting.GOLD), false);
        for (Map.Entry<ResourceKey<Biome>, List<Species>> habitat : habitats.entrySet()) {
            int done = countCaught(habitat.getValue(), caught);
            int total = habitat.getValue().size();
            ctx.getSource().sendSuccess(() -> Component.translatable(key("command.dex.habitat_line"),
                    habitatName(habitat.getKey()), done, total)
                    .withStyle(done == total ? ChatFormatting.GREEN : ChatFormatting.WHITE), false);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.dex.hint"))
                .withStyle(ChatFormatting.GRAY), false);
        return caughtCount;
    }

    /** /safari dex &lt;habitat&gt;: the species of one habitat, caught and missing. */
    private static int showDexHabitat(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        Map<ResourceKey<Biome>, List<Species>> habitats = SafariDex.habitats();
        if (habitats.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(key("command.dex.empty")));
            return 0;
        }
        Map.Entry<ResourceKey<Biome>, List<Species>> habitat = null;
        for (Map.Entry<ResourceKey<Biome>, List<Species>> candidate : habitats.entrySet()) {
            if (candidate.getKey().location().getPath().equals(name)) {
                habitat = candidate;
            }
        }
        if (habitat == null) {
            ctx.getSource().sendFailure(Component.translatable(key("command.dex.unknown_habitat"), name));
            return 0;
        }
        Set<String> caught = SafariDex.caughtBy(server, player);
        MutableComponent caughtNames = Component.empty();
        MutableComponent missingNames = Component.empty();
        int done = 0;
        for (Species species : habitat.getValue()) {
            boolean has = caught.contains(species.getResourceIdentifier().toString());
            MutableComponent list = has ? caughtNames : missingNames;
            if (!list.getSiblings().isEmpty()) {
                list.append(", ");
            }
            list.append(species.getTranslatedName());
            if (has) {
                done++;
            }
        }
        int total = habitat.getValue().size();
        int doneCount = done;
        ResourceKey<Biome> biome = habitat.getKey();
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.dex.habitat_header"),
                habitatName(biome), doneCount, total).withStyle(ChatFormatting.GOLD), false);
        if (doneCount > 0) {
            ctx.getSource().sendSuccess(() -> Component.translatable(key("command.dex.caught"), caughtNames)
                    .withStyle(ChatFormatting.GREEN), false);
        }
        if (doneCount < total) {
            ctx.getSource().sendSuccess(() -> Component.translatable(key("command.dex.missing"), missingNames)
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return doneCount;
    }

    /** How many of the species the caught set holds. */
    private static int countCaught(List<Species> species, Set<String> caught) {
        int count = 0;
        for (Species one : species) {
            if (caught.contains(one.getResourceIdentifier().toString())) {
                count++;
            }
        }
        return count;
    }

    /** A Safari biome's translated name, as the F3 screen and the wall screens show it. */
    private static Component habitatName(ResourceKey<Biome> biome) {
        return Component.translatable("biome." + biome.location().getNamespace() + "." + biome.location().getPath());
    }

    /** Prints the same list as the wall screens' FEATURED page. */
    private static int showToday(CommandContext<CommandSourceStack> ctx) {
        for (Component line : FeaturedBoard.lines(ctx.getSource().getServer())) {
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return FeaturedRotation.today(ctx.getSource().getServer()).size();
    }

    /** Prints the best hunters by total catches. */
    private static int showTop(CommandContext<CommandSourceStack> ctx) {
        List<PlayerSafariStats> top = SafariStatsStore.get(ctx.getSource().getServer()).top(TOP_SIZE);
        if (top.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(key("command.top.empty")));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.top.header"))
                .withStyle(ChatFormatting.GOLD), false);
        for (int i = 0; i < top.size(); i++) {
            PlayerSafariStats stats = top.get(i);
            int rank = i + 1;
            ctx.getSource().sendSuccess(() -> Component.translatable(key("command.top.row"),
                    rank, stats.name(), stats.totalCatches(), stats.bestSessionCatches()), false);
        }
        return top.size();
    }

    /**
     * Marks the map as due for a reset: at the next server start in START
     * mode; in LIVE mode as soon as nobody is inside, or after the wait.
     */
    private static int scheduleReset(CommandContext<CommandSourceStack> ctx) {
        if (!SafariMapReset.scheduleReset(ctx.getSource().getServer())) {
            ctx.getSource().sendFailure(Component.translatable(key("command.reset.failed")));
            return 0;
        }
        SafariConfig.Reset config = SafariConfigManager.get().reset;
        Component done = config.mode == ResetMode.LIVE && config.enabled
                ? Component.translatable(key("command.reset.scheduled_live"), config.maxWaitMinutes)
                : Component.translatable(key("command.reset.scheduled"));
        ctx.getSource().sendSuccess(() -> done, true);
        return 1;
    }

    /**
     * Resets the map now, while the server runs, whatever the mode: at once
     * when nobody is inside, else after the countdown for those inside.
     */
    private static int forceReset(CommandContext<CommandSourceStack> ctx) {
        int inside = LiveReset.force(ctx.getSource().getServer());
        if (inside < 0) {
            ctx.getSource().sendFailure(Component.translatable(key("command.reset.busy")));
            return 0;
        }
        int countdown = SafariConfigManager.get().reset.countdownSeconds;
        Component done = inside == 0 || countdown == 0
                ? Component.translatable(key("command.reset.forced_now"))
                : Component.translatable(key("command.reset.forced"), inside, Ticks.format(Ticks.fromSeconds(countdown)));
        ctx.getSource().sendSuccess(() -> done, true);
        return 1;
    }

    /** Reloads the config, re-applies the border and re-syncs the rules to every client. */
    private static int reload(CommandContext<CommandSourceStack> ctx) {
        SafariConfigManager.reload();
        SafariBorder.apply(ctx.getSource().getServer());
        // Slots, categories and exclusions may have changed: redraw today's specials.
        FeaturedRotation.invalidate();
        // Not prepare(): that one may build the entrance on the spot, generating
        // chunks on the server thread while players are online.
        RestHouses.refresh(ctx.getSource().getServer());
        SafariNetwork.broadcastRules(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.reload.success")), true);
        return 1;
    }

    /**
     * /safari rotate: starts a new rotation now (specials, bounties, shop
     * Pokémon and purchase limits), ending at the next scheduled change.
     */
    private static int rotate(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        FeaturedRotation.forceNext(server);
        // Draws now rather than at the next check, so the log and the chat
        // name the new specials together.
        int specials = FeaturedRotation.today(server).size();
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.rotate.success"), specials), true);
        return 1;
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
