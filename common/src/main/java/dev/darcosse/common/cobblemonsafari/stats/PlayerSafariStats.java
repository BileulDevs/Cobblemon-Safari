package dev.darcosse.common.cobblemonsafari.stats;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lifetime Safari record of one player: the "hunting log" the games never had,
 * and the base for future records, titles and rewards.
 *
 * <p>Mutated only by {@link SafariStatsListener}, which marks the store dirty.
 */
public final class PlayerSafariStats {

    /** Last known name, so leaderboards can show players who are offline. */
    private String name = "";
    private int sessions;
    private int totalCatches;
    private int shinyCatches;
    private int bestSessionCatches;
    private long ticksHunted;
    private final Set<String> speciesCaught = new LinkedHashSet<>();

    /** Last known player name. */
    public String name() {
        return name;
    }

    /** Completed Safari games, whatever the way they ended. */
    public int sessions() {
        return sessions;
    }

    /** Pokémon caught inside the Safari, all sessions together. */
    public int totalCatches() {
        return totalCatches;
    }

    /** Shiny Pokémon caught inside the Safari. */
    public int shinyCatches() {
        return shinyCatches;
    }

    /** Most Pokémon caught in a single session. */
    public int bestSessionCatches() {
        return bestSessionCatches;
    }

    /** Time actually spent hunting, in ticks. */
    public long ticksHunted() {
        return ticksHunted;
    }

    /** Distinct species ever caught inside the Safari. Read-only view. */
    public Set<String> speciesCaught() {
        return Collections.unmodifiableSet(speciesCaught);
    }

    /** Refreshes the last known name, so a renamed player shows up under the new one. */
    void setName(String name) {
        this.name = name;
    }

    /** Counts one catch and remembers its species. */
    void addCatch(String speciesId, boolean shiny) {
        totalCatches++;
        if (shiny) {
            shinyCatches++;
        }
        speciesCaught.add(speciesId);
    }

    /** Closes one game: counts it, keeps the best score and adds the time hunted. */
    void addFinishedSession(int catches, int ticksUsed) {
        sessions++;
        bestSessionCatches = Math.max(bestSessionCatches, catches);
        ticksHunted += Math.max(0, ticksUsed);
    }

    /** Serialises into a compound. Keys are the save format: renaming one resets that statistic. */
    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putInt("sessions", sessions);
        tag.putInt("catches", totalCatches);
        tag.putInt("shiny", shinyCatches);
        tag.putInt("best_session", bestSessionCatches);
        tag.putLong("ticks", ticksHunted);
        ListTag species = new ListTag();
        for (String id : speciesCaught) {
            species.add(StringTag.valueOf(id));
        }
        tag.put("species", species);
        return tag;
    }

    /** Reads a compound written by {@link #save()}. Missing keys read as zero. */
    static PlayerSafariStats load(CompoundTag tag) {
        PlayerSafariStats stats = new PlayerSafariStats();
        stats.name = tag.getString("name");
        stats.sessions = tag.getInt("sessions");
        stats.totalCatches = tag.getInt("catches");
        stats.shinyCatches = tag.getInt("shiny");
        stats.bestSessionCatches = tag.getInt("best_session");
        stats.ticksHunted = tag.getLong("ticks");
        for (Tag species : tag.getList("species", Tag.TAG_STRING)) {
            stats.speciesCaught.add(species.getAsString());
        }
        return stats;
    }
}
