package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.bounty.BountyKind;

/**
 * The tuning of every kind of mission, one entry per {@link BountyKind}
 * ({@code bounties.kinds} in server.json). A kind not in
 * {@code bounties.allowedKinds} keeps its tuning but is never drawn.
 *
 * <p>The defaults are balanced against about 600 points per hour of hunting
 * (the {@code points} section): a mission is worth 90 to 250 points, the
 * hard ones more.
 */
public class BountyKinds {

    /**
     * Catch a given species. The count follows the species' rarity in the
     * Safari: a common species is met twice as often, so it is asked twice.
     */
    public SpeciesBounty species = new SpeciesBounty();
    /** Catch N Pokémon of a type, 3 to 5 at 30 points each. */
    public BountyKindSettings type = BountyKindSettings.of(3, 3, 5, 0, 30);
    /** Catch one of the specials of the rotation. Never drawn while the specials are off. */
    public BountyKindSettings featured = BountyKindSettings.of(2, 1, 1, 250, 0);
    /** Catch N Pokémon in a given habitat. */
    public BountyKindSettings habitat = BountyKindSettings.of(2, 3, 4, 0, 25);
    /** Catch N Pokémon, any of them: the easy one. */
    public BountyKindSettings any = BountyKindSettings.of(1, 5, 8, 0, 15);
    /** Catch N different species. */
    public BountyKindSettings different = BountyKindSettings.of(2, 3, 5, 0, 30);
    /** Catch N Pokémon without battling them. */
    public BountyKindSettings outOfBattle = BountyKindSettings.of(2, 2, 4, 0, 30);
    /** Catch N Pokémon at night. */
    public BountyKindSettings night = BountyKindSettings.of(1, 2, 3, 0, 40);
    /** Catch N fully evolved Pokémon. */
    public BountyKindSettings evolved = BountyKindSettings.of(1, 1, 2, 0, 80);
    /** Catch a Pokémon of at least a level, drawn between {@code minLevel} and {@code maxLevel}. */
    public LevelBounty level = new LevelBounty();
    /** Catch a Pokémon with its hidden ability. Off by default: rare in the wild. */
    public BountyKindSettings hiddenAbility = BountyKindSettings.of(1, 1, 1, 400, 0);
    /** Catch a shiny. Off by default: luck more than skill. */
    public BountyKindSettings shiny = BountyKindSettings.of(1, 1, 1, 1000, 0);

    /** The tuning of a kind; never null once the config is validated. */
    public BountyKindSettings of(BountyKind kind) {
        return switch (kind) {
            case SPECIES -> species;
            case TYPE -> type;
            case FEATURED -> featured;
            case HABITAT -> habitat;
            case ANY -> any;
            case DIFFERENT -> different;
            case OUT_OF_BATTLE -> outOfBattle;
            case NIGHT -> night;
            case EVOLVED -> evolved;
            case LEVEL -> level;
            case HIDDEN_ABILITY -> hiddenAbility;
            case SHINY -> shiny;
        };
    }

    /** Puts back the defaults of every entry the file left out. Returns true if anything was missing. */
    boolean fillMissing() {
        BountyKinds defaults = new BountyKinds();
        boolean missing = species == null || type == null || featured == null || habitat == null || any == null
                || different == null || outOfBattle == null || night == null || evolved == null || level == null
                || hiddenAbility == null || shiny == null;
        if (species == null) species = defaults.species;
        if (type == null) type = defaults.type;
        if (featured == null) featured = defaults.featured;
        if (habitat == null) habitat = defaults.habitat;
        if (any == null) any = defaults.any;
        if (different == null) different = defaults.different;
        if (outOfBattle == null) outOfBattle = defaults.outOfBattle;
        if (night == null) night = defaults.night;
        if (evolved == null) evolved = defaults.evolved;
        if (level == null) level = defaults.level;
        if (hiddenAbility == null) hiddenAbility = defaults.hiddenAbility;
        if (shiny == null) shiny = defaults.shiny;
        return missing;
    }

    /**
     * The species mission: how many of a species it asks by rarity, instead
     * of a drawn count.
     */
    public static class SpeciesBounty extends BountyKindSettings {
        /** Catches asked of a common species. */
        public int commonCount = 2;
        /** Catches asked of an uncommon species. */
        public int uncommonCount = 1;
        /** Catches asked of a rare species, when {@code speciesFilter.rarities} lets rare ones in. */
        public int rareCount = 1;

        /** The default species mission: 120 points, whatever the count. */
        public SpeciesBounty() {
            weight = 3;
            minCount = 1;
            maxCount = 2;
            reward = 120;
            rewardPerCatch = 0;
        }
    }

    /**
     * The level mission: the level to reach is drawn between
     * {@link #minLevel} and {@link #maxLevel}, by steps of
     * {@code SafariBounties.LEVEL_STEP}, and lowered to what the Safari's
     * species reach in the wild, so it is always possible.
     */
    public static class LevelBounty extends BountyKindSettings {
        /** Lowest level a level mission may ask. */
        public int minLevel = 20;
        /** Highest level a level mission may ask. */
        public int maxLevel = 40;

        /** The default level mission: one Pokémon, 120 points. */
        public LevelBounty() {
            weight = 1;
            minCount = 1;
            maxCount = 1;
            reward = 120;
            rewardPerCatch = 0;
        }
    }
}
