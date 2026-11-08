package dev.darcosse.common.cobblemonsafari.capture;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent;
import com.cobblemon.mod.common.api.mark.Mark;
import com.cobblemon.mod.common.api.mark.Marks;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.spawn.Downpour;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.spawn.HabitatEntry;
import dev.darcosse.common.cobblemonsafari.spawn.SafariHappenings;
import dev.darcosse.common.cobblemonsafari.spawn.Swarm;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Safari's Cobblemon marks, given on capture inside the Safari:
 * <ul>
 *   <li>the Safari Mark, to every Pokémon caught there ("Tauros the Safari Find");</li>
 *   <li>event marks, for a catch made at a remarkable moment: one of the
 *       specials of the day (the star), a Pokémon of the swarm in its
 *       habitat (the swarm), a catch where a downpour falls (the downpour),
 *       a catch on a full moon night (the moon), and a Pokémon fished in the
 *       Safari (the angler).</li>
 * </ul>
 *
 * <p>The marks themselves are data, like Cobblemon's own
 * ({@code data/cobblemon_safari/marks/}, icons in
 * {@code textures/gui/mark/}): Cobblemon loads them, syncs them to clients
 * and lists them in the summary's marks page. Their {@code chance} is left
 * at 0 so Cobblemon never rolls them on a wild spawn; only this class gives
 * them. Server side.
 *
 * <p>Title: an event mark's title replaces the Safari Mark's as the active
 * one, the rarest moment first (star, swarm, downpour, moon, angler); a title the
 * Pokémon already has from Cobblemon (weather, personality) stays.
 */
public final class SafariMark {

    /** The Safari Mark's id, from its file name. Renaming a file drops its mark from every Pokémon that has it. */
    public static final ResourceLocation ID = CobblemonSafari.id("mark_safari");
    /** One of the specials of the day. */
    public static final ResourceLocation STAR = CobblemonSafari.id("mark_safari_star");
    /** A Pokémon of the swarm, caught in the swarm's habitat. */
    public static final ResourceLocation SWARM = CobblemonSafari.id("mark_safari_swarm");
    /** Caught where a downpour falls. */
    public static final ResourceLocation DOWNPOUR = CobblemonSafari.id("mark_safari_downpour");
    /** Caught on a full moon night. */
    public static final ResourceLocation MOON = CobblemonSafari.id("mark_safari_moon");
    /** Fished, then caught, in the Safari. */
    public static final ResourceLocation ANGLER = CobblemonSafari.id("mark_safari_angler");

    /** Marks found missing, each warned about once rather than on every catch. */
    private static final Set<ResourceLocation> WARNED = new HashSet<>();

    private SafariMark() {
    }

    /** Subscribes to captures. Called once from common init. */
    public static void register() {
        // LOWEST: after Cobblemon's own marks are applied, so its pick of a title is kept.
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.LOWEST, SafariMark::onCaptured);
    }

    /** Marks a Pokémon caught in the Safari, then picks its title. */
    private static void onCaptured(PokemonCapturedEvent event) {
        ServerPlayer player = event.getPlayer();
        MinecraftServer server = player.getServer();
        if (server == null || !SafariDimensions.isSafari(player.level())) {
            return;
        }
        SafariConfig.Capture config = SafariConfigManager.get().capture;
        Pokemon pokemon = event.getPokemon();
        Mark safari = config.marks.safari ? give(pokemon, ID) : null;
        // In order of preference for the title: the rarest moment first.
        List<Mark> events = new ArrayList<>();
        SafariConfig.Marks marks = config.marks;
        if (marks.special && isSpecial(server, pokemon)) {
            addIfLoaded(events, give(pokemon, STAR));
        }
        // Where the Pokémon was: the ball lies on it. The player when there is no ball.
        EmptyPokeBallEntity ball = event.getPokeBallEntity();
        Entity where = ball != null ? ball : player;
        if (marks.swarm && Swarm.isSwarming(pokemon, where.level().getBiome(where.blockPosition()))) {
            addIfLoaded(events, give(pokemon, SWARM));
        }
        // Where the rain falls, as for the downpour's spawns: not in snow, a dry biome or under a roof.
        if (marks.downpour && Downpour.fallsAt(where.level(), where.blockPosition())) {
            addIfLoaded(events, give(pokemon, DOWNPOUR));
        }
        if (marks.fullMoon && SafariHappenings.isFullMoon()) {
            addIfLoaded(events, give(pokemon, MOON));
        }
        if (marks.angler && FishedTracker.wasFished(pokemon.getUuid())) {
            addIfLoaded(events, give(pokemon, ANGLER));
        }
        Mark active = pokemon.getActiveMark();
        // An event title wins over none or over the plain Safari one; Cobblemon's titles stay.
        if (!events.isEmpty() && (active == null || active == Marks.getByIdentifier(ID))) {
            pokemon.setActiveMark(events.get(0));
        } else if (active == null && safari != null) {
            pokemon.setActiveMark(safari);
        }
    }

    /** Whether the species is one of the specials of the current rotation. */
    private static boolean isSpecial(MinecraftServer server, Pokemon pokemon) {
        for (HabitatEntry entry : FeaturedRotation.today(server)) {
            if (entry.species() == pokemon.getSpecies()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gives a mark to the Pokémon (synced to its owner's client, as Cobblemon
     * does for its own), and returns it; null when the mark is not loaded.
     */
    private static Mark give(Pokemon pokemon, ResourceLocation id) {
        Mark mark = Marks.getByIdentifier(id);
        if (mark == null) {
            // A datapack overriding the mod's data, or a broken jar: say it once, not on every catch.
            if (WARNED.add(id)) {
                CobblemonSafari.LOGGER.warn("Safari mark {} is not loaded; it is not given", id);
            }
            return null;
        }
        // true: syncs the new mark to the owner's client.
        pokemon.exchangeMark(mark, true);
        return mark;
    }

    /** Adds a mark to the list when it was given. */
    private static void addIfLoaded(List<Mark> marks, Mark mark) {
        if (mark != null) {
            marks.add(mark);
        }
    }
}
