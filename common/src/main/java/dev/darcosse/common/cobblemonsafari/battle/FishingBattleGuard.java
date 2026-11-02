package dev.darcosse.common.cobblemonsafari.battle;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleStartedEvent;
import com.cobblemon.mod.common.api.events.fishing.BobberSpawnPokemonEvent;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Optionally stops the battle Cobblemon starts on its own with a Pokémon
 * just fished in the Safari ({@code battles.fishingStartsBattle = false}), so the
 * angler can throw a Safari Ball at it out of battle instead.
 *
 * <p>How Cobblemon does it: one second after a bobber pulls a Pokémon out,
 * it forces a battle between that Pokémon and the angler. Nothing marks
 * that battle as the fishing one, so the fished Pokémon are remembered
 * here when they come out of the water, and the first battle any of them
 * enters within {@link #FORCED_BATTLE_WINDOW_TICKS} is cancelled. A battle
 * the player starts later with that Pokémon is left alone. Cancelling at
 * the battle's pre-start event, which Cobblemon offers for exactly this,
 * leaves nothing half started and shows no message. Server side.
 */
public final class FishingBattleGuard {

    /**
     * How long after the bite the forced battle is expected, in ticks.
     * Cobblemon waits 1 s (20 ticks); 2 s leaves room for a lagging server.
     * Longer would also cancel a battle the player starts on purpose right
     * after the bite, when Cobblemon's own attempt failed (no Pokémon able
     * to fight) and so never used up the entry.
     */
    private static final int FORCED_BATTLE_WINDOW_TICKS = 40;

    /**
     * Fished Pokémon awaiting their forced battle, by Pokémon UUID, with the
     * server tick of the bite. Entries leave when their battle is refused or
     * when they are older than the window (purged on each new bite), so the
     * map holds a handful at most. Server thread only.
     */
    private static final Map<UUID, Integer> FRESHLY_FISHED = new LinkedHashMap<>();

    private FishingBattleGuard() {
    }

    /** Subscribes to bites and battle starts. Called once from common init. */
    public static void register() {
        // LOWEST: only reads the spawn, after any addon that might change the Pokémon.
        CobblemonEvents.BOBBER_SPAWN_POKEMON_POST.subscribe(Priority.LOWEST, FishingBattleGuard::onFished);
        // HIGH: before addons that react to a battle about to start; BattleGuard (HIGHEST) refuses all of them first anyway.
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.HIGH, FishingBattleGuard::onBattleStart);
    }

    /** Forgets every bite; for a server start, as single player reuses this class. */
    public static void reset() {
        FRESHLY_FISHED.clear();
    }

    /** Remembers a Pokémon fished in the Safari while the option is off. */
    private static void onFished(BobberSpawnPokemonEvent.Post event) {
        if (SafariConfigManager.get().battles.fishingStartsBattle) {
            return;
        }
        Player owner = event.getBobber().getPlayerOwner();
        if (owner == null || !SafariDimensions.isSafari(owner.level())) {
            return;
        }
        MinecraftServer server = owner.getServer();
        if (server == null) {
            return;
        }
        int now = server.getTickCount();
        forgetExpired(now);
        FRESHLY_FISHED.put(event.getPokemon().getPokemon().getUuid(), now);
    }

    /** Cancels the forced battle of a freshly fished Pokémon. */
    private static void onBattleStart(BattleStartedEvent.Pre event) {
        if (FRESHLY_FISHED.isEmpty()) {
            return;
        }
        // The angler: a fishing battle always has exactly one player, and the bite was recorded in their server.
        List<ServerPlayer> players = event.getBattle().getPlayers();
        MinecraftServer server = players.isEmpty() ? null : players.get(0).getServer();
        if (server == null) {
            return;
        }
        int now = server.getTickCount();
        for (BattleActor actor : event.getBattle().getActors()) {
            for (BattlePokemon pokemon : actor.getPokemonList()) {
                // Removed whatever the outcome: only the first battle after the bite is Cobblemon's.
                Integer bite = FRESHLY_FISHED.remove(pokemon.getOriginalPokemon().getUuid());
                if (bite != null && now - bite <= FORCED_BATTLE_WINDOW_TICKS
                        && !SafariConfigManager.get().battles.fishingStartsBattle) {
                    event.cancel();
                    return;
                }
            }
        }
    }

    /** Drops the bites older than the window: their forced battle never came (or failed before the event). */
    private static void forgetExpired(int now) {
        Iterator<Map.Entry<UUID, Integer>> entries = FRESHLY_FISHED.entrySet().iterator();
        while (entries.hasNext()) {
            if (now - entries.next().getValue() > FORCED_BATTLE_WINDOW_TICKS) {
                entries.remove();
            }
        }
    }
}
