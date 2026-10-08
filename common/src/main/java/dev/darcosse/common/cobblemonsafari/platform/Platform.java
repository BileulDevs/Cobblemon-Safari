package dev.darcosse.common.cobblemonsafari.platform;

/**
 * Holds the {@link PlatformAdapter} provided by the running loader.
 */
public final class Platform {

    private static PlatformAdapter adapter;

    private Platform() {
    }

    /** Installs the loader implementation; called once from {@code CobblemonSafari.init}. */
    public static void set(PlatformAdapter platformAdapter) {
        adapter = platformAdapter;
    }

    /**
     * Returns the loader implementation.
     *
     * @throws IllegalStateException if called before initialisation, which always
     *         means a loader entry point forgot to call {@code CobblemonSafari.init}.
     */
    public static PlatformAdapter get() {
        if (adapter == null) {
            throw new IllegalStateException(
                    "PlatformAdapter not initialized: CobblemonSafari.init(adapter) was never called.");
        }
        return adapter;
    }
}
