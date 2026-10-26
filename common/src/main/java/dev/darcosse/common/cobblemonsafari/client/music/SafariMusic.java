package dev.darcosse.common.cobblemonsafari.client.music;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Picks the Safari's music: the Pokémon Center theme inside a rest house,
 * the habitat's own track everywhere else, and when the player moves from
 * one to the other, a change that fades (see {@link FadingMusic}).
 *
 * <p>Vanilla only lets a biome's music finish before the next one plays,
 * so a player crossing into another habitat kept hearing the old one for
 * minutes. Here, once the player has stayed {@link #HABITAT_SWITCH_TICKS}
 * in a habitat whose track differs from the one playing, the habitat's
 * music is handed to vanilla as "replace the current music", which the
 * fade hook turns into a fade-out then a fade-in. A rest house switches at
 * once: its walls are a clear border.
 *
 * <p>Client only; {@link #override} runs every client tick through
 * MinecraftMusicMixin, {@link #track} when vanilla starts a track
 * (MusicManagerFadeMixin).
 */
public final class SafariMusic {

    /** Sound event of the Pokémon Center theme, declared in sounds.json by generate_worldgen.py. */
    public static final ResourceLocation CENTER_EVENT = CobblemonSafari.id("music.center");

    /**
     * Ticks before the center theme starts again once it ended, while the
     * player stays inside: short, so the theme loops with a breath between
     * plays. On entering, vanilla waits a random 0 to half the minimum.
     */
    private static final int CENTER_MIN_DELAY = 20;
    private static final int CENTER_MAX_DELAY = 400;

    /**
     * Ticks the player must stay in a habitat with another track before the
     * music changes. Shorter and walking along a border makes the music
     * fade back and forth; longer and the old habitat's track lingers.
     */
    private static final int HABITAT_SWITCH_TICKS = 100;

    /**
     * Vanilla waits a random 0 to half this many ticks between the end of a
     * replaced track and the start of the new one. Kept short so the new
     * habitat's track follows its fade-out without a long silence; the
     * habitat's own delays still space its plays afterwards.
     */
    private static final int SWITCH_GAP_TICKS = 20;

    /** The center theme. Replaces whatever plays on entering a rest house. */
    private static final Music CENTER = new Music(
            Holder.direct(SoundEvent.createVariableRangeEvent(CENTER_EVENT)),
            CENTER_MIN_DELAY, CENTER_MAX_DELAY, true);

    /** Each habitat music as a "replace the current music" one, built once per track. */
    private static final Map<ResourceLocation, Music> REPLACING = new HashMap<>();

    /** The track vanilla started last, whatever it is; null before the first. */
    @Nullable
    private static SoundInstance current;
    /** The habitat track waiting to take over, and the tick count from which it waits. */
    @Nullable
    private static ResourceLocation pending;
    private static int pendingTicks;

    private SafariMusic() {
    }

    /**
     * The music to play instead of vanilla's choice, or null to keep it
     * (outside the Safari, in a menu with its own music, in a habitat
     * without music). Cost: a few comparisons per rest house and a biome
     * lookup, once per client tick.
     */
    @Nullable
    public static Music override(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || !inSafari(minecraft)) {
            pending = null;
            return null;
        }
        // Menus with their own music (the credits, for instance) keep it.
        if (minecraft.screen != null && minecraft.screen.getBackgroundMusic() != null) {
            return null;
        }
        if (SafariClientState.isInRestHouse(player.getBlockX(), player.getBlockZ())) {
            pending = null;
            return CENTER;
        }
        // Read for every player: vanilla would give creative players its creative music instead.
        Music habitat = player.level().getBiome(player.blockPosition()).value().getBackgroundMusic().orElse(null);
        if (habitat == null) {
            pending = null;
            return null;
        }
        ResourceLocation track = habitat.getEvent().value().getLocation();
        boolean otherPlaying = current != null && minecraft.getSoundManager().isActive(current)
                && !current.getLocation().equals(track);
        if (!otherPlaying) {
            pending = null;
            return habitat;
        }
        if (!track.equals(pending)) {
            pending = track;
            pendingTicks = 0;
        }
        if (++pendingTicks < HABITAT_SWITCH_TICKS) {
            // Not yet: vanilla keeps the track in progress, as the habitat does not replace it.
            return habitat;
        }
        return REPLACING.computeIfAbsent(track, ignored ->
                new Music(habitat.getEvent(), SWITCH_GAP_TICKS, habitat.getMaxDelay(), true));
    }

    /**
     * The track to play for the one vanilla built: a fading copy, rising
     * from silence in the Safari and at full volume elsewhere. A copy even
     * outside, so that a track begun in the overworld can fade out when the
     * Safari's music replaces it (a rest house at the entrance). Remembered,
     * so that {@link #override} knows what is playing.
     */
    public static SoundInstance track(SoundInstance built) {
        if (built == null) {
            return null;
        }
        current = new FadingMusic(SoundEvent.createVariableRangeEvent(built.getLocation()),
                inSafari(Minecraft.getInstance()));
        return current;
    }

    /**
     * Whether a replaced track fades out rather than stopping dead: in the
     * Safari only, so that music elsewhere behaves exactly as in vanilla.
     */
    public static boolean fadesHere() {
        return inSafari(Minecraft.getInstance());
    }

    /** Whether the local player is in the Safari dimension. */
    private static boolean inSafari(Minecraft minecraft) {
        ResourceLocation safari = SafariClientState.safariDimension();
        return minecraft.player != null && safari != null
                && minecraft.player.level().dimension().location().equals(safari);
    }
}
