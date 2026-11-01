package dev.darcosse.common.cobblemonsafari.session;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.dex.SafariDex;
import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import dev.darcosse.common.cobblemonsafari.stats.PlayerSafariStats;
import dev.darcosse.common.cobblemonsafari.stats.SafariStatsStore;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the end-of-game summary shown when a session ends, like the
 * "Your Safari Game is over" screen of the games: what was caught, how long
 * the hunt lasted, and how many loaned balls were handed back; then, each
 * only when there is something to say, the Safari points earned, the
 * missions completed, the species new to the player (with their Safari
 * Pokédex), a beaten record and the Safari advancements unlocked.
 */
public final class SafariRecap {

    /**
     * Species listed by name before the rest is summarised as "and N more".
     * Higher values make a 30-catch recap scroll the chat away; lower ones hide
     * the interesting part of short hunts.
     */
    private static final int MAX_LISTED_CATCHES = 8;

    /** Same, for the advancements unlocked during the hunt. */
    private static final int MAX_LISTED_ADVANCEMENTS = 4;

    /** Path prefix of the Safari tab's advancements, in the mod's namespace. */
    private static final String ADVANCEMENT_FOLDER = "safari/";

    private SafariRecap() {
    }

    /**
     * Composes the recap message. Must run before the session listeners:
     * the record line compares the hunt with the best one the statistics
     * hold, which the statistics listener raises at the end.
     *
     * @param returnedBalls loaned balls taken back from the inventory at the exit
     */
    public static Component build(ServerPlayer player, SafariSession session, int returnedBalls) {
        List<CatchRecord> catches = session.catches();
        MutableComponent message = Component.translatable(key("recap.header"),
                Ticks.format(session.ticksUsed())).withStyle(ChatFormatting.GOLD);

        if (catches.isEmpty()) {
            message.append("\n").append(Component.translatable(key("recap.none")).withStyle(ChatFormatting.GRAY));
        } else {
            message.append("\n").append(Component.translatable(key("recap.caught"), catches.size(),
                    listed(catches.stream().map(CatchRecord::displayName).toList(), MAX_LISTED_CATCHES, "recap.more"))
                    .withStyle(ChatFormatting.WHITE));
        }
        if (session.ballsLoaned() > 0) {
            message.append("\n").append(Component.translatable(key("recap.balls_returned"), returnedBalls)
                    .withStyle(ChatFormatting.GRAY));
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return message;
        }
        if (session.pointsEarned() > 0) {
            message.append("\n").append(Component.translatable(key("recap.points"),
                    SafariPointsIcon.amount(session.pointsEarned())).withStyle(ChatFormatting.YELLOW));
        }
        if (session.missionsCompleted() > 0) {
            message.append("\n").append(Component.translatable(key("recap.missions"), session.missionsCompleted())
                    .withStyle(ChatFormatting.GREEN));
        }
        if (session.newSpecies() > 0) {
            int dexTotal = SafariDex.total();
            Component line = dexTotal > 0
                    ? Component.translatable(key("recap.new_species"), session.newSpecies(),
                    SafariDex.caughtCount(server, player), dexTotal)
                    // The Pokédex is not built yet (a hunt right after a start): the count alone.
                    : Component.translatable(key("recap.new_species_plain"), session.newSpecies());
            message.append("\n").append(line.copy().withStyle(ChatFormatting.AQUA));
        }
        PlayerSafariStats record = SafariStatsStore.get(server).find(player.getUUID());
        // Not on the first hunts: beating a best of 0 is no record worth a line.
        if (record != null && record.bestSessionCatches() > 0 && catches.size() > record.bestSessionCatches()) {
            message.append("\n").append(Component.translatable(key("recap.record"), catches.size(),
                    record.bestSessionCatches()).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        List<Component> unlocked = advancementsSince(server, player, session.startedAtMillis());
        if (!unlocked.isEmpty()) {
            message.append("\n").append(Component.translatable(key("recap.advancements"),
                    listed(unlocked, MAX_LISTED_ADVANCEMENTS, "recap.more_advancements")).withStyle(ChatFormatting.GOLD));
        }
        return message;
    }

    /**
     * "A, B, C and 2 more": the first {@code max} names, the rest summarised.
     *
     * @param moreKey key of the "and N more" text; one per kind of thing,
     *                since some languages count Pokémon and advancements with different words
     */
    private static MutableComponent listed(List<Component> names, int max, String moreKey) {
        MutableComponent result = Component.empty();
        int shown = Math.min(max, names.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append(names.get(i));
        }
        if (names.size() > shown) {
            result.append(" ").append(Component.translatable(key(moreKey), names.size() - shown));
        }
        return result;
    }

    /**
     * Names of the Safari advancements the player completed since the given
     * time: those whose last criterion was obtained after it. Empty when the
     * start is unknown (a session saved before 1.4.0). Cost: one pass over
     * the server's advancements (about a thousand with Cobblemon), once per
     * hunt.
     */
    private static List<Component> advancementsSince(MinecraftServer server, ServerPlayer player, long sinceMillis) {
        List<Component> names = new ArrayList<>();
        if (sinceMillis <= 0) {
            return names;
        }
        Instant since = Instant.ofEpochMilli(sinceMillis);
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (!holder.id().getNamespace().equals(CobblemonSafari.MOD_ID)
                    || !holder.id().getPath().startsWith(ADVANCEMENT_FOLDER)
                    || holder.value().display().isEmpty()) {
                continue;
            }
            AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
            if (!progress.isDone()) {
                continue;
            }
            Instant completed = completedAt(progress);
            if (completed != null && completed.isAfter(since)) {
                names.add(Advancement.name(holder));
            }
        }
        return names;
    }

    /**
     * When an advancement was completed: the latest time one of its criteria
     * was obtained, or null when none says. Saved progress keeps whole
     * seconds, so an advancement earned in the very second the hunt began
     * may be left out after a restart.
     */
    private static Instant completedAt(AdvancementProgress progress) {
        Instant latest = null;
        for (String criterion : progress.getCompletedCriteria()) {
            CriterionProgress done = progress.getCriterion(criterion);
            Instant obtained = done == null ? null : done.getObtained();
            if (obtained != null && (latest == null || obtained.isAfter(latest))) {
                latest = obtained;
            }
        }
        return latest;
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
