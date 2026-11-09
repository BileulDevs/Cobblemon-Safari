package dev.darcosse.common.cobblemonsafari.reset;

/**
 * When a due map reset happens ({@code reset.mode}).
 */
public enum ResetMode {
    /** At the next server start only, before the levels load: no work while the server runs. */
    START,
    /**
     * While the server runs, as soon as the reset is due and the Safari is
     * empty, or after {@code reset.maxWaitMinutes} with a countdown for the
     * players inside. A due reset still happens at start when the server
     * was down at the time.
     */
    LIVE
}
