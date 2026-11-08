package dev.darcosse.common.cobblemonsafari.item;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.resources.ResourceLocation;
import dev.darcosse.common.cobblemonsafari.block.SafariBlocks;
import dev.darcosse.common.cobblemonsafari.decor.SafariDecor;
import dev.darcosse.common.cobblemonsafari.egg.SafariEggItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

import java.util.function.BiConsumer;

/**
 * Items added by the mod.
 *
 * <p>Instances are created INSIDE {@link #register}, not in static
 * initialisers: an Item claims its registry holder in its constructor, and
 * NeoForge only allows that while the item registry is open, i.e. during its
 * {@code RegisterEvent}. Fabric calls this from its initializer, which is also
 * before the registry freezes.
 */
public final class SafariItems {

    /** Id of the Safari Ticket; also its model and translation path. */
    public static final ResourceLocation SAFARI_TICKET_ID = CobblemonSafari.id("safari_ticket");

    /**
     * Stack size of tickets. 16 like other paper goods with a use; 64 would
     * make bulk gifting trivial for servers that sell tickets.
     */
    private static final int TICKET_STACK_SIZE = 16;

    private static Item safariTicket;
    private static Item safariWallScreen;
    private static Item safariGate;
    private static Item escapeRope;
    private static Item safariEgg;
    private static Item safariNest;

    /** Id of the Escape Rope; also its model and translation path. */
    public static final ResourceLocation ESCAPE_ROPE_ID = CobblemonSafari.id("safari_escape_rope");

    /** Id of the Safari Egg; also its model and translation path. Renaming it turns every egg out there into air. */
    public static final ResourceLocation SAFARI_EGG_ID = CobblemonSafari.id("safari_egg");

    /**
     * Id of the straw nest item. Never given to players: it only carries the
     * nest's model, which the nest entity's renderer draws.
     */
    public static final ResourceLocation SAFARI_NEST_ID = CobblemonSafari.id("safari_nest");

    private SafariItems() {
    }

    /**
     * Creates every item and hands it to the loader's registrar. Call exactly
     * once, after {@code SafariBlocks.register}: block items wrap existing blocks.
     */
    public static void register(BiConsumer<ResourceLocation, Item> registrar) {
        // One ticket item: its length comes from tickets.defaultDurationSeconds,
        // or from its own custom data (see SafariTicketItem).
        safariTicket = new SafariTicketItem(new Item.Properties().stacksTo(TICKET_STACK_SIZE).rarity(Rarity.UNCOMMON));
        registrar.accept(SAFARI_TICKET_ID, safariTicket);
        safariWallScreen = new BlockItem(SafariBlocks.safariWallScreen(), new Item.Properties());
        registrar.accept(SafariBlocks.SAFARI_WALL_SCREEN_ID, safariWallScreen);
        // Epic like other blocks only operators can get (command blocks, structure blocks).
        safariGate = new BlockItem(SafariBlocks.safariGate(), new Item.Properties().rarity(Rarity.EPIC));
        registrar.accept(SafariBlocks.SAFARI_GATE_ID, safariGate);
        // One per player and never used up, so no stacking.
        escapeRope = new SafariEscapeRopeItem(new Item.Properties().stacksTo(1));
        registrar.accept(ESCAPE_ROPE_ID, escapeRope);
        // One egg per stack: each holds its own species and hatch progress.
        safariEgg = new SafariEggItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));
        registrar.accept(SAFARI_EGG_ID, safariEgg);
        safariNest = new Item(new Item.Properties());
        registrar.accept(SAFARI_NEST_ID, safariNest);
        SafariDecor.registerItems(registrar);
    }

    /**
     * The Safari Egg.
     *
     * @throws IllegalStateException before registration
     */
    public static Item safariEgg() {
        if (safariEgg == null) {
            throw new IllegalStateException("SafariItems.register was never called");
        }
        return safariEgg;
    }

    /**
     * The straw nest item, whose model the nest entity draws.
     *
     * @throws IllegalStateException before registration
     */
    public static Item safariNest() {
        if (safariNest == null) {
            throw new IllegalStateException("SafariItems.register was never called");
        }
        return safariNest;
    }

    /**
     * The Escape Rope.
     *
     * @throws IllegalStateException before registration
     */
    public static Item escapeRope() {
        if (escapeRope == null) {
            throw new IllegalStateException("SafariItems.register was never called");
        }
        return escapeRope;
    }

    /**
     * The Safari Gate pane, as an item, for operators building their own gates.
     *
     * @throws IllegalStateException before registration
     */
    public static Item safariGate() {
        if (safariGate == null) {
            throw new IllegalStateException("SafariItems.register was never called");
        }
        return safariGate;
    }

    /**
     * The Safari Wall Screen, as an item.
     *
     * @throws IllegalStateException before registration
     */
    public static Item safariWallScreen() {
        if (safariWallScreen == null) {
            throw new IllegalStateException("SafariItems.register was never called");
        }
        return safariWallScreen;
    }

    /**
     * The Safari Ticket.
     *
     * @throws IllegalStateException before registration, which means a loader entry point is wired wrong
     */
    public static Item safariTicket() {
        if (safariTicket == null) {
            throw new IllegalStateException("SafariItems.register was never called");
        }
        return safariTicket;
    }
}
