package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The special Pokémon of the day: a small, rarity-balanced selection drawn
 * from the habitat index and renewed every rotation.
 *
 * <p>The draw is deterministic: same world, same rotation, same installed
 * data, same list. Restarting the server or asking twice never reshuffles
 * it, and every player sees the same board.
 *
 * <p>Both the index and the list are cached; {@link #invalidate()} drops them
 * after a data or config reload and when a server starts (single player keeps
 * this class loaded across worlds). Rebuilding happens lazily, on the next
 * request, never in the middle of a tick that did not ask for it.
 *
 * <p>{@link #revision()} changes every time the list may have changed, which
 * is what {@link FeaturedSync} watches to push the Safari Board screens.
 */
public final class FeaturedRotation {

    /** Mixed into the seed so the rotation never correlates with other uses of the salt. */
    private static final long ROTATION_SEED_MIX = 0x9E3779B97F4A7C15L;

    private static final long MILLIS_PER_MINUTE = 60_000L;

    /**
     * Minimum delay between two index builds while the index comes out empty.
     * An empty index usually means Cobblemon has not loaded its data yet, so
     * retrying is right, but a build walks every species and spawn: retrying
     * on every call would cost a full build per call on a server where nothing
     * qualifies. Lower retries sooner, higher waits longer for the first list.
     */
    private static final long EMPTY_INDEX_RETRY_MILLIS = 30_000L;

    @Nullable
    private static Map<ResourceLocation, HabitatEntry> index;
    @Nullable
    private static List<HabitatEntry> current;
    private static long currentPeriod = Long.MIN_VALUE;
    /** When the last build came out empty; only meaningful while {@link #index} is null. */
    private static long lastEmptyBuildMillis;
    private static boolean emptyBuildPending;
    private static long revision;

    private FeaturedRotation() {
    }

    /**
     * Drops every cached result. Call after a datapack or config reload, and
     * when a server starts.
     */
    public static void invalidate() {
        index = null;
        current = null;
        currentPeriod = Long.MIN_VALUE;
        emptyBuildPending = false;
        revision++;
    }

    /**
     * Counter that changes whenever today's list may have changed: a new draw,
     * or cached data dropped. Equal values mean the list is the same.
     */
    public static long revision() {
        return revision;
    }

    /** The habitat index for the current data, built on first use. */
    public static Map<ResourceLocation, HabitatEntry> index(MinecraftServer server) {
        if (index == null) {
            long now = System.currentTimeMillis();
            if (emptyBuildPending && now - lastEmptyBuildMillis < EMPTY_INDEX_RETRY_MILLIS) {
                return Map.of();
            }
            Map<ResourceLocation, HabitatEntry> built =
                    SafariHabitatIndex.build(server, SafariConfigManager.get().featured.includeWithoutNaturalSpawn);
            // Empty usually means Cobblemon has not loaded its species or spawn
            // pool yet; caching it would leave the board empty until a reload.
            if (built.isEmpty()) {
                emptyBuildPending = true;
                lastEmptyBuildMillis = now;
                return built;
            }
            emptyBuildPending = false;
            index = built;
        }
        return index;
    }

    /** Today's specials, most common first; empty when the feature is off or nothing qualifies. */
    public static List<HabitatEntry> today(MinecraftServer server) {
        if (!SafariConfigManager.get().featured.enabled) {
            return List.of();
        }
        long period = currentPeriod();
        if (current == null || period != currentPeriod) {
            Map<ResourceLocation, HabitatEntry> available = index(server);
            if (available.isEmpty()) {
                // Not cached: the draw of an empty index would be kept for the
                // whole rotation, defeating the retry done by index().
                return List.of();
            }
            current = draw(available, period, SafariWorldState.get(server).rotationSalt());
            currentPeriod = period;
            revision++;
            CobblemonSafari.LOGGER.info("Safari specials for rotation {}: {}", period,
                    current.stream().map(e -> e.species().getResourceIdentifier().toString()).toList());
        }
        return current;
    }

    /**
     * Index of the rotation running now. Bounties are drawn per rotation, so
     * they change together with the specials.
     */
    public static long period() {
        return currentPeriod();
    }

    /** Milliseconds until the list changes. */
    public static long millisUntilNextRotation() {
        long length = rotationLengthMillis();
        return length - Math.floorMod(System.currentTimeMillis(), length);
    }

    /** Index of the rotation running now, counted from the Unix epoch. */
    private static long currentPeriod() {
        return Math.floorDiv(System.currentTimeMillis(), rotationLengthMillis());
    }

    /** Length of one rotation in milliseconds, from the config. */
    public static long rotationLengthMillis() {
        return SafariConfigManager.get().featured.rotationMinutes * MILLIS_PER_MINUTE;
    }

    /**
     * Picks the specials of one rotation.
     *
     * <p>Each rarity fills its own slots, so the board always mixes uncommon,
     * rare and ultra-rare finds. Slots a rarity cannot fill (no legendary
     * allowed, for instance) fall back to the next more common rarity rather
     * than staying empty. Within a rarity, species whose main habitat is not
     * taken yet go first, so the specials send players to different corners of
     * the park instead of all to the same forest.
     */
    private static List<HabitatEntry> draw(Map<ResourceLocation, HabitatEntry> index, long period, long salt) {
        SafariConfig.Featured config = SafariConfigManager.get().featured;
        Random random = new Random(salt ^ (period * ROTATION_SEED_MIX));

        Map<Rarity, List<HabitatEntry>> pools = new EnumMap<>(Rarity.class);
        for (Rarity rarity : Rarity.values()) {
            pools.put(rarity, new ArrayList<>());
        }
        // The index is sorted by species id, so pools are built in a stable
        // order before shuffling: required for the draw to be reproducible.
        for (HabitatEntry entry : index.values()) {
            if (entry.rarity() == Rarity.COMMON || !SpeciesRules.isAllowed(entry.species())) {
                continue;
            }
            if (!entry.natural() && !config.includeWithoutNaturalSpawn) {
                continue;
            }
            pools.get(entry.rarity()).add(entry);
        }
        for (List<HabitatEntry> pool : pools.values()) {
            Collections.shuffle(pool, random);
        }

        List<HabitatEntry> picked = new ArrayList<>();
        Set<ResourceKey<Biome>> usedHabitats = new HashSet<>();
        int carried = 0;
        // Rarest first, so leftover slots can roll over to the more common tiers.
        carried = fill(pools.get(Rarity.ULTRA_RARE), config.ultraRareSlots + carried, picked, usedHabitats);
        carried = fill(pools.get(Rarity.RARE), config.rareSlots + carried, picked, usedHabitats);
        fill(pools.get(Rarity.UNCOMMON), config.uncommonSlots + carried, picked, usedHabitats);

        picked.sort((a, b) -> Integer.compare(a.rarity().ordinal(), b.rarity().ordinal()));
        return List.copyOf(picked);
    }

    /**
     * Takes up to {@code slots} entries from a shuffled pool, new habitats first.
     *
     * @return the number of slots left unfilled
     */
    private static int fill(List<HabitatEntry> pool, int slots, List<HabitatEntry> picked,
                            Set<ResourceKey<Biome>> usedHabitats) {
        int remaining = slots;
        List<HabitatEntry> deferred = new ArrayList<>();
        for (HabitatEntry entry : pool) {
            if (remaining == 0) {
                break;
            }
            ResourceKey<Biome> habitat = entry.mainHabitat();
            if (habitat != null && usedHabitats.contains(habitat)) {
                deferred.add(entry);
                continue;
            }
            picked.add(entry);
            if (habitat != null) {
                usedHabitats.add(habitat);
            }
            remaining--;
        }
        for (HabitatEntry entry : deferred) {
            if (remaining == 0) {
                break;
            }
            picked.add(entry);
            remaining--;
        }
        return remaining;
    }
}
