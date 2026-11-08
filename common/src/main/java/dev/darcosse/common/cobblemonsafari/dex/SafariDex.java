package dev.darcosse.common.cobblemonsafari.dex;

import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.biome.SafariBiomes;
import dev.darcosse.common.cobblemonsafari.network.SafariDexPayload;
import dev.darcosse.common.cobblemonsafari.platform.Platform;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.spawn.HabitatEntry;
import dev.darcosse.common.cobblemonsafari.spawn.SpawnSource;
import dev.darcosse.common.cobblemonsafari.spawn.SpeciesRules;
import dev.darcosse.common.cobblemonsafari.stats.PlayerSafariStats;
import dev.darcosse.common.cobblemonsafari.stats.SafariStatsStore;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Safari Pokédex: every species that really lives in the Safari, by
 * habitat, and which of them a player has caught there.
 *
 * <p>"Really lives": a species with natural spawn data in one of the
 * Safari's surface habitats (Cobblemon's spawns and the installed spawn
 * datapacks, through the habitat index), allowed in by the config through
 * one of the two ways a Pokémon comes in. Species the index only guesses
 * from their types (most legendaries) are left out, so the Pokédex can be
 * completed, and so are those met only through Poké Snacks (Gallade since
 * Cobblemon 1.8): the Pokédex lists what can be found in the wild. Each
 * species counts once, in its main habitat among those where it spawns
 * without a snack ({@link HabitatEntry#wildMainHabitat}).
 *
 * <p>Server side. The grouping is rebuilt when the index changes
 * ({@link FeaturedRotation#revision}), checked once a second.
 */
public final class SafariDex {

    /** How often the index revision is checked, in ticks. A data reload reaches the screens within this. */
    private static final int CHECK_INTERVAL_TICKS = Ticks.PER_SECOND;

    /** Species by habitat, in the Safari's canonical order; empty until the index is built. */
    private static Map<ResourceKey<Biome>, List<Species>> habitats = Map.of();
    /** Index revision {@link #habitats} was built from. */
    private static long builtRevision = Long.MIN_VALUE;
    private static int tickCounter;

    private SafariDex() {
    }

    /** Forgets the grouping; for a server start, as single player reuses this class. */
    public static void reset() {
        habitats = Map.of();
        builtRevision = Long.MIN_VALUE;
        tickCounter = 0;
    }

    /**
     * Rebuilds the grouping and pushes everyone's Pokédex when the Safari's
     * species changed. Called each server tick. Cost: a counter per tick;
     * on a change (a data reload, a few times a day at most), one pass over
     * the index and one small packet per player.
     */
    public static void tick(MinecraftServer server) {
        if (++tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        long revision = FeaturedRotation.revision();
        if (revision == builtRevision) {
            return;
        }
        Map<ResourceLocation, HabitatEntry> index = FeaturedRotation.index(server);
        if (index.isEmpty()) {
            // Cobblemon's species or spawns are not loaded yet: try again next second.
            return;
        }
        habitats = group(index);
        builtRevision = revision;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player);
        }
    }

    /** Species by habitat, in canonical order; empty while the Safari's species are not known yet. */
    public static Map<ResourceKey<Biome>, List<Species>> habitats() {
        return habitats;
    }

    /** Number of species in the Pokédex. */
    public static int total() {
        int total = 0;
        for (List<Species> species : habitats.values()) {
            total += species.size();
        }
        return total;
    }

    /** Species ids a player has caught in the Safari; empty for a player who never hunted. */
    public static Set<String> caughtBy(MinecraftServer server, ServerPlayer player) {
        PlayerSafariStats record = SafariStatsStore.get(server).find(player.getUUID());
        return record == null ? Set.of() : record.speciesCaught();
    }

    /** Species of the Pokédex a player has caught in the Safari. */
    public static int caughtCount(MinecraftServer server, ServerPlayer player) {
        Set<String> caught = caughtBy(server, player);
        int count = 0;
        for (List<Species> species : habitats.values()) {
            for (Species one : species) {
                if (caught.contains(one.getResourceIdentifier().toString())) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Sends a player their Pokédex. Nothing before the grouping exists: the
     * tick sends it to everyone once the index is built.
     */
    public static void sendTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || habitats.isEmpty()) {
            return;
        }
        Set<String> caught = caughtBy(server, player);
        List<SafariDexPayload.Habitat> rows = new ArrayList<>(habitats.size());
        for (Map.Entry<ResourceKey<Biome>, List<Species>> habitat : habitats.entrySet()) {
            int count = 0;
            for (Species species : habitat.getValue()) {
                if (caught.contains(species.getResourceIdentifier().toString())) {
                    count++;
                }
            }
            rows.add(new SafariDexPayload.Habitat(habitat.getKey().location(), count, habitat.getValue().size()));
        }
        Platform.get().sendToPlayer(player, new SafariDexPayload(List.copyOf(rows)));
    }

    /**
     * Groups the index's natural, allowed species found in the wild (not
     * only through Poké Snacks) by main habitat, in the
     * Safari's canonical habitat order, each habitat sorted by national
     * Pokédex number (then id, for datapack species without one).
     */
    private static Map<ResourceKey<Biome>, List<Species>> group(Map<ResourceLocation, HabitatEntry> index) {
        Map<ResourceKey<Biome>, List<Species>> byHabitat = new LinkedHashMap<>();
        for (ResourceKey<Biome> biome : SafariBiomes.SURFACE_HABITATS) {
            byHabitat.put(biome, new ArrayList<>());
        }
        for (HabitatEntry entry : index.values()) {
            if (!entry.natural()) {
                continue;
            }
            Species species = entry.species();
            if (!SpeciesRules.isAllowed(species, SpawnSource.NATURAL)
                    && !SpeciesRules.isAllowed(species, SpawnSource.FEATURED)) {
                continue;
            }
            // Not mainHabitat(): a species wild in one habitat and snack-only in
            // another must be listed where players can actually find it.
            ResourceKey<Biome> main = entry.wildMainHabitat();
            if (main != null) {
                byHabitat.get(main).add(species);
            }
        }
        Map<ResourceKey<Biome>, List<Species>> result = new LinkedHashMap<>();
        Comparator<Species> order = Comparator.comparingInt(Species::getNationalPokedexNumber)
                .thenComparing(species -> species.getResourceIdentifier().toString());
        for (Map.Entry<ResourceKey<Biome>, List<Species>> habitat : byHabitat.entrySet()) {
            if (!habitat.getValue().isEmpty()) {
                habitat.getValue().sort(order);
                result.put(habitat.getKey(), List.copyOf(habitat.getValue()));
            }
        }
        return result;
    }
}
