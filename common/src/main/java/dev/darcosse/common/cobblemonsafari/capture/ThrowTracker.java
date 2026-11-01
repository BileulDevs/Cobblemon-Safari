package dev.darcosse.common.cobblemonsafari.capture;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokeball.PokemonCatchRateEvent;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * What a capture needs to know about the throw that made it, read by every
 * feature that reacts to captures (missions, advancements): whether the
 * ball hit a Pokémon in a battle, and whether it was night.
 *
 * <p>Why a shared tracker: Cobblemon's capture event does not say whether
 * the Pokémon was in a battle, and by the time it fires the battle may be
 * over. The catch rate event, which fires for every throw that hits, still
 * knows; it is recorded here once and read by all. Server side only.
 */
public final class ThrowTracker {

    /**
     * Night as Cobblemon's spawner counts it (its "night" time range, in
     * ticks of the day), so that a night mission, a night advancement and
     * the night spawns agree. Widening it would let dusk captures count.
     */
    private static final long NIGHT_START = 12_542L;
    private static final long NIGHT_END = 23_459L;
    private static final long TICKS_PER_DAY = 24_000L;

    /**
     * Balls thrown at a Pokémon, mapped to whether that Pokémon was in a
     * battle. Weak: a ball that missed or broke free is forgotten with its
     * entity, and readers do not need to remove what they read.
     */
    private static final Map<EmptyPokeBallEntity, Boolean> IN_BATTLE = new WeakHashMap<>();

    private ThrowTracker() {
    }

    /** Subscribes to Cobblemon's catch rate event. Called once from common init. */
    public static void register() {
        // LOWEST: only reads the event, after any addon that might change it.
        CobblemonEvents.POKEMON_CATCH_RATE.subscribe(Priority.LOWEST, ThrowTracker::onCatchRate);
    }

    /** Remembers whether a ball was thrown at a Pokémon in a battle. */
    private static void onCatchRate(PokemonCatchRateEvent event) {
        if (event.getPokeBallEntity() != null && event.getPokemonEntity() != null) {
            IN_BATTLE.put(event.getPokeBallEntity(), event.getPokemonEntity().getBattleId() != null);
        }
    }

    /**
     * Whether the ball was thrown at a Pokémon in a battle.
     *
     * @return null when the catch rate never saw that ball (another mod's capture)
     */
    public static Boolean wasInBattle(EmptyPokeBallEntity ball) {
        return ball == null ? null : IN_BATTLE.get(ball);
    }

    /** Whether it is night where the entity stands, by Cobblemon's spawner's definition. */
    public static boolean isNight(Entity where) {
        long time = Math.floorMod(where.level().getDayTime(), TICKS_PER_DAY);
        return time >= NIGHT_START && time <= NIGHT_END;
    }

    /** Forgets every throw; for a server start, as single player reuses this class. */
    public static void reset() {
        IN_BATTLE.clear();
    }
}
