package dev.darcosse.common.cobblemonsafari.capture;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokeball.PokemonCatchRateEvent;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.minecraft.resources.ResourceLocation;

/**
 * Makes the Safari Ball worth throwing at a Pokémon that is not in a battle,
 * inside the Safari only.
 *
 * <p>Cobblemon halves every capture attempted outside a battle, and its Safari
 * Ball bonus (×1.5 out of battle) does not make up for it. Since the Safari is
 * about throwing balls rather than battling, the species catch rate is
 * multiplied here by {@code capture.safariBallOutOfBattleMultiplier}. Server
 * side: Cobblemon computes captures on the server only.
 */
public final class SafariBallBonus {

    private SafariBallBonus() {
    }

    /** Subscribes to Cobblemon's catch rate event. Called once from common init. */
    public static void register() {
        // LOW: other addons see the species' real rate first; ours is the
        // Safari's final word on a throw that already passed CaptureGuard.
        CobblemonEvents.POKEMON_CATCH_RATE.subscribe(Priority.LOW, SafariBallBonus::apply);
    }

    /** Multiplies the catch rate when the throw qualifies. */
    private static void apply(PokemonCatchRateEvent event) {
        SafariConfig.Capture config = SafariConfigManager.get().capture;
        if (config.safariBallOutOfBattleMultiplier <= 1.0
                || event.getPokemonEntity().getBattleId() != null
                || !SafariDimensions.isSafari(event.getPokemonEntity().level())) {
            return;
        }
        ResourceLocation ball = event.getPokeBallEntity().getPokeBall().getName();
        if (!ball.toString().equals(config.safariBallItem)) {
            return;
        }
        event.setCatchRate((float) (event.getCatchRate() * config.safariBallOutOfBattleMultiplier));
    }
}
