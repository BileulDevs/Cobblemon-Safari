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
 * The cinematic of a rest house trip, between two black bands with the
 * destination written underneath. It plays one of three scenes
 * ({@link SafariTravelPayload.Scene}):
 * <ul>
 *   <li>LANDSCAPE: the expedition trail of the wall screens, in the light
 *   of the time of day, slowly panning;</li>
 *   <li>CAVE: the Escape Rope's pixel-art escape ({@link CaveEscapeAnimation});</li>
 *   <li>FLIGHT: the world itself, through the game camera that the server
 *   points at the flying double ({@link TravelCamera}), with fades to black
 *   between the take-off and the landing. The HUD and the hand are hidden
 *   meanwhile.</li>
 * </ul>
 * It stays until the server says the trip is over, then fades out on the
 * new place.
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
     * Fade to or from black between two flight shots, in milliseconds. The
     * server sends the black a little before the take-off ends
     * (RestHouseTravel.BLACK_LEAD_TICKS) to match it.
     */
    private static final long SHOT_FADE_MS = 500L;

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

    private final SafariTravelPayload.Scene scene;
    /** The destination, in the reader's language. */
    private final Component destination;
    /** When the screen opened, in {@link Util#getMillis()}. */
    private final long openedAt;
    /** When the screen closes by itself if the end message never comes. */
    private final long deadline;
    /** When the fade out began, or -1 while still playing. */
    private long closingAt = -1L;

    // Flight only: the black between shots.
    /** Whether the current shot is black. */
    private boolean black;
    /** Darkness when the current shot began, 0 to 1, so a fade starts where the last one stood. */
    private float shotFromDarkness = 1.0F;
    /** When the current shot began. */
    private long shotAt;
    /** The player's own "hide GUI" (F1) setting, put back when the flight ends. */
    private final boolean previousHideGui;
    /** Whether the HUD is still hidden by this screen. */
    private boolean hidingGui;

    private TravelCinematicScreen(SafariTravelPayload.Scene scene, Component destination, long timeoutMillis) {
        super(Component.translatable(key("travel.cinematic.heading")));
        this.scene = scene;
        this.destination = destination;
        this.openedAt = Util.getMillis();
        this.deadline = openedAt + timeoutMillis;
        this.shotAt = openedAt;
        Minecraft minecraft = Minecraft.getInstance();
        this.previousHideGui = minecraft.options.hideGui;
        if (scene == SafariTravelPayload.Scene.FLIGHT) {
            // Hides the HUD, the hand and the block outline; screens still draw.
            minecraft.options.hideGui = true;
            hidingGui = true;
        }
    }

    /**
     * Payload handler, installed through {@code TravelClientBridge}: opens
     * the cinematic, changes the flight's shot, or starts the fade out.
     */
    public static void handle(SafariTravelPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        switch (payload.kind()) {
            case START -> {
                // Before the new screen reads the F1 setting: an old flight screen still hides the HUD.
                if (minecraft.screen instanceof TravelCinematicScreen old) {
                    old.giveViewBack();
                }
                long timeoutMillis = payload.timeoutTicks() * 1000L / Ticks.PER_SECOND;
                TravelCinematicScreen screen = new TravelCinematicScreen(payload.scene(), label(payload),
                        timeoutMillis);
                minecraft.setScreen(screen);
                if (payload.scene() == SafariTravelPayload.Scene.FLIGHT) {
                    screen.onShot(payload);
                }
            }
            case SHOT -> {
                if (minecraft.screen instanceof TravelCinematicScreen screen) {
                    screen.onShot(payload);
                }
            }
            case END -> {
                if (minecraft.screen instanceof TravelCinematicScreen screen) {
                    screen.beginClose();
                }
            }
        }
    }

    /** The destination's words, from the keys the server sent. */
    private static Component label(SafariTravelPayload payload) {
        Component biome = Component.translatable(payload.biomeKey());
        return payload.directionKey().isEmpty() ? biome
                : Component.translatable(key("travel.house_with_direction"), biome,
                Component.translatable(payload.directionKey()));
    }

    /** A new flight shot: fade to black, or point the camera and fade in on the actor. */
    private void onShot(SafariTravelPayload payload) {
        long now = Util.getMillis();
        shotFromDarkness = flightDarkness(now);
        shotAt = now;
        black = payload.actorId() == SafariTravelPayload.NO_ACTOR;
        if (!black) {
            TravelCamera.shoot(payload.actorId(), payload.camera(), payload.look());
        }
        // Black keeps the camera where it was: the fade ends on the last
        // view, and the player's eyes would show the destination too soon.
    }

    /** How dark the flight's veil is at a moment of the current shot, 0 to 1. */
    private float flightDarkness(long now) {
        float progress = Math.min(1.0F, (now - shotAt) / (float) SHOT_FADE_MS);
        float target = black ? 1.0F : 0.0F;
        return shotFromDarkness + (target - shotFromDarkness) * progress;
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

    /** Puts the camera and the HUD back, however the screen goes (closed, replaced, disconnected). */
    @Override
    public void removed() {
        giveViewBack();
        super.removed();
    }

    /** Hands the camera back to the player and restores their own F1 setting. Idempotent. */
    private void giveViewBack() {
        TravelCamera.stop();
        if (hidingGui) {
            Minecraft.getInstance().options.hideGui = previousHideGui;
            hidingGui = false;
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

    /** The scene, the bands, the words and the fade over all of it. */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float fade;
        boolean scenery;
        if (closingAt < 0) {
            scenery = true;
            fade = scene == SafariTravelPayload.Scene.FLIGHT ? flightDarkness(now)
                    : 1.0F - Math.min(1.0F, (now - openedAt) / (float) FADE_IN_MS);
        } else {
            // Two halves: the black first covers the scene, then lifts off
            // the world, where the player now stands.
            float progress = Math.min(1.0F, (now - closingAt) / (float) FADE_OUT_MS);
            scenery = progress < 0.5F;
            float from = scene == SafariTravelPayload.Scene.FLIGHT ? flightDarkness(closingAt) : 0.0F;
            fade = scenery ? from + (1.0F - from) * progress * 2.0F : (1.0F - progress) * 2.0F;
            if (!scenery) {
                // Under full black: the swap to the player's own eyes is not seen.
                giveViewBack();
            }
        }
        if (scenery) {
            drawScene(graphics, now);
            int band = Math.round(height * BAND_SHARE);
            graphics.fill(0, 0, width, band, BLACK);
            graphics.fill(0, height - band, width, height, BLACK);
            // The words stay above the flight's black, so the name reads throughout.
            drawFade(graphics, fade);
            drawWords(graphics, now, band);
        } else {
            drawFade(graphics, fade);
        }
    }

    /** What lies between the bands: a painted scene, or nothing (the world shows through). */
    private void drawScene(GuiGraphics graphics, long now) {
        switch (scene) {
            case LANDSCAPE -> {
                graphics.fill(0, 0, width, height, BLACK);
                drawLandscape(graphics, now);
            }
            case CAVE -> {
                graphics.fill(0, 0, width, height, BLACK);
                CaveEscapeAnimation.render(graphics, width, height, now - openedAt);
            }
            case FLIGHT -> {
                // The game camera films the flight behind this transparent screen.
            }
        }
    }

    /** Black over everything, at a darkness from 0 to 1. */
    private void drawFade(GuiGraphics graphics, float darkness) {
        int alpha = Math.round(Math.max(0.0F, Math.min(1.0F, darkness)) * 255.0F);
        if (alpha > 0) {
            graphics.fill(0, 0, width, height, alpha << 24);
        }
    }

    /**
     * The trail, scaled to cover the screen with {@link #PAN_CROP} of its
     * width to spare, and that window gliding back and forth across it.
     */
    private void drawLandscape(GuiGraphics graphics, long now) {
        SafariLandscapes.Scene landscape = SafariLandscapes.MISSIONS;
        float scale = Math.max(width / (landscape.width() * (1.0F - PAN_CROP)),
                height / (float) landscape.height());
        int windowWidth = Math.max(1, Math.round(width / scale));
        int windowHeight = Math.max(1, Math.round(height / scale));
        double phase = (now - openedAt) % PAN_PERIOD_MS / (double) PAN_PERIOD_MS;
        // Eased back and forth, so the pan never jerks at its ends.
        float travel = (float) (0.5D - 0.5D * Math.cos(phase * Math.PI * 2.0D));
        int u = Math.round(Math.max(0, landscape.width() - windowWidth) * travel);
        // Centred on the horizon, so the trail stays in view whatever the screen shape.
        int v = Math.round(Math.max(0, Math.min(landscape.height() - windowHeight,
                landscape.height() * landscape.horizon() - windowHeight / 2.0F)));
        graphics.blit(landscape.forTime(minecraft == null ? null : minecraft.level), 0, 0, width, height,
                u, v, windowWidth, windowHeight, landscape.width(), landscape.height());
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
