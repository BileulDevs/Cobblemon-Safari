package dev.darcosse.common.cobblemonsafari.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.access.EntryResult;
import dev.darcosse.common.cobblemonsafari.access.SafariAccess;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.park.SafariBorder;
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
 * /safari enter [targets] [seconds]          (op) start a session
 * /safari exit &lt;targets&gt;                   (op) end a session
 * /safari leave                              leave early
 * /safari time                               own remaining time
 * /safari time add|remove|set &lt;targets&gt; &lt;seconds&gt;   (op)
 * /safari reload                             (op) reload the config
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
                        .executes(ctx -> enter(ctx, List.of(ctx.getSource().getPlayerOrException()),
                                SafariConfigManager.defaultDurationTicks()))
                        .then(Commands.argument(ARG_TARGETS, EntityArgument.players())
                                .executes(ctx -> enter(ctx, EntityArgument.getPlayers(ctx, ARG_TARGETS),
                                        SafariConfigManager.defaultDurationTicks()))
                                .then(Commands.argument(ARG_SECONDS, IntegerArgumentType.integer(1))
                                        .executes(ctx -> enter(ctx, EntityArgument.getPlayers(ctx, ARG_TARGETS),
                                                Ticks.fromSeconds(IntegerArgumentType.getInteger(ctx, ARG_SECONDS)))))))
                .then(Commands.literal("exit")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .then(Commands.argument(ARG_TARGETS, EntityArgument.players())
                                .executes(ctx -> exit(ctx, EntityArgument.getPlayers(ctx, ARG_TARGETS)))))
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
                        .executes(SafariCommands::reload)));
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

    /** Starts sessions for the targets, bypassing entry requirements. */
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

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
