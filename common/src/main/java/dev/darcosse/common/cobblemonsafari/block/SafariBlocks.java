package dev.darcosse.common.cobblemonsafari.block;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.decor.SafariDecor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.function.BiConsumer;

/**
 * Blocks added by the mod.
 *
 * <p>Same rule as {@code SafariItems}: instances are created inside
 * {@link #register}, during the loader's block registration, never in static
 * initialisers. Must run before {@code SafariItems.register}, which wraps the
 * blocks in block items.
 */
public final class SafariBlocks {

    /** Id of the Safari Wall Screen; also its models, loot table and translation path. */
    public static final ResourceLocation SAFARI_WALL_SCREEN_ID = CobblemonSafari.id("safari_wall_screen");

    /** Id of the Safari Gate pane; also its model and translation path. */
    public static final ResourceLocation SAFARI_GATE_ID = CobblemonSafari.id("safari_gate");

    /**
     * Light given off by a gate pane, like a nether portal. Higher values
     * light the plaza at night; 15 would also melt nearby snow and ice.
     */
    private static final int GATE_LIGHT = 11;

    /** Vanilla's value for unbreakable blocks (bedrock, portals): survival players cannot mine gates. */
    private static final float UNBREAKABLE = -1.0F;

    /** Explosion resistance of bedrock, so no blast opens a gate's pane. */
    private static final float BLAST_PROOF = 3_600_000.0F;

    /** Mining time like a wooden sign: easy to move, not broken by a stray punch. */
    private static final float WALL_SCREEN_STRENGTH = 1.0F;

    private static Block safariWallScreen;
    private static Block safariGate;

    private SafariBlocks() {
    }

    /** Creates every block and hands it to the loader's registrar. Call exactly once. */
    public static void register(BiConsumer<ResourceLocation, Block> registrar) {
        // A wooden-sounding screen, easy to move.
        safariWallScreen = new SafariWallScreenBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .sound(SoundType.WOOD)
                .strength(WALL_SCREEN_STRENGTH)
                .noOcclusion());
        registrar.accept(SAFARI_WALL_SCREEN_ID, safariWallScreen);
        safariGate = new SafariGateBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.EMERALD)
                .sound(SoundType.GLASS)
                .strength(UNBREAKABLE, BLAST_PROOF)
                .lightLevel(state -> GATE_LIGHT)
                .noCollission()
                .noOcclusion()
                .noLootTable()
                .pushReaction(PushReaction.BLOCK));
        registrar.accept(SAFARI_GATE_ID, safariGate);
        SafariDecor.registerBlocks(registrar);
    }

    /**
     * The Safari Gate pane.
     *
     * @throws IllegalStateException before registration, which means a loader entry point is wired wrong
     */
    public static Block safariGate() {
        if (safariGate == null) {
            throw new IllegalStateException("SafariBlocks.register was never called");
        }
        return safariGate;
    }

    /**
     * The Safari Wall Screen.
     *
     * @throws IllegalStateException before registration, which means a loader entry point is wired wrong
     */
    public static Block safariWallScreen() {
        if (safariWallScreen == null) {
            throw new IllegalStateException("SafariBlocks.register was never called");
        }
        return safariWallScreen;
    }
}
