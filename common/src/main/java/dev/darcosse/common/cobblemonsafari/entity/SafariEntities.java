package dev.darcosse.common.cobblemonsafari.entity;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.egg.SafariNestEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.function.BiConsumer;

/**
 * Entity types added by the mod.
 *
 * <p>Same lifecycle rule as the items: created inside {@link #register},
 * while the loader's entity type registry is open.
 */
public final class SafariEntities {

    /** Id of the straw nest; also its translation path. Renaming it drops every nest saved in the worlds. */
    public static final ResourceLocation SAFARI_NEST_ID = CobblemonSafari.id("safari_nest");

    /** Hitbox of a nest, in blocks: the straw ring and the egg on it, so it is easy to click. */
    private static final float NEST_WIDTH = 0.8F;
    private static final float NEST_HEIGHT = 0.5F;

    /**
     * Chunks around a player within which clients are told about a nest.
     * Vanilla's item frames use 10; less and a nest 48 blocks away (the
     * farthest one is placed) could be missing on the client until walked to.
     */
    private static final int NEST_TRACKING_RANGE_CHUNKS = 8;

    /**
     * Ticks between position updates sent to clients. A nest never moves, so
     * the slowest vanilla rate, as item frames, saves packets.
     */
    private static final int NEST_UPDATE_INTERVAL = Integer.MAX_VALUE;

    private static EntityType<SafariNestEntity> safariNest;

    private SafariEntities() {
    }

    /** Creates every entity type and hands it to the loader's registrar. Call exactly once. */
    public static void register(BiConsumer<ResourceLocation, EntityType<?>> registrar) {
        safariNest = EntityType.Builder.<SafariNestEntity>of(SafariNestEntity::new, MobCategory.MISC)
                .sized(NEST_WIDTH, NEST_HEIGHT)
                .clientTrackingRange(NEST_TRACKING_RANGE_CHUNKS)
                .updateInterval(NEST_UPDATE_INTERVAL)
                // Neither fire nor lava ends a nest early: its lifetime does.
                .fireImmune()
                .build(SAFARI_NEST_ID.toString());
        registrar.accept(SAFARI_NEST_ID, safariNest);
    }

    /**
     * The straw nest's type.
     *
     * @throws IllegalStateException before registration, which means a loader entry point is wired wrong
     */
    public static EntityType<SafariNestEntity> safariNest() {
        if (safariNest == null) {
            throw new IllegalStateException("SafariEntities.register was never called");
        }
        return safariNest;
    }
}
