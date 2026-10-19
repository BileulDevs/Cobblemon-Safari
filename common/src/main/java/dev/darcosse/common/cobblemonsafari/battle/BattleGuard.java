package dev.darcosse.common.cobblemonsafari.battle;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleStartedEvent;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Optionally forbids battles inside the Safari, as in the original games.
 *
 * <p>Off by default ({@code allowBattles = true}): forbidding battles removes
 * the usual way of weakening a Pokémon before throwing, which changes the feel
 * of Cobblemon captures a lot. The config value is read at event time, so a
 * reload applies immediately.
 */
public final class BattleGuard {

    private BattleGuard() {
    }

    /** Subscribes to battle start. Called once from common init. */
    public static void register() {
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.HIGHEST, BattleGuard::onBattleStart);
    }

    /** Cancels a battle involving a player inside the Safari, when the config forbids battles there. */
    private static void onBattleStart(BattleStartedEvent.Pre event) {
        if (SafariConfigManager.get().allowBattles) {
            return;
        }
        for (ServerPlayer player : event.getBattle().getPlayers()) {
            if (SafariDimensions.isSafari(player.level())) {
                event.setReason(Component.translatable(CobblemonSafari.MOD_ID + ".message.battle_not_allowed"));
                event.cancel();
                return;
            }
        }
    }
}
