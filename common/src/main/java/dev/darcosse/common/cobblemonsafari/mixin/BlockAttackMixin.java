package dev.darcosse.common.cobblemonsafari.mixin;

import dev.darcosse.common.cobblemonsafari.protection.SafariProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@code protection.interact.apricorns} cover the left click too:
 * Cobblemon's apricorns are picked by a left click as well as a right one,
 * in {@code ApricornBlock.attack}, which neither loader's break event sees.
 * See {@link SafariProtection#shouldBlockAttack}.
 *
 * <p>On the block state's {@code attack}, the one call both the server and
 * the client make when a player starts hitting a block, rather than on
 * Cobblemon's Kotlin class, whose methods are less stable to target.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockAttackMixin {

    /** Drops the hit before the block reacts to it, when the Safari forbids it. */
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void cobblemonSafari$protectHarvest(Level level, BlockPos pos, Player player, CallbackInfo ci) {
        if (SafariProtection.shouldBlockAttack(player, (BlockState) (Object) this)) {
            ci.cancel();
        }
    }
}
