package dev.darcosse.common.cobblemonsafari.config;

/**
 * The tuning of one kind of mission: how often it is drawn, how many
 * catches it asks, and what it pays (a flat {@link #reward} plus
 * {@link #rewardPerCatch} for each catch asked).
 */
public class BountyKindSettings {
    /**
     * How often this kind is drawn, against the other allowed kinds' weights.
     * 0 never draws it, as leaving it out of {@code allowedKinds} does.
     */
    public int weight = 1;
    /** Fewest catches a mission of this kind asks. At least 1. */
    public int minCount = 1;
    /** Most catches a mission of this kind asks. At least {@link #minCount}. */
    public int maxCount = 1;
    /** Points paid on completion, whatever the count. */
    public int reward = 0;
    /** Points paid on completion for each catch the mission asked. */
    public int rewardPerCatch = 0;

    /** Gson's constructor. */
    public BountyKindSettings() {
    }

    /** A tuning for the defaults. */
    static BountyKindSettings of(int weight, int minCount, int maxCount, int reward, int rewardPerCatch) {
        BountyKindSettings settings = new BountyKindSettings();
        settings.weight = weight;
        settings.minCount = minCount;
        settings.maxCount = maxCount;
        settings.reward = reward;
        settings.rewardPerCatch = rewardPerCatch;
        return settings;
    }

    /** What a mission asking {@code count} catches pays. */
    public int rewardFor(int count) {
        return reward + rewardPerCatch * count;
    }
}
