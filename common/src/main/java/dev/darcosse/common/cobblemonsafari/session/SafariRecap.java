package dev.darcosse.common.cobblemonsafari.session;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Builds the end-of-game summary shown when a session ends, like the
 * "Your Safari Game is over" screen of the games: what was caught, how long the
 * hunt lasted, and how many loaned balls were handed back.
 */
public final class SafariRecap {

    /**
     * Species listed by name before the rest is summarised as "and N more".
     * Higher values make a 30-catch recap scroll the chat away; lower ones hide
     * the interesting part of short hunts.
     */
    private static final int MAX_LISTED_CATCHES = 8;

    private SafariRecap() {
    }

    /**
     * Composes the recap message.
     *
     * @param returnedBalls loaned balls taken back from the inventory at the exit
     */
    public static Component build(SafariSession session, int returnedBalls) {
        List<CatchRecord> catches = session.catches();
        MutableComponent message = Component.translatable(key("recap.header"),
                Ticks.format(session.ticksUsed())).withStyle(ChatFormatting.GOLD);

        if (catches.isEmpty()) {
            message.append("\n").append(Component.translatable(key("recap.none")).withStyle(ChatFormatting.GRAY));
        } else {
            MutableComponent names = Component.empty();
            int listed = Math.min(MAX_LISTED_CATCHES, catches.size());
            for (int i = 0; i < listed; i++) {
                if (i > 0) {
                    names.append(", ");
                }
                names.append(catches.get(i).displayName());
            }
            if (catches.size() > listed) {
                names.append(" ").append(Component.translatable(key("recap.more"), catches.size() - listed));
            }
            message.append("\n").append(Component.translatable(key("recap.caught"), catches.size(), names)
                    .withStyle(ChatFormatting.WHITE));
        }
        if (session.ballsLoaned() > 0) {
            message.append("\n").append(Component.translatable(key("recap.balls_returned"), returnedBalls)
                    .withStyle(ChatFormatting.GRAY));
        }
        return message;
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
