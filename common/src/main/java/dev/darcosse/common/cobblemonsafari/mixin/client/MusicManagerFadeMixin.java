package dev.darcosse.common.cobblemonsafari.mixin.client;

import dev.darcosse.common.cobblemonsafari.client.music.FadingMusic;
import dev.darcosse.common.cobblemonsafari.client.music.SafariMusic;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes the Safari's music changes fade ({@link FadingMusic}) instead of
 * cutting: the music of a rest house or of another habitat no longer stops
 * the track in progress dead.
 *
 * <p>Two hooks in vanilla's MusicManager:
 * <ul>
 *   <li>startPlaying: the track it builds is swapped for a fading one
 *       (rising from silence in the Safari, at full volume elsewhere);</li>
 *   <li>tick: when a new music replaces the current one, vanilla stops the
 *       current track; a fading track fades out instead. Vanilla keeps
 *       waiting while it is still audible and starts the new one once it
 *       has stopped, so the change is a fade-out then a fade-in.</li>
 * </ul>
 * Outside the Safari a track is started at full volume and stopped dead,
 * exactly as in vanilla.
 *
 * <p>Why a shadowed field and redirects: vanilla builds its track with
 * SimpleSoundInstance.forMusic, whose return type a fading (tickable) sound
 * cannot have, so the swap is done on the write of the field that holds
 * it. A shadowed field is remapped reliably on Fabric, unlike a generic
 * shadowed method (see ScreenDialogueOptionsMixin).
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerFadeMixin {

    /**
     * The JVM's PUTFIELD opcode (0xB5), what {@code @At("FIELD")} needs to
     * pick the field's writes. Written as a number: ASM's Opcodes, where it is
     * named, is not on the common module's compile classpath.
     */
    private static final int PUTFIELD = 181;

    @Shadow
    private SoundInstance currentMusic;

    /**
     * The track vanilla just built, or a fading one in its place.
     * {@code require = 0}: if another mod rewrote this method, music simply
     * cuts as in vanilla.
     */
    @Redirect(method = "startPlaying",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/sounds/MusicManager;currentMusic:Lnet/minecraft/client/resources/sounds/SoundInstance;",
                    opcode = PUTFIELD),
            require = 0)
    private void cobblemonSafari$fadingTrack(MusicManager self, SoundInstance built) {
        currentMusic = SafariMusic.track(built);
    }

    /** Fades the replaced track out instead of stopping it. Same {@code require = 0} as above. */
    @Redirect(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundManager;stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V"),
            require = 0)
    private void cobblemonSafari$fadeOut(SoundManager sounds, SoundInstance track) {
        if (track instanceof FadingMusic fading && SafariMusic.fadesHere()) {
            fading.fadeOut();
        } else {
            sounds.stop(track);
        }
    }
}
