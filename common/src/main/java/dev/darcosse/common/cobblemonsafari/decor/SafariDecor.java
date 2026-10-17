package dev.darcosse.common.cobblemonsafari.decor;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The Safari's furniture: the Safari Banners and the coat rack.
 *
 * <p>Registered from {@code SafariBlocks.register} and
 * {@code SafariItems.register}, so the loaders only add {@link #items()} to
 * the creative tab. Instances are created during registration, never in
 * static initialisers, for the same reason as the other blocks and items.
 */
public final class SafariDecor {

    /** Id of the standing banner, its item and its block entity type; also its loot table and translation path. */
    public static final ResourceLocation SAFARI_BANNER_ID = CobblemonSafari.id("safari_banner");

    /** Id of the wall banner; it drops and is named like the standing one. */
    public static final ResourceLocation SAFARI_WALL_BANNER_ID = CobblemonSafari.id("safari_wall_banner");

    /** Id of the coat rack; also its models, loot table and translation path. */
    public static final ResourceLocation COAT_RACK_ID = CobblemonSafari.id("coat_rack");

    /** Like a vanilla banner: breaks at a touch. */
    private static final float BANNER_STRENGTH = 1.0F;

    /** Banners per stack, as vanilla's. */
    private static final int BANNER_STACK_SIZE = 16;

    /** Like a wooden fence: easy to move with an axe. */
    private static final float COAT_RACK_STRENGTH = 1.5F;

    private static Block safariBanner;
    private static Block safariWallBanner;
    private static Block coatRack;
    private static final List<Item> ITEMS = new ArrayList<>();

    private SafariDecor() {
    }

    /** Creates every decor block and hands it to the loader's registrar. Call exactly once. */
    public static void registerBlocks(BiConsumer<ResourceLocation, Block> registrar) {
        safariBanner = new SafariBannerBlock(bannerProperties());
        registrar.accept(SAFARI_BANNER_ID, safariBanner);
        // Drops what the standing banner drops: one item places both.
        safariWallBanner = new SafariWallBannerBlock(bannerProperties().dropsLike(safariBanner));
        registrar.accept(SAFARI_WALL_BANNER_ID, safariWallBanner);
        coatRack = new CoatRackBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .sound(SoundType.WOOD)
                .strength(COAT_RACK_STRENGTH)
                .noOcclusion()
                .ignitedByLava());
        registrar.accept(COAT_RACK_ID, coatRack);
    }

    /** Behaviour of both banners, as vanilla's: no collision, wooden, burns. */
    private static BlockBehaviour.Properties bannerProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .sound(SoundType.WOOD)
                .strength(BANNER_STRENGTH)
                .noCollission()
                .ignitedByLava();
    }

    /**
     * The standing Safari Banner.
     *
     * @throws IllegalStateException before registration
     */
    public static Block safariBanner() {
        if (safariBanner == null) {
            throw new IllegalStateException("SafariDecor.registerBlocks was never called");
        }
        return safariBanner;
    }

    /**
     * The wall Safari Banner.
     *
     * @throws IllegalStateException before registration
     */
    public static Block safariWallBanner() {
        if (safariWallBanner == null) {
            throw new IllegalStateException("SafariDecor.registerBlocks was never called");
        }
        return safariWallBanner;
    }

    /** Creates the items of the decor blocks. Call exactly once, after {@link #registerBlocks}. */
    public static void registerItems(BiConsumer<ResourceLocation, Item> registrar) {
        if (safariBanner == null) {
            throw new IllegalStateException("SafariDecor.registerBlocks was never called");
        }
        ITEMS.clear();
        // Down: clicking the top of a block stands it, a side hangs it, as vanilla banners.
        Item banner = new StandingAndWallBlockItem(safariBanner, safariWallBanner,
                new Item.Properties().stacksTo(BANNER_STACK_SIZE), Direction.DOWN);
        registrar.accept(SAFARI_BANNER_ID, banner);
        ITEMS.add(banner);
        plainItem(registrar, COAT_RACK_ID, coatRack);
    }

    /** Registers the plain block item of a decor block and adds it to the creative tab list. */
    private static void plainItem(BiConsumer<ResourceLocation, Item> registrar, ResourceLocation id, Block block) {
        Item item = new BlockItem(block, new Item.Properties());
        registrar.accept(id, item);
        ITEMS.add(item);
    }

    /** Every decor item, in creative tab order: the banner, then the coat rack. */
    public static List<Item> items() {
        return Collections.unmodifiableList(ITEMS);
    }
}
