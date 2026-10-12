package dev.darcosse.common.cobblemonsafari.command;

import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.access.EntryResult;
import dev.darcosse.common.cobblemonsafari.access.SafariAccess;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.credit.SafariCredit;
import dev.darcosse.common.cobblemonsafari.gate.SafariGate;
import dev.darcosse.common.cobblemonsafari.points.SafariPoints;
import dev.darcosse.common.cobblemonsafari.bounty.SafariBounties;
import dev.darcosse.common.cobblemonsafari.shop.SafariShop;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.park.SafariBorder;
import dev.darcosse.common.cobblemonsafari.reset.SafariMapReset;
import dev.darcosse.common.cobblemonsafari.session.SafariSession;
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
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;

/**
 * The {@code /safari} command tree.
 *
 * <p>Operator commands bypass entry requirements on purpose: they are the
 * recovery tool when a paid entry or ticket system is misconfigured.
 *
 * <pre>
 * /safari enter [targets]                    (op) start a session paid with the targets' banked time
 * /safari enter &lt;targets&gt; &lt;seconds&gt;          (op) start a session of an explicit length, for testing
 * /safari exit &lt;targets&gt;                   (op) end a session
 * /safari start                              enter, paying with banked time (config: ticket.allowStartCommand)
 * /safari gate                               enter through a nearby Safari Gate (used by the gate warden)
 * /safari shop                               open the warden's shop, next to a Safari Gate (used by the warden)
 * /safari bounties                           today's missions and own progress
 * /safari points                             own Safari points
 * /safari points add &lt;targets&gt; &lt;amount&gt;      (op) give Safari points
 * /safari credit                             own banked time
 * /safari credit add &lt;targets&gt; &lt;seconds&gt;     (op) bank time for players
 * /safari leave                              leave early
 * /safari time                               own remaining time
 * /safari time add|remove|set &lt;targets&gt; &lt;seconds&gt;   (op)
 * /safari reload                             (op) reload the config
 * /safari reset                              (op) regenerate the map at the next server start
 * /safari stats [player]                     lifetime hunting record
 * /safari top                                best hunters
 * /safari today                              special Pokémon of the day
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
                .then(Commands.literal("start")
                        .executes(SafariCommands::start))
                .then(Commands.literal("gate")
                        .executes(SafariCommands::enterThroughGate))
                .then(Commands.literal("bounties")
                        .executes(SafariCommands::showBounties))
                .then(Commands.literal("shop")
                        .executes(SafariCommands::openShop))
                .then(Commands.literal("points")
                        .executes(SafariCommands::queryOwnPoints)
                        .then(Commands.literal("add")
                                .requires(s -> s.hasPermission(OP_LEVEL))
                                .then(Commands.argument(ARG_TARGETS, EntityArgument.players())
                                        .then(Commands.argument(ARG_AMOUNT, IntegerArgumentType.integer(1))
                                                .executes(SafariCommands::addPoints)))))
                .then(Commands.literal("credit")
                        .executes(SafariCommands::queryOwnCredit)
                        .then(Commands.literal("add")
                                .requires(s -> s.hasPermission(OP_LEVEL))
                                .then(Commands.argument(ARG_TARGETS, EntityArgument.players())
                                        .then(Commands.argument(ARG_SECONDS, IntegerArgumentType.integer(1))
                                                .executes(SafariCommands::addCredit)))))
                .then(Commands.literal("leave")
                        .executes(SafariCommands::leave))
                .then(Commands.literal("time")
                        .executes(SafariCommands::queryOwnTime)
                        .then(timeChange("add", TimeOperation.ADD))
                        .then(timeChange("remove", TimeOperation.REMOVE))
                        .then(timeChange("set", TimeOperation.SET)))
                .then(Commands.literal("stats")
                        .executes(ctx -> showStats(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument(ARG_TARGET, EntityArgument.player())
                                .executes(ctx -> showStats(ctx, EntityArgument.getPlayer(ctx, ARG_TARGET)))))
                .then(Commands.literal("top")
                        .executes(SafariCommands::showTop))
                .then(Commands.literal("today")
                        .executes(SafariCommands::showToday))
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(SafariCommands::reload))
                .then(Commands.literal("reset")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(SafariCommands::scheduleReset))
                .then(Commands.literal("rotate")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(SafariCommands::rotate)));
    }

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

    /**
     * Starts sessions for the targets, each paid with their own banked time.
     *
     * <p>Entry requirements are bypassed (operator recovery tool) but the
     * time is not: a player with an empty bank stays out. Before, this granted
     * the configured default for free, which let any operator, including the
     * owner of a single-player world, skip tickets entirely.
     */
    private static int enterWithCredit(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        int entered = 0;
        for (ServerPlayer target : targets) {
            int before = SafariCredit.balance(target);
            if (before <= 0) {
                ctx.getSource().sendFailure(Component.translatable(key("command.enter.no_credit"),
                        target.getDisplayName()));
                continue;
            }
            EntryResult result = SafariCredit.enter(target, true);
            if (result.success()) {
                entered++;
                int spent = before - SafariCredit.balance(target);
                ctx.getSource().sendSuccess(() -> Component.translatable(key("command.enter.success"),
                        target.getDisplayName(), Ticks.format(spent)), true);
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

    /** Lets a player end their own session early. */
    private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!SafariSessionManager.end(player, SessionEndReason.VOLUNTARY)) {
            ctx.getSource().sendFailure(Component.translatable(key("command.self_no_session")));
            return 0;
        }
        return 1;
    }

    /** Enters the Safari with banked time, if the server allows the command. */
    private static int start(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!SafariConfigManager.get().ticket.allowStartCommand) {
            ctx.getSource().sendFailure(Component.translatable(key("command.start.disabled")));
            return 0;
        }
        EntryResult result = SafariCredit.enter(player);
        if (!result.success()) {
            if (result.reason() != null) {
                ctx.getSource().sendFailure(result.reason());
            }
            return 0;
        }
        return 1;
    }

    /** Enters through the Safari Gate the player stands next to, paying with banked time. */
    private static int enterThroughGate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        EntryResult result = SafariGate.enterNearby(player);
        if (!result.success()) {
            if (result.reason() != null) {
                ctx.getSource().sendFailure(result.reason());
            }
            return 0;
        }
        return 1;
    }

    /**
     * Opens the shop. Only where a shopkeeper stands (the warden at a Safari
     * Gate, the clerk in a rest house); operators can open it anywhere, to
     * check the configuration. See {@link SafariShop#canShopHere}.
     */
    private static int openShop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!SafariShop.canShopHere(player)) {
            ctx.getSource().sendFailure(Component.translatable(key("command.shop.too_far")));
            return 0;
        }
        return SafariShop.open(player) ? 1 : 0;
    }

    /** Lists today's missions with the player's progress. */
    private static int showBounties(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<Component> lines = SafariBounties.lines(player);
        for (Component line : lines) {
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return lines.size();
    }

    /** Tells a player how many Safari points they have. */
    private static int queryOwnPoints(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int points = SafariPoints.balance(player);
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.points.query"), SafariPointsIcon.amount(points)), false);
        return points;
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

    /** Tells a player how much Safari time they have banked. */
    private static int queryOwnCredit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int banked = SafariCredit.balance(player);
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.credit.query"),
                Ticks.format(banked)), false);
        return Ticks.toSecondsCeil(banked);
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

    /** Tells a player how much time they have left. */
    private static int queryOwnTime(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SafariSession session = SafariSessionManager.getSession(player);
        if (session == null) {
            ctx.getSource().sendFailure(Component.translatable(key("command.self_no_session")));
            return 0;
        }
        int remaining = session.remainingTicks();
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.time.query"),
                Ticks.format(remaining)), false);
        return Ticks.toSecondsCeil(remaining);
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

    /** Prints the same list as the Safari Board. */
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

    /** Schedules a map reset for the next server start; the running map is untouched. */
    private static int scheduleReset(CommandContext<CommandSourceStack> ctx) {
        if (!SafariMapReset.scheduleReset(ctx.getSource().getServer())) {
            ctx.getSource().sendFailure(Component.translatable(key("command.reset.failed")));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(key("command.reset.scheduled")), true);
        return 1;
    }

    /** Reloads the config, re-applies the border and re-syncs the rules to every client. */
    private static int reload(CommandContext<CommandSourceStack> ctx) {
        SafariConfigManager.reload();
        SafariBorder.apply(ctx.getSource().getServer());
        // Slots, categories and exclusions may have changed: redraw today's specials.
        FeaturedRotation.invalidate();
        RestHouses.ensureBuilt(ctx.getSource().getServer());
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
