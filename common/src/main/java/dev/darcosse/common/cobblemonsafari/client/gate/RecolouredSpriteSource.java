package dev.darcosse.common.cobblemonsafari.client.gate;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Atlas source that adds a recoloured copy of another texture, animation
 * included: the Safari Gate's pane is the nether portal's texture with its
 * hue turned to green.
 *
 * <p>The recolouring happens on the player's machine, from the texture their
 * game (or resource pack) provides: the mod ships no copy of it, and a
 * resource pack that redraws the nether portal also redraws the gate.
 * Vanilla's own {@code paletted_permutations} source cannot do this: it maps
 * exact colours (a resource pack breaks it) and drops the animation.
 *
 * <p>Used from {@code assets/minecraft/atlases/blocks.json}; registered by
 * each loader's client setup under {@link #ID}. Client only.
 */
public final class RecolouredSpriteSource implements SpriteSource {

    /** Type id used in atlas JSON files. Renaming it breaks the shipped blocks.json. */
    public static final ResourceLocation ID = CobblemonSafari.id("recoloured");

    private static final float DEGREES_PER_TURN = 360.0F;

    public static final MapCodec<RecolouredSpriteSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("source").forGetter(source -> source.source),
            ResourceLocation.CODEC.fieldOf("sprite").forGetter(source -> source.sprite),
            Codec.FLOAT.optionalFieldOf("hue_shift", 0.0F).forGetter(source -> source.hueShiftDegrees),
            Codec.FLOAT.optionalFieldOf("saturation", 1.0F).forGetter(source -> source.saturation),
            Codec.FLOAT.optionalFieldOf("brightness", 1.0F).forGetter(source -> source.brightness)
    ).apply(instance, RecolouredSpriteSource::new));

    /** The type to register with the loader. */
    public static final SpriteSourceType TYPE = new SpriteSourceType(CODEC);

    private final ResourceLocation source;
    private final ResourceLocation sprite;
    private final float hueShiftDegrees;
    private final float saturation;
    private final float brightness;

    /**
     * @param source          texture to copy, e.g. {@code minecraft:block/nether_portal}
     * @param sprite          id of the new sprite in the atlas
     * @param hueShiftDegrees hue rotation; -140 turns the portal's purple (about 265°) into green (about 125°)
     * @param saturation      saturation multiplier, 1 to keep it
     * @param brightness      brightness multiplier, 1 to keep it
     */
    private RecolouredSpriteSource(ResourceLocation source, ResourceLocation sprite, float hueShiftDegrees,
                                   float saturation, float brightness) {
        this.source = source;
        this.sprite = sprite;
        this.hueShiftDegrees = hueShiftDegrees;
        this.saturation = saturation;
        this.brightness = brightness;
    }

    /**
     * Adds the recoloured sprite. The image is converted lazily, when the
     * atlas actually loads it; the metadata (animation) is the original's,
     * so the copy animates exactly like the source.
     */
    @Override
    public void run(ResourceManager resourceManager, Output output) {
        Optional<Resource> original = resourceManager.getResource(TEXTURE_ID_CONVERTER.idToFile(source));
        if (original.isEmpty()) {
            CobblemonSafari.LOGGER.warn("Texture {} not found, {} will show the missing texture", source, sprite);
            return;
        }
        Resource base = original.get();
        output.add(sprite, new Resource(base.source(), () -> recolour(base), base::metadata));
    }

    /** Reads the source image, recolours every pixel, and returns it encoded as PNG again. */
    private InputStream recolour(Resource base) throws IOException {
        try (InputStream in = base.open(); NativeImage image = NativeImage.read(in)) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setPixelRGBA(x, y, recolourPixel(image.getPixelRGBA(x, y)));
                }
            }
            return new ByteArrayInputStream(image.asByteArray());
        }
    }

    /**
     * Rotates the hue of one pixel and scales its saturation and brightness.
     * NativeImage stores ABGR; alpha is kept as is, so the pane stays as
     * translucent as the nether portal.
     */
    private int recolourPixel(int abgr) {
        int alpha = abgr >>> 24 & 0xFF;
        if (alpha == 0) {
            return abgr;
        }
        float r = (abgr & 0xFF) / 255.0F;
        float g = (abgr >>> 8 & 0xFF) / 255.0F;
        float b = (abgr >>> 16 & 0xFF) / 255.0F;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue;
        if (delta == 0.0F) {
            hue = 0.0F;
        } else if (max == r) {
            hue = 60.0F * (((g - b) / delta) % 6.0F);
        } else if (max == g) {
            hue = 60.0F * ((b - r) / delta + 2.0F);
        } else {
            hue = 60.0F * ((r - g) / delta + 4.0F);
        }
        float sat = max == 0.0F ? 0.0F : delta / max;

        hue = ((hue + hueShiftDegrees) % DEGREES_PER_TURN + DEGREES_PER_TURN) % DEGREES_PER_TURN;
        sat = Math.min(1.0F, sat * saturation);
        float value = Math.min(1.0F, max * brightness);

        float chroma = value * sat;
        float second = chroma * (1.0F - Math.abs((hue / 60.0F) % 2.0F - 1.0F));
        float match = value - chroma;
        float r1;
        float g1;
        float b1;
        switch ((int) (hue / 60.0F)) {
            case 0 -> { r1 = chroma; g1 = second; b1 = 0.0F; }
            case 1 -> { r1 = second; g1 = chroma; b1 = 0.0F; }
            case 2 -> { r1 = 0.0F; g1 = chroma; b1 = second; }
            case 3 -> { r1 = 0.0F; g1 = second; b1 = chroma; }
            case 4 -> { r1 = second; g1 = 0.0F; b1 = chroma; }
            default -> { r1 = chroma; g1 = 0.0F; b1 = second; }
        }
        int ri = Math.round((r1 + match) * 255.0F);
        int gi = Math.round((g1 + match) * 255.0F);
        int bi = Math.round((b1 + match) * 255.0F);
        return alpha << 24 | bi << 16 | gi << 8 | ri;
    }

    /** The type this source was registered as. */
    @Override
    public SpriteSourceType type() {
        return TYPE;
    }
}
