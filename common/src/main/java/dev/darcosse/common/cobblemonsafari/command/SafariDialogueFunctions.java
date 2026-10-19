package dev.darcosse.common.cobblemonsafari.command;

import com.cobblemon.mod.common.api.molang.function.PlayerMoLangFunctions;
import kotlin.jvm.functions.Function1;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The Safari's actions as MoLang functions of the player, for NPC dialogues:
 * {@code q.player.safari_shop()}, {@code safari_bounties()},
 * {@code safari_gate()}, {@code safari_leave()}, {@code safari_status()} and
 * {@code safari_today()}.
 *
 * <p>Why not commands: the dialogues used to run {@code /safari shop} and the
 * like in the player's name ({@code q.player.run_command}), which had to stay
 * commands every player could type. Functions are only reachable from
 * dialogues, so players see a single {@code /safari}.
 *
 * <p>Added to Cobblemon's {@code PlayerMoLangFunctions.custom}, the list it
 * merges into every player's MoLang struct (checked in the Cobblemon 1.8.0
 * sources: {@code AbstractMoLangFunctionHolder.attach}). Each function
 * returns true (1) when the action went through, false (0) otherwise; it does
 * nothing for a client-side player.
 */
public final class SafariDialogueFunctions {

    /** Function names, as datapacks call them: {@code q.player.<name>()}. Renaming one breaks existing dialogues. */
    public static final String SHOP = "safari_shop";
    public static final String BOUNTIES = "safari_bounties";
    public static final String GATE = "safari_gate";
    public static final String LEAVE = "safari_leave";
    public static final String STATUS = "safari_status";
    public static final String TODAY = "safari_today";

    private static boolean registered;

    private SafariDialogueFunctions() {
    }

    /**
     * Adds the functions to Cobblemon's player struct. Called once from the
     * common initialisation; a second call does nothing.
     *
     * <p>The list is typed in Kotlin with the MoLang library's parameter
     * type, which is not on this module's compile classpath: it is filled
     * raw, the parameters being ignored anyway.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        List custom = PlayerMoLangFunctions.INSTANCE.getCustom();
        custom.add((Function1<Object, Map<String, Function1<Object, Object>>>) SafariDialogueFunctions::functionsFor);
    }

    /** The functions of one player's struct. */
    private static Map<String, Function1<Object, Object>> functionsFor(Object player) {
        Map<String, Function1<Object, Object>> functions = new HashMap<>();
        functions.put(SHOP, params -> run(player, SafariPlayerActions::openShop));
        functions.put(BOUNTIES, params -> run(player, SafariPlayerActions::showBounties));
        functions.put(GATE, params -> run(player, SafariPlayerActions::enterThroughGate));
        functions.put(LEAVE, params -> run(player, SafariPlayerActions::leave));
        functions.put(STATUS, params -> run(player, SafariPlayerActions::sendStatus));
        functions.put(TODAY, params -> run(player, SafariPlayerActions::showToday));
        return functions;
    }

    /**
     * Runs an action for a server-side player. Returned as a Boolean, which
     * the MoLang runtime turns into 1 or 0 (MoValue.of).
     */
    private static Object run(Object player, Predicate<ServerPlayer> action) {
        return player instanceof ServerPlayer serverPlayer && action.test(serverPlayer);
    }
}
