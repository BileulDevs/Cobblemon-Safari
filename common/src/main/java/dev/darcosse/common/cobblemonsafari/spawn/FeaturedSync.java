package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.network.SafariFeaturedPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Keeps every client's copy of the special Pokémon of the day current, so the
 * wall screens never show a stale list.
 *
 * <p>Pushes on join, then whenever {@link FeaturedRotation#revision()}
 * changes: new rotation, data reload, config reload, or the first successful
 * draw after Cobblemon finished loading.
 */
public final class FeaturedSync {

    /**
     * How often the revision is checked, in ticks. A new rotation reaches the
     * screens at most this late. Lower is more reactive; each check costs one
     * cached list lookup, so this mostly bounds how often an empty index is
     * retried (itself throttled in {@link FeaturedRotation}).
     */
    private static final int CHECK_INTERVAL_TICKS = Ticks.PER_SECOND;

    private static int tickCounter;
    /** Revision last broadcast; MIN_VALUE forces a broadcast on the first check. */
    private static long sentRevision = Long.MIN_VALUE;
    /** Entries built for {@link #entriesRevision}; rebuilt only when the revision moves. */
    @Nullable
    private static List<SafariFeaturedPayload.Entry> entries;
    private static long entriesRevision = Long.MIN_VALUE;

    private FeaturedSync() {
    }

    /** Forgets what was sent; call when a server starts, since single player reuses this class. */
    public static void reset() {
        tickCounter = 0;
        sentRevision = Long.MIN_VALUE;
        entries = null;
        entriesRevision = Long.MIN_VALUE;
    }

    /**
     * Runs from the server tick. Cost: one counter increment per tick; once a
     * second, a cached list lookup and, only when the list changed, one
     * payload per online player.
     */
    public static void tick(MinecraftServer server) {
        if (++tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        // Called for its side effect: starts a new draw when the rotation
        // rolled over, which moves the revision.
        FeaturedRotation.today(server);
        long revision = FeaturedRotation.revision();
        if (revision != sentRevision) {
            sentRevision = revision;
            SafariNetwork.broadcastFeatured(server, build(server));
        }
    }

    /** Sends the current list to a player who just joined. */
    public static void sendTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            SafariNetwork.sendFeatured(player, build(server));
        }
    }

    /**
     * Snapshot for the clients. The entries are cached per revision; the time
     * left is recomputed on every call, since it is sent as a relative value.
     */
    private static SafariFeaturedPayload build(MinecraftServer server) {
        List<HabitatEntry> today = FeaturedRotation.today(server);
        long revision = FeaturedRotation.revision();
        if (entries == null || entriesRevision != revision) {
            entries = toEntries(today);
            entriesRevision = revision;
        }
        return new SafariFeaturedPayload(SafariConfigManager.get().featured.enabled,
                FeaturedRotation.millisUntilNextRotation(), FeaturedRotation.rotationLengthMillis(), entries);
    }

    /** Converts the server-side entries to what the client needs to draw them. */
    private static List<SafariFeaturedPayload.Entry> toEntries(List<HabitatEntry> today) {
        List<SafariFeaturedPayload.Entry> converted = new ArrayList<>(today.size());
        for (HabitatEntry entry : today) {
            PokemonProperties template = entry.template();
            // Aspects come from the parsed spawn template (regional forms are
            // flags there), sorted so the payload is identical for identical data.
            List<String> aspects = template == null ? List.of() : List.copyOf(new TreeSet<>(template.getAspects()));
            ResourceKey<Biome> habitat = entry.mainHabitat();
            converted.add(new SafariFeaturedPayload.Entry(entry.species().getResourceIdentifier(), aspects,
                    entry.rarity(), habitat == null ? null : habitat.location(),
                    SpeciesRules.isSpecial(entry.species()), entry.minLevel(), entry.maxLevel()));
        }
        return List.copyOf(converted);
    }
}
