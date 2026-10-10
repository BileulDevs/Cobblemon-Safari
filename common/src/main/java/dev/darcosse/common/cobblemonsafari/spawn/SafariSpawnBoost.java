package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
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
 * class boosted are reset, so settings other mods put on a spawner are left alone.
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
        tickCounter = 0;
    }

    /**
     * Runs from the server tick. Cost: a counter increment per tick; once a
     * second, two field writes per online player.
     */
    public static void tick(MinecraftServer server) {
        if (++tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        SafariConfig.Spawns config = SafariConfigManager.get().spawns;
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
            } else if (BOOSTED.remove(player.getUUID())) {
                spawner.setTickTimerMultiplier(NORMAL_RATE);
                spawner.setMaxPokemonPerChunk(basePerChunk);
            }
        }
        // Players who logged out get a fresh spawner from Cobblemon next time.
        BOOSTED.retainAll(online);
    }
}
