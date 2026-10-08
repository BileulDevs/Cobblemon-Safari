package dev.darcosse.common.cobblemonsafari.mixin;

import dev.darcosse.common.cobblemonsafari.protection.SafariProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops fire from spreading or burning blocks inside the Safari.
 *
 * <p>A mixin is the only option: fire spread is governed by the
 * {@code doFireTick} gamerule, which is server-wide, and neither Fabric nor
 * NeoForge exposes a per-dimension fire event. All spreading and burning
 * happens in {@code FireBlock.tick}, scheduled 30 to 40 ticks after the fire
 * appears; removing the fire there means it flickers for about two seconds
 * and never touches anything. Soul fire is a different block and never spreads.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    /** Removes the fire instead of letting it tick when it burns inside the Safari. */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cobblemonSafari$extinguishInSafari(BlockState state, ServerLevel level, BlockPos pos,
                                                   RandomSource random, CallbackInfo ci) {
        if (SafariProtection.shouldExtinguishFire(level)) {
            level.removeBlock(pos, false);
            ci.cancel();
        }
    }
}
