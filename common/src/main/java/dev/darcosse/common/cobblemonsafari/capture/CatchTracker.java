package dev.darcosse.common.cobblemonsafari.capture;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.session.CatchRecord;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * Turns Cobblemon captures made inside the Safari into session catch records.
 */
public final class CatchTracker {

    private CatchTracker() {
    }

    /** Subscribes to Cobblemon's capture event. Called once from common init. */
    public static void register() {
        // LOWEST: the capture is final by the time this event fires; running
        // last just keeps our bookkeeping out of other addons' way.
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.LOWEST, CatchTracker::onCaptured);
    }

    /** Records a capture made by a player standing in the Safari. */
    private static void onCaptured(PokemonCapturedEvent event) {
        ServerPlayer player = event.getPlayer();
        // The dimension of the player, not of the ball: a ball thrown from
        // inside and landing on a Pokémon is always inside too, and the session
        // is what the catch is credited to.
        if (!SafariDimensions.isSafari(player.level())) {
            return;
        }
        Pokemon pokemon = event.getPokemon();
        Species species = pokemon.getSpecies();
        SafariSessionManager.recordCatch(player, new CatchRecord(
                species.getResourceIdentifier().toString(), species.getTranslationKey(), pokemon.getShiny()));
    }
}
