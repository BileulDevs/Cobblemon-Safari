package dev.darcosse.common.cobblemonsafari.stats;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lifetime Safari record of one player: the "hunting log" the games never
 * had, shown by {@code /safari stats}, {@code /safari top} and the wall
 * screens' STATS page.
 *
 * <p>Mutated only through {@link SafariStatsListener} and {@link SafariStatsStore},
 * which mark the store dirty.
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
    /** Distinct maps hunted on, counted when a hunt starts on a map other than the last one. */
    private int mapsHunted;
    /**
     * Salt of the map of the last hunt, or {@link #NO_MAP}. A sentinel and
     * not 0: the first map of a world has salt 0.
     */
    private long lastMapSalt = NO_MAP;

    /** {@link #lastMapSalt} of a player who never hunted since maps were counted. */
    private static final long NO_MAP = Long.MIN_VALUE;

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

    /** Distinct maps the player has hunted on (since 1.4.0, when they started being counted). */
    public int mapsHunted() {
        return mapsHunted;
    }

    /**
     * Notes a hunt on the map of the given salt, counting it when it is not
     * the map of the previous hunt. Maps only ever move on, so "not the last
     * one" means "a new one".
     *
     * @return whether the count changed
     */
    boolean markMapHunted(long mapSalt) {
        if (mapSalt == lastMapSalt) {
            return false;
        }
        lastMapSalt = mapSalt;
        mapsHunted++;
        return true;
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
        tag.putInt("maps", mapsHunted);
        tag.putLong("last_map", lastMapSalt);
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
        stats.mapsHunted = tag.getInt("maps");
        // contains: a missing key must read as "no map", not as salt 0, the first map's.
        stats.lastMapSalt = tag.contains("last_map", Tag.TAG_LONG) ? tag.getLong("last_map") : NO_MAP;
        for (Tag species : tag.getList("species", Tag.TAG_STRING)) {
            stats.speciesCaught.add(species.getAsString());
        }
        return stats;
    }
}
