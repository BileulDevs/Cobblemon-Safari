package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.stats.PlayerSafariStats;
import dev.darcosse.common.cobblemonsafari.stats.SafariStatsStore;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Makes Cobblemon spawn more Pokémon around players in the Safari, so no
 * habitat feels empty.
 *
 * <p>Cobblemon gives every player their own spawner. While a player is in the
 * Safari, their spawner ticks faster ({@code spawns.spawnRateMultiplier}) and
 * accepts more Pokémon per chunk ({@code spawns.pokemonPerChunkMultiplier});
 * both go back to Cobblemon's values once the player leaves. Only players this
 * class boosted are reset, so settings other mods put on a spawner are left
 * alone.
 *
 * <p>It also keeps two influences on the spawner of every player in the
 * Safari: {@link NoveltyInfluence}, with a snapshot of the species they
 * caught there, so new species come up more often; and
 * {@link SafariSpawnFilter}, with snapshots of the species the Safari would
 * refuse, so those never take part in Cobblemon's weighted pick.
 */
public final class SafariSpawnBoost {

    /**
     * How often players are checked, in ticks. A player who just entered gets
     * the boost at most this late; checking more often buys nothing visible.
     */
    private static final int CHECK_INTERVAL_TICKS = Ticks.PER_SECOND;

    /** Cobblemon's own pace, restored on leaving. */
    private static final float NORMAL_RATE = 1.0F;

    private static final Set<UUID> BOOSTED = new HashSet<>();
    private static int tickCounter;

    private SafariSpawnBoost() {
    }

    /** Forgets boosted players; call when a server starts, as single player reuses this class. */
    public static void reset() {
        BOOSTED.clear();
        NoveltyInfluence.clear();
        SafariSpawnFilter.clear();
        tickCounter = 0;
    }

    /**
     * Runs from the server tick. Cost: a counter increment per tick; once a
     * second, per online player, two field writes and, inside the Safari, a
     * stats lookup (the caught species are copied only when their number
     * changed) and one entity query for the crowding snapshot; plus, once a
     * second while anyone is in the Safari, the filter's server-wide refresh
     * (see {@link SafariSpawnFilter#refresh}).
     */
    public static void tick(MinecraftServer server) {
        if (++tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        SafariConfig.Spawns config = SafariConfigManager.get().spawns;
        NoveltyInfluence.setCaughtWeight(config.caughtSpeciesWeight);
        SafariStatsStore stats = SafariStatsStore.get(server);
        float basePerChunk = Cobblemon.INSTANCE.getConfig().getPokemonPerChunk();
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            PlayerSpawner spawner = PlayerExtensionsKt.getSpawner(player);
            if (spawner == null) {
                continue;
            }
            if (SafariDimensions.isSafari(player.level()) && config.nativeSpawns) {
                spawner.setTickTimerMultiplier((float) config.spawnRateMultiplier);
                spawner.setMaxPokemonPerChunk((float) (basePerChunk * config.pokemonPerChunkMultiplier));
                BOOSTED.add(player.getUUID());
                attachInfluences(spawner, player.getUUID(), stats);
                SafariSpawnFilter.updateCrowded(player, config.maxSameSpeciesNearby);
            } else if (BOOSTED.remove(player.getUUID())) {
                spawner.setTickTimerMultiplier(NORMAL_RATE);
                spawner.setMaxPokemonPerChunk(basePerChunk);
                // The influences stay on the spawner: they do nothing outside
                // the Safari, and leaving them avoids touching the list Cobblemon
                // may be reading. Only the snapshots go.
                NoveltyInfluence.forget(player.getUUID());
                SafariSpawnFilter.forget(player.getUUID());
            }
        }
        // Players who logged out get a fresh spawner from Cobblemon next time.
        BOOSTED.retainAll(online);
        NoveltyInfluence.retain(online);
        SafariSpawnFilter.retain(online);
        // After the loop so the snapshot is fresh for the spawners just
        // attached; skipped when nobody is in, since nothing reads it then.
        if (!BOOSTED.isEmpty()) {
            SafariSpawnFilter.refresh(server);
        }
    }

    /**
     * Puts the novelty and filter influences on a spawner once, and refreshes
     * the player's snapshot of caught species when its size changed (species
     * are only ever added, so the size tells).
     */
    private static void attachInfluences(PlayerSpawner spawner, UUID playerId, SafariStatsStore stats) {
        // Cobblemon's list is a plain ArrayList: added to once per spawner, on
        // the server thread, the first second the player is in the Safari.
        if (!spawner.getInfluences().contains(NoveltyInfluence.INSTANCE)) {
            spawner.getInfluences().add(NoveltyInfluence.INSTANCE);
        }
        if (!spawner.getInfluences().contains(SafariSpawnFilter.INSTANCE)) {
            spawner.getInfluences().add(SafariSpawnFilter.INSTANCE);
        }
        PlayerSafariStats playerStats = stats.find(playerId);
        Set<String> caught = playerStats == null ? Set.of() : playerStats.speciesCaught();
        if (!NoveltyInfluence.hasSize(playerId, caught.size())) {
            NoveltyInfluence.update(playerId, Set.copyOf(caught));
        }
    }
}
