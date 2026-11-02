package dev.darcosse.common.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.network.SafariDexPayload;

/**
 * The client's copy of the local player's Safari Pokédex, for the DEX page
 * of the wall screens. Holds no client-only types, so payload handlers
 * registered on both dists can call it.
 */
public final class DexClientState {

    /** Null until the server's first snapshot arrives. */
    private static SafariDexPayload dex;

    private DexClientState() {
    }

    /** Applies a snapshot from the server. */
    public static void onDex(SafariDexPayload payload) {
        dex = payload;
    }

    /** Forgets everything; called on disconnect. */
    public static void clear() {
        dex = null;
    }

    /** The local player's Pokédex, or null until the server's first snapshot arrives. */
    public static SafariDexPayload dex() {
        return dex;
    }
}
