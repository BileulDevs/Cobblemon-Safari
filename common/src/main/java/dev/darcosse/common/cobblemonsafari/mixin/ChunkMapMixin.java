package dev.darcosse.common.cobblemonsafari.mixin;

import dev.darcosse.common.cobblemonsafari.reset.SafariMapReset;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives the Safari dimension its own terrain seed after each map reset.
 *
 * <p>Every dimension generates from the world seed, read once in the chunk
 * map's constructor to build the noise state; no data pack setting can change
 * it per dimension. Redirecting that single read is the narrowest way to make
 * the regenerated Safari differ from the previous one. Other levels get the
 * world seed unchanged.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    /** Mixes the current map salt into the seed when the level is the Safari. */
    @Redirect(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getSeed()J"))
    private long cobblemonSafari$safariSeed(ServerLevel level) {
        return SafariMapReset.seedFor(level, level.getSeed());
    }
}
