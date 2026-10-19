package dev.darcosse.common.cobblemonsafari.gate;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleStartedEvent;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.access.EntryResult;
import dev.darcosse.common.cobblemonsafari.block.SafariGateBlock;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.credit.SafariCredit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Entering the Safari through a Safari Gate: by standing in its pane until
 * the portal effect completes, or by asking its warden (whose dialogue calls
 * {@code q.player.safari_gate()}, see command.SafariDialogueFunctions).
 *
 * <p>Both ways spend banked time ({@link SafariCredit}) and apply the entry
 * requirements. They are the only way in for players; operators also have
 * {@code /safari enter}. Server side only.
 */
public final class SafariGate {

    /**
     * How far from the pane, in blocks, a player is moved just before entering.
     * The return point is taken from where they stand when the hunt starts:
     * left inside the pane, a player coming back would start the portal
     * countdown again and be sent straight back in. Must exceed half a block
     * plus half a player width (0.8).
     */
    private static final double STEP_OUT_DISTANCE = 1.1D;

    /**
     * Range, in blocks, within which the warden's entry finds a gate. Covers
     * the warden's spot next to the gate with room to spare; much more would
     * let players enter from places that are not visibly a gate.
     */
    private static final int COMMAND_RANGE = 10;

    private SafariGate() {
    }

    /** Subscribes to battle start, to protect the warden's Pokémon. Called once from common init. */
    public static void register() {
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.HIGH, SafariGate::onBattleStart);
    }

    /**
     * The portal countdown of a player standing in a gate pane completed:
     * sends them in if they have banked time, otherwise tells them why and
     * leaves them where they are.
     */
    public static void onPortalCompleted(ServerPlayer player, BlockState state, BlockPos pos) {
        if (!(state.getBlock() instanceof SafariGateBlock)) {
            // The pane was removed during the countdown.
            return;
        }
        if (!SafariConfigManager.get().gate.allowEntry) {
            player.displayClientMessage(Component.translatable(key("message.gate_closed")), true);
            return;
        }
        Vec3 inPane = player.position();
        stepOut(player, state, pos);
        EntryResult result = SafariCredit.enter(player, false);
        if (!result.success()) {
            // Put back exactly where they stood: a refusal must not move anyone.
            player.teleportTo(inPane.x, inPane.y, inPane.z);
            if (result.reason() != null) {
                player.displayClientMessage(result.reason(), true);
            }
        }
    }

    /**
     * Enters through the gate the player stands next to; what the warden's
     * dialogue calls.
     */
    public static EntryResult enterNearby(ServerPlayer player) {
        if (!SafariConfigManager.get().gate.allowEntry) {
            return EntryResult.denied(Component.translatable(key("message.gate_closed")));
        }
        if (!isNearGate(player)) {
            return EntryResult.denied(Component.translatable(key("command.gate.too_far")));
        }
        return SafariCredit.enter(player, false);
    }

    /**
     * Whether any gate block lies within {@link #COMMAND_RANGE}. Up to 21³
     * block reads, only when a gate command is used. Also what keeps the
     * warden's shop at the warden.
     */
    public static boolean isNearGate(ServerPlayer player) {
        BlockPos centre = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-COMMAND_RANGE, -COMMAND_RANGE, -COMMAND_RANGE),
                centre.offset(COMMAND_RANGE, COMMAND_RANGE, COMMAND_RANGE))) {
            if (player.level().getBlockState(pos).getBlock() instanceof SafariGateBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves the player just outside the pane, on the side they stand on.
     * Riding players never get here: vanilla does not let passengers use
     * portals.
     */
    private static void stepOut(ServerPlayer player, BlockState state, BlockPos pos) {
        Direction.Axis across = state.getValue(SafariGateBlock.AXIS) == Direction.Axis.X
                ? Direction.Axis.Z : Direction.Axis.X;
        double paneCentre = (across == Direction.Axis.X ? pos.getX() : pos.getZ()) + 0.5D;
        double playerCoord = across == Direction.Axis.X ? player.getX() : player.getZ();
        // Exactly on the plane (teleported in by a command): pick a side rather than stay stuck.
        double side = playerCoord < paneCentre ? -1.0D : 1.0D;
        double target = paneCentre + side * STEP_OUT_DISTANCE;
        if (across == Direction.Axis.X) {
            player.teleportTo(target, player.getY(), player.getZ());
        } else {
            player.teleportTo(player.getX(), player.getY(), target);
        }
    }

    /** Cancels any battle against one of the gate warden's roaming Pokémon. */
    private static void onBattleStart(BattleStartedEvent.Pre event) {
        for (BattleActor actor : event.getBattle().getActors()) {
            for (BattlePokemon pokemon : actor.getPokemonList()) {
                PokemonEntity entity = pokemon.getEntity();
                if (entity != null && GateResidents.isCompanion(entity)) {
                    event.setReason(Component.translatable(key("message.gate_companion_battle")));
                    event.cancel();
                    return;
                }
            }
        }
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
