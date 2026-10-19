package dev.darcosse.common.cobblemonsafari.command;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.access.EntryResult;
import dev.darcosse.common.cobblemonsafari.bounty.SafariBounties;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.credit.SafariCredit;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.gate.SafariGate;
import dev.darcosse.common.cobblemonsafari.points.SafariPoints;
import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import dev.darcosse.common.cobblemonsafari.session.SafariSession;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.shop.SafariShop;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedBoard;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * What a player can ask of the Safari: their status, and the actions the
 * NPCs offer (shop, missions, accepted balls, entering at a gate, leaving). The status is
 * the {@code /safari} command; the actions are reached only through the
 * NPCs' dialogues ({@link SafariDialogueFunctions}), so they are no longer
 * commands a player sees.
 *
 * <p>Server side. Each action answers the player itself, failures in red,
 * since there is no command source to report to.
 */
public final class SafariPlayerActions {

    private SafariPlayerActions() {
    }

    /**
     * The status lines: a header, the time left in the running hunt (or
     * that there is none), the banked time and the Safari points.
     *
     * @param self whether the reader is the player (header "Your Safari")
     *             or an operator looking at them (header naming them)
     */
    public static List<Component> statusLines(ServerPlayer player, boolean self) {
        List<Component> lines = new ArrayList<>();
        lines.add((self
                ? Component.translatable(key("command.status.header_self"))
                : Component.translatable(key("command.status.header_other"), player.getDisplayName()))
                .withStyle(ChatFormatting.GOLD));
        SafariSession session = SafariSessionManager.getSession(player);
        lines.add(session != null
                ? Component.translatable(key("command.status.time"), Ticks.format(session.remainingTicks()))
                : Component.translatable(key("command.status.no_session")).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(key("command.status.credit"), Ticks.format(SafariCredit.balance(player))));
        lines.add(Component.translatable(key("command.status.points"),
                SafariPointsIcon.amount(SafariPoints.balance(player))));
        return lines;
    }

    /** Sends the player their own status; see {@link #statusLines}. */
    public static boolean sendStatus(ServerPlayer player) {
        for (Component line : statusLines(player, true)) {
            player.sendSystemMessage(line);
        }
        return true;
    }

    /**
     * Opens the shop. Only where a shopkeeper stands (the warden at a Safari
     * Gate, the clerk in a rest house); see {@link SafariShop#canShopHere}.
     */
    public static boolean openShop(ServerPlayer player) {
        if (!SafariShop.canShopHere(player)) {
            fail(player, Component.translatable(key("command.shop.too_far")));
            return false;
        }
        return SafariShop.open(player);
    }

    /** Lists the missions of the rotation with the player's progress, in chat. */
    public static boolean showBounties(ServerPlayer player) {
        for (Component line : SafariBounties.lines(player)) {
            player.sendSystemMessage(line);
        }
        return true;
    }

    /** Lists the specials of the rotation, in chat, as the wall screens show them. */
    public static boolean showToday(ServerPlayer player) {
        for (Component line : FeaturedBoard.lines(player.getServer())) {
            player.sendSystemMessage(line);
        }
        return true;
    }

    /**
     * Lists in chat the Poké Balls that work inside the Safari, by their item
     * names (in the reader's language): the configured {@code allowedBalls},
     * plus the lent ball when the loan is on. Asked of the gate warden before
     * going in, or of a rest house clerk.
     */
    public static boolean showAllowedBalls(ServerPlayer player) {
        MutableComponent names = Component.empty();
        int count = 0;
        for (ResourceLocation id : SafariConfigManager.allowedBalls()) {
            if (count++ > 0) {
                names.append(", ");
            }
            // Ball ids and item ids coincide for every Cobblemon ball; an id
            // of no known item (a typo, a removed mod) still shows, as is.
            names.append(BuiltInRegistries.ITEM.getOptional(id)
                    .<Component>map(item -> item.getDescription())
                    .orElse(Component.literal(id.toString()))
                    .copy().withStyle(ChatFormatting.WHITE));
        }
        player.sendSystemMessage(count == 0
                ? Component.translatable(key("message.allowed_balls_none")).withStyle(ChatFormatting.RED)
                : Component.translatable(key("message.allowed_balls"), names).withStyle(ChatFormatting.YELLOW));
        return true;
    }

    /** Enters through the Safari Gate the player stands next to, paying with banked time. */
    public static boolean enterThroughGate(ServerPlayer player) {
        EntryResult result = SafariGate.enterNearby(player);
        if (!result.success() && result.reason() != null) {
            fail(player, result.reason());
        }
        return result.success();
    }

    /**
     * Ends the player's hunt early, or simply walks them out.
     *
     * <p>A player can stand in the Safari without a session: a creative or
     * spectator player exempt from the rules, typically an operator testing
     * on a server. They are sent to the world spawn, the same place a player
     * without a session is sent when logging in inside.
     *
     * <p>The leave itself happens on the next tick
     * ({@link SafariSessionManager#requestLeave}): run from the clerk's
     * dialogue, ending and teleporting right away ended the session but left
     * the player inside.
     */
    public static boolean leave(ServerPlayer player) {
        if (SafariSessionManager.getSession(player) != null || SafariDimensions.isSafari(player.level())) {
            SafariSessionManager.requestLeave(player);
            return true;
        }
        fail(player, Component.translatable(key("command.self_no_session")));
        return false;
    }

    /** Tells the player why something was refused, in red like a failed command. */
    private static void fail(ServerPlayer player, Component reason) {
        player.sendSystemMessage(reason.copy().withStyle(ChatFormatting.RED));
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
