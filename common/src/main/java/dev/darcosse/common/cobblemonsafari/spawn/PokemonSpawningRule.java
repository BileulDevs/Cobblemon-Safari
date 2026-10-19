package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;

/**
 * Reads Cobblemon's gamerule that switches wild Pokémon spawning on and off,
 * so the mod's own spawns (the daily specials) obey it like Cobblemon's.
 *
 * <p>The rule is looked up by its name among the registered gamerules
 * rather than through Cobblemon's Kotlin object, so this class compiles and
 * runs against any Cobblemon version: a missing rule is logged once and
 * treated as "spawning allowed".
 */
public final class PokemonSpawningRule {

    /**
     * Name of Cobblemon's gamerule ({@code /gamerule doPokemonSpawning false}).
     * Renamed by Cobblemon, the lookup fails and spawning is never blocked;
     * the log says so once.
     */
    private static final String RULE_NAME = "doPokemonSpawning";

    /** The rule's key once found; null before the first lookup or when it does not exist. */
    @Nullable
    private static GameRules.Key<GameRules.BooleanValue> key;
    private static boolean looked;

    private PokemonSpawningRule() {
    }

    /**
     * Whether Pokémon may spawn on this server. Gamerules are server-wide,
     * so any level's rules answer the same. Cheap: one map lookup after the
     * first call.
     */
    public static boolean allowed(MinecraftServer server) {
        GameRules.Key<GameRules.BooleanValue> rule = key();
        return rule == null || server.getGameRules().getBoolean(rule);
    }

    /** Finds the rule among every registered gamerule, once. */
    @Nullable
    private static synchronized GameRules.Key<GameRules.BooleanValue> key() {
        if (!looked) {
            looked = true;
            GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
                /** Keeps the boolean rule whose name is {@link #RULE_NAME}. */
                @Override
                public void visitBoolean(GameRules.Key<GameRules.BooleanValue> candidate,
                                         GameRules.Type<GameRules.BooleanValue> type) {
                    if (RULE_NAME.equals(candidate.getId())) {
                        key = candidate;
                    }
                }
            });
            if (key == null) {
                CobblemonSafari.LOGGER.warn("Gamerule {} not found: the Safari spawns Pokémon whatever the gamerules say",
                        RULE_NAME);
            }
        }
        return key;
    }
}
