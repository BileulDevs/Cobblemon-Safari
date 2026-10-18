package dev.darcosse.common.cobblemonsafari.item;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.decor.SafariDecor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The mod's own creative tab, "Cobblemon Safari", holding every item the mod
 * adds.
 *
 * <p>Each loader creates the tab builder (Fabric's {@code FabricItemGroup}
 * places it among the pages of modded tabs, NeoForge sorts modded tabs
 * itself) and registers the tab under {@link #ID}; this class fills it, so
 * both loaders list the same items in the same order.
 */
public final class SafariCreativeTab {

    /** Registry id of the tab. */
    public static final ResourceLocation ID = CobblemonSafari.id("cobblemon_safari");

    /** Translation key of the tab's title, in vanilla's {@code itemGroup.} namespace. */
    public static final String TITLE_KEY = "itemGroup." + CobblemonSafari.MOD_ID;

    private SafariCreativeTab() {
    }

    /**
     * Sets the title, icon and contents of the tab on a loader's builder and
     * builds it. Call during the creative tab registry's registration, after
     * the items exist: the icon and contents are only read when the creative
     * screen first opens, but the items must be registered by then.
     */
    public static CreativeModeTab build(CreativeModeTab.Builder builder) {
        return builder
                .title(Component.translatable(TITLE_KEY))
                // The ticket: the mod's most recognisable item.
                .icon(() -> new ItemStack(SafariItems.safariTicket()))
                .displayItems((parameters, output) -> {
                    // A loop rather than a method reference: accept is overloaded.
                    for (Item item : items()) {
                        output.accept(item);
                    }
                })
                .build();
    }

    /**
     * Every item of the mod, in tab order: the hunt (ticket, Escape Rope),
     * the gate and the wall screen for building, then the decor.
     */
    public static List<Item> items() {
        List<Item> items = new ArrayList<>();
        items.add(SafariItems.safariTicket());
        items.add(SafariItems.escapeRope());
        items.add(SafariItems.safariGate());
        items.add(SafariItems.safariWallScreen());
        items.addAll(SafariDecor.items());
        return items;
    }
}
