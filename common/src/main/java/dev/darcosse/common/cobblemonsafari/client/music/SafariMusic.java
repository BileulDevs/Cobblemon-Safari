package dev.darcosse.common.cobblemonsafari.client.music;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Picks the Safari's music where the biome alone cannot: inside a rest house
 * (the Pokémon Center theme) and for creative players (who would otherwise
 * hear vanilla's creative music instead of the Safari's).
 *
 * <p>Everywhere else in the Safari, each biome's own {@code music} field
 * already plays its zone track; this class leaves vanilla's choice alone.
 * Client only; called every client tick through {@code MinecraftMusicMixin}.
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
     * Replaces whatever plays on entering a rest house, like a building theme
     * in the games. Leaving does not cut it: the zone tracks do not replace
     * the current music, so the theme finishes outside.
     */
    private static final Music CENTER = new Music(
            Holder.direct(SoundEvent.createVariableRangeEvent(CENTER_EVENT)),
            CENTER_MIN_DELAY, CENTER_MAX_DELAY, true);

    private SafariMusic() {
    }

    /**
     * The music to play instead of vanilla's choice, or null to keep it. A
     * few comparisons per rest house; called once per client tick.
     */
    @Nullable
    public static Music override(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return null;
        }
        // Menus with their own music (the credits, for instance) keep it.
        if (minecraft.screen != null && minecraft.screen.getBackgroundMusic() != null) {
            return null;
        }
        ResourceLocation safari = SafariClientState.safariDimension();
        if (safari == null || !player.level().dimension().location().equals(safari)) {
            return null;
        }
        if (SafariClientState.isInRestHouse(player.getBlockX(), player.getBlockZ())) {
            return CENTER;
        }
        if (player.getAbilities().instabuild && player.getAbilities().mayfly) {
            return player.level().getBiome(player.blockPosition()).value().getBackgroundMusic().orElse(null);
        }
        return null;
    }
}
