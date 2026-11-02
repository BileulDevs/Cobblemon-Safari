package dev.darcosse.common.cobblemonsafari.capture;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers the Pokémon a rod pulled out of the water in the Safari, so a
 * later capture can be told to be of a fished Pokémon (the advancements and
 * the angler's mark read it). A fished Pokémon carries no sign of it, and
 * the capture may come minutes after the bite.
 *
 * <p>Readers never remove: two features read the same capture, in an order
 * Cobblemon does not guarantee. Bounded instead: beyond
 * {@link #MEMORY} Pokémon, the oldest are forgotten (one that fled or
 * despawned is never caught anyway). Server thread only.
 */
public final class FishedTracker {

    /**
     * Fished Pokémon remembered at most. Lowering it risks forgetting one
     * still being chased on a busy server.
     */
    private static final int MEMORY = 256;

    /** Fished Pokémon by Pokémon UUID, oldest first. */
    private static final Map<UUID, Boolean> FISHED = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > MEMORY;
        }
    };

    private FishedTracker() {
    }

    /** Remembers a Pokémon a rod just brought up in the Safari. */
    public static void remember(UUID pokemonId) {
        FISHED.put(pokemonId, Boolean.TRUE);
    }

    /** Whether the Pokémon was fished in the Safari (and not forgotten since). */
    public static boolean wasFished(UUID pokemonId) {
        return FISHED.containsKey(pokemonId);
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    public static void reset() {
        FISHED.clear();
    }
}
