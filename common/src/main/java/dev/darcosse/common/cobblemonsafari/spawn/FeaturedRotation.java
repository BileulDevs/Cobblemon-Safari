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

    /**
     * Species with no natural spawn anywhere (most legendaries, some data
     * pack species) may be featured, their habitat guessed from their types.
     * Whether a legendary can actually be drawn is the spawns.allowLegendary
     * setting's job; this only makes such species known to the index.
     */
    private static final boolean INCLUDE_WITHOUT_NATURAL_SPAWN = true;

    /**
     * Chance that a slot goes to a starter's line when the pool holds both
     * starters and others. Cobblemon files most starter lines as ultra-rare,
     * where they would otherwise take most slots (27 lines against a short
     * list of other ultra-rares). Higher brings starters back to the board
     * more often; 1 drops the rule (starters first), 0 shows them only when
     * nothing else is left.
     */
    private static final double STARTER_SLOT_CHANCE = 0.25;

    @Nullable
    private static Map<ResourceLocation, HabitatEntry> index;
    @Nullable
    private static List<HabitatEntry> current;
    private static long currentPeriod = Long.MIN_VALUE;
    /** When the last build came out empty; only meaningful while {@link #index} is null. */
    private static long lastEmptyBuildMillis;
    private static boolean emptyBuildPending;
    private static long revision;
    /**
     * The world's count of rotations started by hand (SafariWorldState),
     * mirrored here because the rotation index is read without a server at
     * hand. Set by {@link #attach} on server start and by {@link #forceNext}.
     */
    private static volatile long rotationOffset;

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

    /**
     * Loads the world's forced rotations. Call on server start, before the
     * first use of {@link #period()}: single player keeps this class loaded
     * from one world to the next.
     */
    public static void attach(MinecraftServer server) {
        rotationOffset = SafariWorldState.get(server).rotationOffset();
    }

    /**
     * Starts a new rotation now: new specials, and with them new bounties and
     * shop Pokémon, all keyed on the rotation index, which this moves on by
     * one. The schedule is kept: the forced rotation ends at the next
     * scheduled change, so the rotations stay aligned on midnight UTC.
     * Players are updated by the usual revision checks within a few seconds.
     */
    public static void forceNext(MinecraftServer server) {
        SafariWorldState state = SafariWorldState.get(server);
        state.advanceRotation();
        rotationOffset = state.rotationOffset();
        current = null;
        revision++;
    }

    /** The habitat index for the current data, built on first use. */
    public static Map<ResourceLocation, HabitatEntry> index(MinecraftServer server) {
        if (index == null) {
            long now = System.currentTimeMillis();
            if (emptyBuildPending && now - lastEmptyBuildMillis < EMPTY_INDEX_RETRY_MILLIS) {
                return Map.of();
            }
            Map<ResourceLocation, HabitatEntry> built =
                    SafariHabitatIndex.build(server, INCLUDE_WITHOUT_NATURAL_SPAWN);
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

    /**
     * Index of the rotation running now: rotations since the Unix epoch,
     * plus those started by hand.
     */
    private static long currentPeriod() {
        return Math.floorDiv(System.currentTimeMillis(), rotationLengthMillis()) + rotationOffset;
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
            if (!entry.natural() && !INCLUDE_WITHOUT_NATURAL_SPAWN) {
                continue;
            }
            pools.get(entry.rarity()).add(entry);
        }
        for (Rarity rarity : Rarity.values()) {
            List<HabitatEntry> pool = pools.get(rarity);
            Collections.shuffle(pool, random);
            pools.put(rarity, limitStarters(pool, random));
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
     * Reorders a shuffled pool so that each position is a starter's line with
     * {@link #STARTER_SLOT_CHANCE}, another species otherwise; when one kind
     * runs out, the other fills the rest. {@link #fill} then takes the pool
     * in this order. Uses the rotation's random, so the draw stays
     * reproducible.
     */
    private static List<HabitatEntry> limitStarters(List<HabitatEntry> pool, Random random) {
        List<HabitatEntry> starters = new ArrayList<>();
        List<HabitatEntry> others = new ArrayList<>();
        for (HabitatEntry entry : pool) {
            (SpeciesRules.isStarterLine(entry.species()) ? starters : others).add(entry);
        }
        if (starters.isEmpty() || others.isEmpty()) {
            return pool;
        }
        List<HabitatEntry> ordered = new ArrayList<>(pool.size());
        int s = 0;
        int o = 0;
        while (s < starters.size() || o < others.size()) {
            boolean starter = o >= others.size()
                    || (s < starters.size() && random.nextDouble() < STARTER_SLOT_CHANCE);
            ordered.add(starter ? starters.get(s++) : others.get(o++));
        }
        return ordered;
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
