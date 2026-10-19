package dev.darcosse.common.cobblemonsafari.client.shop

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.util.math.fromEulerXYZDegrees
import dev.darcosse.common.cobblemonsafari.CobblemonSafari
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Draws animated Cobblemon models, gently swaying, in the shop screen.
 *
 * Uses Cobblemon's own GUI helper (`drawProfilePokemon`, what the PC and the
 * summary use) with the transform of a PC slot, scaled to the card. Each
 * entry keeps its [FloatingState] across frames so the idle animation plays.
 *
 * CLIENT ONLY. Render thread only.
 */
object ShopPokemonModels {

    /**
     * Size of a PC storage slot in Cobblemon (StorageSlot.SIZE) and the
     * scales it uses: the model fits such a slot. Our area is scaled from it,
     * so a model fits the card the same way it fits the PC.
     */
    private const val PC_SLOT_SIZE = 25F
    private const val PC_SLOT_POSE_SCALE = 2.5F
    private const val PC_MODEL_SCALE = 4.5F

    /** Camera tilt of Cobblemon's PC slots, so models look the same as there. */
    private const val PITCH_DEGREES = 13F

    /**
     * Facing of the model at rest: three-quarter front, Cobblemon's PC angle.
     * The model sways around it instead of turning a full circle: a full
     * turn would show every Pokémon from behind half of the time, all at once.
     */
    private const val FRONT_YAW_DEGREES = 35F

    /** How far the model sways to each side of [FRONT_YAW_DEGREES]. Past ~70 the back shows again. */
    private const val SWAY_DEGREES = 30F

    /** Time of one full sway, left and back. */
    private const val SWAY_PERIOD_MILLIS = 6000.0

    /**
     * Phase offset between cards, in radians, so neighbouring cards do not
     * sway in step like a choir.
     */
    private const val SWAY_PHASE_STEP = 1.3

    private val states = HashMap<String, FloatingState>()

    /** Species whose model threw once; drawn as nothing afterwards so the log is not flooded every frame. */
    private val failed = HashSet<ResourceLocation>()

    /** Forgets every animation state; called when the screen closes. */
    @JvmStatic
    fun clear() {
        states.clear()
    }

    /** Name of a species in the player's language, or its id path when the client does not know it. */
    @JvmStatic
    fun displayName(species: ResourceLocation): String =
        PokemonSpecies.getByIdentifier(species)?.translatedName?.string
            ?: species.path.replaceFirstChar { it.uppercaseChar() }

    /**
     * Draws a model filling a square area whose top-left corner is ([x], [y]).
     * The caller clips with a scissor: a large model may overflow the area.
     *
     * @param key one state per key; use the entry index so identical species keep separate animations
     */
    @JvmStatic
    fun draw(graphics: GuiGraphics, key: String, species: ResourceLocation, aspects: Collection<String>,
             x: Int, y: Int, size: Int, partialTicks: Float) {
        if (species in failed || PokemonSpecies.getByIdentifier(species) == null) {
            return
        }
        val state = states.getOrPut(key) { FloatingState() }
        state.currentAspects = aspects.toSet()
        val phase = System.currentTimeMillis() % SWAY_PERIOD_MILLIS.toLong() / SWAY_PERIOD_MILLIS * 2 * Math.PI
        val yaw = FRONT_YAW_DEGREES + SWAY_DEGREES * Math.sin(phase + key.hashCode() * SWAY_PHASE_STEP).toFloat()
        val factor = size / PC_SLOT_SIZE
        val pose = graphics.pose()
        pose.pushPose()
        try {
            // Same anchor as a PC slot: horizontally centred, one slot-pixel below the top.
            pose.translate(x + size / 2.0, y + factor.toDouble(), 0.0)
            pose.scale(PC_SLOT_POSE_SCALE * factor, PC_SLOT_POSE_SCALE * factor, 1F)
            drawProfilePokemon(
                species = species,
                matrixStack = pose,
                rotation = Quaternionf().fromEulerXYZDegrees(Vector3f(PITCH_DEGREES, yaw, 0F)),
                state = state,
                partialTicks = partialTicks,
                scale = PC_MODEL_SCALE,
            )
        } catch (e: RuntimeException) {
            failed.add(species)
            CobblemonSafari.LOGGER.warn("Could not draw the shop model of {}: {}", species, e.toString())
        } finally {
            pose.popPose()
        }
    }
}
