package dev.darcosse.common.cobblemonsafari.client.travel;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.client.SafariLandscapes;
import dev.darcosse.common.cobblemonsafari.network.SafariTravelPayload;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The short loading cinematic of a rest house trip: the expedition trail of
 * the wall screens, in the light of the time of day, slowly panning between
 * two black bands, with the destination written underneath. It fades in,
 * stays until the server says the player arrived (or the trip failed), then
 * fades out on the new place.
 *
 * <p>A screen rather than an overlay so the player cannot walk away from the
 * spot the server is about to move them from; Escape does not close it. It
 * still closes on its own after the timeout the server gave, should the end
 * message never come.
 *
 * <p>Client only.
 */
public final class TravelCinematicScreen extends Screen {

    /** Fade from black at the start and back to the world at the end, in milliseconds. */
    private static final long FADE_IN_MS = 600L;
    private static final long FADE_OUT_MS = 700L;

    /**
     * Time for one full pan across the picture and back, in milliseconds.
     * Shorter reads as scrolling rather than travelling.
     */
    private static final long PAN_PERIOD_MS = 24_000L;

    /**
     * Share of the picture's width hidden at any time, which the pan moves
     * across. Larger pans further but crops more of the scene.
     */
    private static final float PAN_CROP = 0.25F;

    /** Height of each black band, as a share of the screen height. */
    private static final float BAND_SHARE = 0.12F;

    /** Time for the dots after the heading to cycle, in milliseconds. */
    private static final long DOTS_PERIOD_MS = 1_200L;
    private static final int MAX_DOTS = 3;

    /** Gap between the heading and the destination, in GUI pixels. */
    private static final int LINE_GAP = 4;

    private static final int BLACK = 0xFF000000;
    private static final int HEADING_COLOR = 0xFFD8D8D8;
    private static final int DESTINATION_COLOR = 0xFFFFB000;

    /** The destination, in the reader's language. */
    private final Component destination;
    /** When the screen opened, in {@link Util#getMillis()}. */
    private final long openedAt;
    /** When the screen closes by itself if the end message never comes. */
    private final long deadline;
    /** When the fade out began, or -1 while still waiting. */
    private long closingAt = -1L;

    private TravelCinematicScreen(Component destination, long timeoutMillis) {
        super(Component.translatable(key("travel.cinematic.heading")));
        this.destination = destination;
        this.openedAt = Util.getMillis();
        this.deadline = openedAt + timeoutMillis;
    }

    /**
     * Payload handler, installed through {@code TravelClientBridge}: opens the
     * cinematic, or starts its fade out.
     */
    public static void handle(SafariTravelPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.starting()) {
            long timeoutMillis = payload.timeoutTicks() * 1000L / Ticks.PER_SECOND;
            minecraft.setScreen(new TravelCinematicScreen(label(payload), timeoutMillis));
        } else if (minecraft.screen instanceof TravelCinematicScreen screen) {
            screen.beginClose();
        }
    }

    /** The destination's words, from the keys the server sent. */
    private static Component label(SafariTravelPayload payload) {
        Component biome = Component.translatable(payload.biomeKey());
        return payload.directionKey().isEmpty() ? biome
                : Component.translatable(key("travel.house_with_direction"), biome,
                Component.translatable(payload.directionKey()));
    }

    /** Starts the fade out; the screen closes once it is over. */
    private void beginClose() {
        if (closingAt < 0) {
            closingAt = Util.getMillis();
        }
    }

    /** Closes after the fade out, or begins it when the server never answered. */
    @Override
    public void tick() {
        long now = Util.getMillis();
        if (closingAt < 0 && now > deadline) {
            beginClose();
        }
        if (closingAt >= 0 && now - closingAt >= FADE_OUT_MS) {
            onClose();
        }
    }

    /** Only the server ends a trip. */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /** The world keeps running: the server moves the player meanwhile. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Nothing: vanilla's blur would show through the fades. Everything is drawn in {@link #render}. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /** The picture, the bands, the words and the fade over all of it. */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float fade;
        boolean picture;
        if (closingAt < 0) {
            picture = true;
            fade = 1.0F - Math.min(1.0F, (now - openedAt) / (float) FADE_IN_MS);
        } else {
            // Two halves: the black first covers the picture, then lifts off
            // the world, where the player now stands.
            float progress = Math.min(1.0F, (now - closingAt) / (float) FADE_OUT_MS);
            picture = progress < 0.5F;
            fade = picture ? progress * 2.0F : (1.0F - progress) * 2.0F;
        }
        if (picture) {
            graphics.fill(0, 0, width, height, BLACK);
            drawLandscape(graphics, now);
            int band = Math.round(height * BAND_SHARE);
            graphics.fill(0, 0, width, band, BLACK);
            graphics.fill(0, height - band, width, height, BLACK);
            drawWords(graphics, now, band);
        }
        int alpha = Math.round(Math.max(0.0F, Math.min(1.0F, fade)) * 255.0F);
        if (alpha > 0) {
            graphics.fill(0, 0, width, height, alpha << 24);
        }
    }

    /**
     * The trail, scaled to cover the screen with {@link #PAN_CROP} of its
     * width to spare, and that window gliding back and forth across it.
     */
    private void drawLandscape(GuiGraphics graphics, long now) {
        SafariLandscapes.Scene scene = SafariLandscapes.MISSIONS;
        float scale = Math.max(width / (scene.width() * (1.0F - PAN_CROP)), height / (float) scene.height());
        int windowWidth = Math.max(1, Math.round(width / scale));
        int windowHeight = Math.max(1, Math.round(height / scale));
        double phase = (now - openedAt) % PAN_PERIOD_MS / (double) PAN_PERIOD_MS;
        // Eased back and forth, so the pan never jerks at its ends.
        float travel = (float) (0.5D - 0.5D * Math.cos(phase * Math.PI * 2.0D));
        int u = Math.round(Math.max(0, scene.width() - windowWidth) * travel);
        // Centred on the horizon, so the trail stays in view whatever the screen shape.
        int v = Math.round(Math.max(0, Math.min(scene.height() - windowHeight,
                scene.height() * scene.horizon() - windowHeight / 2.0F)));
        graphics.blit(scene.forTime(minecraft == null ? null : minecraft.level), 0, 0, width, height,
                u, v, windowWidth, windowHeight, scene.width(), scene.height());
    }

    /** "On the way to..." and the destination, in the lower band. */
    private void drawWords(GuiGraphics graphics, long now, int band) {
        int dots = (int) ((now - openedAt) % DOTS_PERIOD_MS * (MAX_DOTS + 1) / DOTS_PERIOD_MS);
        Component heading = Component.literal(title.getString() + ".".repeat(dots));
        int lineHeight = font.lineHeight;
        int top = height - band + (band - lineHeight * 2 - LINE_GAP) / 2;
        graphics.drawCenteredString(font, heading, width / 2, top, HEADING_COLOR);
        graphics.drawCenteredString(font, destination, width / 2, top + lineHeight + LINE_GAP, DESTINATION_COLOR);
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
