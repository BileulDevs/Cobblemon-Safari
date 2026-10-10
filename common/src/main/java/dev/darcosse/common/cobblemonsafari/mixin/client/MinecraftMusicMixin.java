package dev.darcosse.common.cobblemonsafari.mixin.client;

import dev.darcosse.common.cobblemonsafari.client.music.SafariMusic;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.Music;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the Safari choose the music in the cases a biome cannot express: a
 * rest house, and creative players. Vanilla's MusicManager asks this method
 * every tick which music should play.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMusicMixin {

    /**
     * Answers with the Safari's music when it has an opinion, otherwise lets
     * vanilla decide. {@code require = 0}: if another mod rewrote this method,
     * the Safari loses its center theme instead of the game refusing to start.
     */
    @Inject(method = "getSituationalMusic", at = @At("HEAD"), cancellable = true, require = 0)
    private void cobblemonSafari$safariMusic(CallbackInfoReturnable<Music> cir) {
        Music music = SafariMusic.override((Minecraft) (Object) this);
        if (music != null) {
            cir.setReturnValue(music);
        }
    }
}
