package dev.darcosse.common.cobblemonsafari.capture;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokeball.ThrownPokeballHitEvent;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.loan.LoanedBalls;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Set;

/**
 * Enforces the Safari ball restriction.
 *
 * <p>Two layers, and the order matters:
 * <ol>
 *   <li>{@link #shouldBlockUse} stops the throw itself. This is the real guard.
 *   Cobblemon attaches an in-battle capture to the battle and forces the
 *   thrower's turn to pass BEFORE it fires {@code THROWN_POKEBALL_HIT}
 *   (see {@code EmptyPokeBallEntity.onHitEntity}); cancelling at hit time in a
 *   battle would therefore leave a dangling capture action and waste the turn.</li>
 *   <li>The {@code THROWN_POKEBALL_HIT} subscription is a safety net for balls
 *   that reach a Pokémon without going through an item use (other mods,
 *   dispensers). Outside battle it is clean: Cobblemon drops the ball back.</li>
 * </ol>
 */
public final class CaptureGuard {

    private CaptureGuard() {
    }

    /** Subscribes the hit-time safety net. Called once from common init. */
    public static void register() {
        // HIGHEST so a forbidden ball is rejected before any other addon reacts to the capture.
        CobblemonEvents.THROWN_POKEBALL_HIT.subscribe(Priority.HIGHEST, CaptureGuard::onBallHit);
    }

    /**
     * Decides whether using an item must be refused, and tells the player why.
     *
     * <p>Called by both loaders from their item-use event, on BOTH logical sides:
     * the server decides from its config, the client from the rules the server
     * sent it. Refusing on the client too keeps the predicted inventory in sync.
     *
     * @return true when the use must be cancelled
     */
    public static boolean shouldBlockUse(Player player, ItemStack stack) {
        ResourceLocation ballId = BallRules.ballId(stack);
        if (ballId == null) {
            return false;
        }
        Level level = player.level();
        boolean inSafari;
        Set<ResourceLocation> allowed;
        if (level.isClientSide()) {
            ResourceLocation safari = SafariClientState.safariDimension();
            allowed = SafariClientState.allowedBalls();
            inSafari = safari != null && level.dimension().location().equals(safari);
            // No rules yet: let the server decide rather than guess.
            if (allowed == null) {
                return false;
            }
        } else {
            inSafari = SafariDimensions.isSafari(level);
            allowed = SafariConfigManager.allowedBalls();
        }
        if (!inSafari) {
            // A loaned ball is the park's property: thrown outside it would be a
            // free Safari Ball, and the 1-second reclaim sweep leaves a window.
            if (LoanedBalls.isLoaned(stack)) {
                player.displayClientMessage(
                        Component.translatable(CobblemonSafari.MOD_ID + ".message.loaned_ball_outside"), true);
                return true;
            }
            return false;
        }
        if (BallRules.isAllowed(ballId, allowed)) {
            return false;
        }
        player.displayClientMessage(notAllowedMessage(stack), true);
        return true;
    }

    /** Cancels a forbidden ball when it reaches a Pokémon in the Safari (safety net). */
    private static void onBallHit(ThrownPokeballHitEvent event) {
        EmptyPokeBallEntity ball = event.getPokeBall();
        if (!SafariDimensions.isSafari(event.getPokemon().level())) {
            return;
        }
        if (BallRules.isAllowed(ball.getPokeBall().getName(), SafariConfigManager.allowedBalls())) {
            return;
        }
        event.cancel();
        Entity owner = ball.getOwner();
        if (owner instanceof Player player) {
            player.displayClientMessage(notAllowedMessage(ball.getPokeBall().item().getDefaultInstance()), true);
        }
    }

    /** Action-bar message naming the refused ball. */
    private static Component notAllowedMessage(ItemStack stack) {
        return Component.translatable(CobblemonSafari.MOD_ID + ".message.ball_not_allowed", stack.getHoverName());
    }
}
