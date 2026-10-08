package dev.darcosse.common.cobblemonsafari.client.board

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.client.render.models.blockbench.repository.RenderContext
import com.cobblemon.mod.common.client.render.models.blockbench.repository.VaryingModelRepository
import com.cobblemon.mod.common.entity.PoseType
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.darcosse.common.cobblemonsafari.CobblemonSafari
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation

/**
 * Draws animated Cobblemon models on the Safari Board screens.
 *
 * Follows what Cobblemon's own `PokemonItemRenderer` does for item frames
 * (same render context, same profile pose, same layers) with two
 * differences: the model keeps its full depth instead of being flattened,
 * so it can turn on itself, and its [FloatingState] lives across frames so
 * the idle animation actually plays.
 *
 * CLIENT ONLY. Main (render) thread only.
 */
object BoardPokemonModels {

    /**
     * Height, in model units after `profileScale`, that a Cobblemon profile
     * model roughly fills. Derived from the item-frame transform of
     * `PokemonItemRenderer` (scale 0.5, feet at a quarter of the frame, head
     * near the top). Raise it to draw every model smaller, lower it for bigger.
     */
    private const val NOMINAL_PROFILE_HEIGHT = 1.5F

    /**
     * Longest animation step applied at once, in ticks. A screen that was out
     * of sight for a minute resumes where it was instead of skipping ahead,
     * which also keeps one-shot effects from firing in a burst.
     */
    private const val MAX_ANIMATION_STEP_TICKS = 2F

    private const val NANOS_PER_TICK = 50_000_000.0

    /** Opaque white: the model is drawn with its own colours. */
    private const val NO_TINT = -1

    /** One animated model instance, shared by every screen showing the same species and aspects. */
    private class Slot(val state: FloatingState, var lastNanos: Long)

    private val slots = HashMap<String, Slot>()

    /** Species whose model threw once; skipped afterwards so a broken add-on model cannot spam the log every frame. */
    private val failed = HashSet<ResourceLocation>()

    /** Drops every animation state; called on disconnect. */
    @JvmStatic
    fun clear() {
        slots.clear()
        failed.clear()
    }

    /**
     * Name of a species in the player's language, or a readable fallback when
     * the client does not know the species (data pack missing client side).
     */
    @JvmStatic
    fun displayName(species: ResourceLocation): String {
        val known = PokemonSpecies.getByIdentifier(species)
        if (known != null) {
            return known.translatedName.string
        }
        return species.path.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Draws one model with its feet at the current origin of [poseStack].
     *
     * The pose is expected in the board's canvas frame: x to the right, y
     * DOWN, z toward the viewer, units of canvas pixels.
     *
     * @param height    target model height, in canvas pixels
     * @param yawDegrees rotation of the model on itself
     * @return false when nothing was drawn (unknown species or broken model)
     */
    @JvmStatic
    fun render(
        species: ResourceLocation,
        aspects: Set<String>,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        light: Int,
        height: Float,
        yawDegrees: Float
    ): Boolean {
        if (species in failed || PokemonSpecies.getByIdentifier(species) == null) {
            return false
        }
        val slot = slots.getOrPut(key(species, aspects)) {
            Slot(FloatingState().also { it.currentAspects = aspects }, System.nanoTime())
        }
        val state = slot.state
        poseStack.pushPose()
        try {
            val model = VaryingModelRepository.getPoser(species, state)
            val context = RenderContext()
            context.put(RenderContext.RENDER_STATE, RenderContext.RenderState.PROFILE)
            context.put(RenderContext.DO_QUIRKS, false)
            context.put(RenderContext.SPECIES, species)
            context.put(RenderContext.ASPECTS, aspects)
            context.put(RenderContext.POSABLE_STATE, state)
            model.context = context
            state.currentModel = model
            state.setPoseToFirstSuitable(PoseType.PROFILE)
            advance(slot)
            model.applyAnimations(null, state, 0F, 0F, 0F, 0F, 0F)

            // PokemonItemRenderer draws from a y-up frame scaled by (s, -s, -s).
            // The canvas frame is that y-up frame with y already mirrored, so
            // mirroring z alone lands in the same frame; with y mirrored again
            // the model would hang upside down.
            val scale = height / NOMINAL_PROFILE_HEIGHT
            poseStack.scale(scale, scale, -scale)
            poseStack.mulPose(Axis.YP.rotationDegrees(yawDegrees))
            poseStack.scale(model.profileScale, model.profileScale, model.profileScale)

            val texture = VaryingModelRepository.getTexture(species, state)
            val buffer = buffers.getBuffer(RenderType.entityCutout(texture))
            model.withLayerContext(buffers, state, VaryingModelRepository.getLayers(species, state)) {
                model.render(context, poseStack, buffer, light, OverlayTexture.NO_OVERLAY, NO_TINT)
            }
            model.setDefault()
            return true
        } catch (e: RuntimeException) {
            failed.add(species)
            CobblemonSafari.LOGGER.warn("Safari Board: could not draw the model of {}; showing its name only", species, e)
            return false
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Moves the animation forward by the real time elapsed since this slot was
     * last drawn. Wall-clock based so that a slot drawn by several screens in
     * the same frame advances once in total, not once per screen:
     * [FloatingState.updatePartialTicks] adds rather than sets.
     */
    private fun advance(slot: Slot) {
        val now = System.nanoTime()
        val elapsedTicks = ((now - slot.lastNanos) / NANOS_PER_TICK).toFloat()
        slot.lastNanos = now
        slot.state.updatePartialTicks(elapsedTicks.coerceIn(0F, MAX_ANIMATION_STEP_TICKS))
    }

    /** Cache key: same species and aspects share one animation. */
    private fun key(species: ResourceLocation, aspects: Set<String>): String =
        species.toString() + "|" + aspects.sorted().joinToString(",")
}
