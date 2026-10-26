package dev.darcosse.common.cobblemonsafari.capture;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent;
import com.cobblemon.mod.common.api.mark.Mark;
import com.cobblemon.mod.common.api.mark.Marks;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Safari Mark: a Cobblemon mark given to every Pokémon caught in the
 * Safari, so that it keeps where it came from ("Tauros the Safari Find").
 *
 * <p>The mark itself is data, like Cobblemon's own
 * ({@code data/cobblemon_safari/marks/mark_safari.json}, icon
 * {@code textures/gui/mark/mark_safari.png}): Cobblemon loads it, syncs it to
 * clients and lists it in the summary's marks page. Its {@code chance} is
 * left at 0 so Cobblemon never rolls it on a wild spawn; only this class
 * gives it. Server side.
 */
public final class SafariMark {

    /** The mark's id, from its file name. Renaming the file drops it from every Pokémon that has it. */
    public static final ResourceLocation ID = CobblemonSafari.id("mark_safari");

    private static boolean warned;

    private SafariMark() {
    }

    /** Subscribes to captures. Called once from common init. */
    public static void register() {
        // LOWEST: after Cobblemon's own marks are applied, so its pick of a title is kept.
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.LOWEST, SafariMark::onCaptured);
    }

    /**
     * Marks a Pokémon caught in the Safari. Its title becomes the active one
     * only when it has none yet: a weather or personality mark Cobblemon
     * gave on the same capture stays in front.
     */
    private static void onCaptured(PokemonCapturedEvent event) {
        ServerPlayer player = event.getPlayer();
        if (!SafariConfigManager.get().capture.awardSafariMark || !SafariDimensions.isSafari(player.level())) {
            return;
        }
        Mark mark = Marks.getByIdentifier(ID);
        if (mark == null) {
            if (!warned) {
                // A datapack overriding the mod's data, or a broken jar: say it once, not on every catch.
                warned = true;
                CobblemonSafari.LOGGER.warn("Safari Mark {} is not loaded; Safari catches are not marked", ID);
            }
            return;
        }
        Pokemon pokemon = event.getPokemon();
        // true: syncs the new mark to the owner's client, as Cobblemon does for its own.
        pokemon.exchangeMark(mark, true);
        if (pokemon.getActiveMark() == null) {
            pokemon.setActiveMark(mark);
        }
    }
}
