package dev.darcosse.common.cobblemonsafari.client.music;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * A music track that fades in when it starts and fades out instead of
 * stopping dead, for the Safari's music changes (a rest house, another
 * habitat).
 *
 * <p>Why a sound of its own: vanilla's music is a SimpleSoundInstance,
 * whose volume is fixed once it plays. The sound engine reads the volume of
 * a tickable sound again every tick, so this one can ramp it. Otherwise it
 * is set up like vanilla's music (SimpleSoundInstance.forMusic): music
 * channel, no attenuation, relative to the listener, not looping.
 *
 * <p>Client only. Every music track vanilla starts is one of these
 * (SafariMusic.track), so that a track begun in the overworld can still fade
 * when a rest house's theme replaces it at the Safari's entrance. Outside the
 * Safari it starts at full volume and is stopped dead, as in vanilla.
 */
public final class FadingMusic extends AbstractTickableSoundInstance {

    /**
     * Ticks to rise from silence to full volume. Two seconds reads as a
     * gentle entrance; much shorter sounds like a cut, much longer loses
     * the track's opening.
     */
    private static final float FADE_IN_TICKS = 40.0F;

    /**
     * Ticks to fall from full volume to silence when another track takes
     * over. The next one starts once this one is silent, so the whole
     * change lasts about this plus the fade-in.
     */
    private static final float FADE_OUT_TICKS = 50.0F;

    /** Whether the track is on its way out. */
    private boolean fadingOut;

    /**
     * A track of the given music.
     *
     * @param fadeIn whether it starts silent and rises, or at full volume as vanilla's
     */
    public FadingMusic(SoundEvent event, boolean fadeIn) {
        super(event, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
        this.volume = fadeIn ? 0.0F : 1.0F;
        this.pitch = 1.0F;
        this.looping = false;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = true;
    }

    /** Starts the fade-out; the track stops by itself once silent. Calling it again changes nothing. */
    public void fadeOut() {
        fadingOut = true;
    }

    /** Steps the volume toward full, or toward silence once fading out. Called by the sound engine every tick. */
    @Override
    public void tick() {
        if (fadingOut) {
            volume = Math.max(0.0F, volume - 1.0F / FADE_OUT_TICKS);
            if (volume <= 0.0F) {
                stop();
            }
        } else if (volume < 1.0F) {
            volume = Math.min(1.0F, volume + 1.0F / FADE_IN_TICKS);
        }
    }

    /**
     * Allowed to start at volume 0: the engine skips a silent sound
     * otherwise, and this one starts silent to fade in.
     */
    @Override
    public boolean canStartSilent() {
        return true;
    }
}
