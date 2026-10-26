package dev.darcosse.common.cobblemonsafari.command;

import com.cobblemon.mod.common.api.molang.function.PlayerMoLangFunctions;
import dev.darcosse.common.cobblemonsafari.bounty.SafariBounties;
import dev.darcosse.common.cobblemonsafari.item.EscapeRopeActions;
import dev.darcosse.common.cobblemonsafari.structure.Compass;
import dev.darcosse.common.cobblemonsafari.teleport.RestHouseTravel;
import kotlin.jvm.functions.Function1;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The Safari's actions as MoLang functions of the player, for NPC dialogues:
 * {@code q.player.safari_shop()}, {@code safari_bounties()},
 * {@code safari_gate()}, {@code safari_leave()}, {@code safari_status()},
 * {@code safari_today()}, {@code safari_balls()}, and the Escape Rope's
 * {@code safari_rope_can_climb()}, {@code safari_rope_climb()},
 * {@code safari_rope_can_rest_house()} and {@code safari_rope_rest_house()},
 * and the rest house guide's
 * {@code safari_guide_can_<direction>()} and {@code safari_guide_go_<direction>()}
 * for each of the eight {@link Compass} directions ({@code safari_guide_go_north_east()}),
 * and the clerk's mission swap: {@code safari_bounty_can_swap()}, then
 * {@code safari_bounty_can_swap_<slot>()} and {@code safari_bounty_swap_<slot>()}
 * for each mission, from 0 ({@code safari_bounty_swap_0()}).
 *
 * <p>Why not commands: a dialogue running a command in the player's name
 * ({@code q.player.run_command}) needs a command every player may type.
 * Functions are only reachable from dialogues, so players see a single
 * {@code /safari}.
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
    public static final String BALLS = "safari_balls";
    /** Whether the rope can climb out from here; the rope dialogue shows that option only then. */
    public static final String ROPE_CAN_CLIMB = "safari_rope_can_climb";
    public static final String ROPE_CLIMB = "safari_rope_climb";
    public static final String ROPE_REST_HOUSE = "safari_rope_rest_house";
    /** Whether the rope offers the nearest rest house (restHouseTravel.ropeEnabled). */
    public static final String ROPE_CAN_REST_HOUSE = "safari_rope_can_rest_house";
    /**
     * Prefixes of the guide's functions, followed by a {@link Compass#id()}.
     * One function per direction rather than one taking the direction as a
     * parameter: the parameters' type comes from the MoLang library, which
     * is not on this module's compile classpath (see {@link #register}).
     */
    public static final String GUIDE_CAN_PREFIX = "safari_guide_can_";
    public static final String GUIDE_GO_PREFIX = "safari_guide_go_";
    /** Whether the clerk offers to swap a mission at all; the greeting shows that answer only then. */
    public static final String BOUNTY_CAN_SWAP = "safari_bounty_can_swap";
    /**
     * Prefixes of the swap functions, followed by the mission's slot from 0;
     * one function per slot for the same reason as the guide's.
     */
    public static final String BOUNTY_CAN_SWAP_PREFIX = "safari_bounty_can_swap_";
    public static final String BOUNTY_SWAP_PREFIX = "safari_bounty_swap_";

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
        functions.put(BALLS, params -> run(player, SafariPlayerActions::showAllowedBalls));
        functions.put(ROPE_CAN_CLIMB, params -> run(player, EscapeRopeActions::canClimb));
        functions.put(ROPE_CLIMB, params -> run(player, EscapeRopeActions::climb));
        functions.put(ROPE_REST_HOUSE, params -> run(player, EscapeRopeActions::toRestHouse));
        functions.put(ROPE_CAN_REST_HOUSE, params -> run(player, EscapeRopeActions::canGoToRestHouse));
        for (Compass direction : Compass.values()) {
            functions.put(GUIDE_CAN_PREFIX + direction.id(),
                    params -> run(player, p -> RestHouseTravel.canTravelTo(p, direction)));
            functions.put(GUIDE_GO_PREFIX + direction.id(),
                    params -> run(player, p -> RestHouseTravel.travelTo(p, direction)));
        }
        functions.put(BOUNTY_CAN_SWAP, params -> run(player, SafariBounties::canSwapAny));
        for (int slot = 0; slot < SafariBounties.MAX_MISSIONS; slot++) {
            int index = slot;
            functions.put(BOUNTY_CAN_SWAP_PREFIX + slot, params -> run(player, p -> SafariBounties.canSwap(p, index)));
            functions.put(BOUNTY_SWAP_PREFIX + slot, params -> run(player, p -> SafariBounties.swap(p, index)));
        }
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
