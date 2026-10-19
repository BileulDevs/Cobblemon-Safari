package dev.darcosse.common.cobblemonsafari.loan;

import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * Creates, recognises and reclaims the Safari Balls lent at the entrance.
 *
 * <p>A loaned ball is an ordinary Poké Ball stack with a marker key in its
 * vanilla custom data. The marker is synced to clients with the stack, so the
 * client can refuse to throw them outside the Safari without any extra
 * packet.
 *
 * <p>Why a marker instead of taking every Safari Ball on exit: players may own
 * Safari Balls bought or crafted outside, and those must never be confiscated.
 */
public final class LoanedBalls {

    /**
     * Key in the stack's custom data. Changing it makes every ball already lent
     * look like a normal ball: those could then leave the Safari for good.
     */
    public static final String MARKER_KEY = CobblemonSafari.MOD_ID + ":loaned";

    private LoanedBalls() {
    }

    /** Whether a stack is a loaned Safari Ball. Safe on both sides. */
    public static boolean isLoaned(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains(MARKER_KEY);
    }

    /**
     * Hands out the configured number of loaned balls, dropping at the
     * player's feet whatever does not fit.
     *
     * @return the number of balls actually lent; 0 when the loan is disabled or the item is unknown
     */
    public static int lend(Player player, int count) {
        ResourceLocation itemId = SafariConfigManager.loanedBallItem();
        if (itemId == null || count <= 0) {
            return 0;
        }
        Item item = BuiltInRegistries.ITEM.get(itemId);
        // The item registry is defaulted: an unknown id silently yields air.
        if (item == Items.AIR) {
            CobblemonSafari.LOGGER.error("loan.ballItem '{}' is not a known item; no ball was lent", itemId);
            return 0;
        }
        int remaining = count;
        while (remaining > 0) {
            ItemStack stack = new ItemStack(item);
            int size = Math.min(remaining, stack.getMaxStackSize());
            stack.setCount(size);
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(MARKER_KEY, true));
            // Cobblemon's helper: inserts, and drops the overflow with the
            // pickup delay removed and the player as target.
            PlayerExtensionsKt.giveOrDropItemStack(player, stack, true);
            remaining -= size;
        }
        return count;
    }

    /**
     * Removes every loaned ball from the player's inventory (main, armour and
     * offhand slots).
     *
     * <p>Cost: one pass over the 41 inventory slots. Called once per second per
     * player outside the Safari, so it stays negligible.
     *
     * @return how many balls were taken back
     */
    public static int reclaim(Player player) {
        Inventory inventory = player.getInventory();
        int taken = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isLoaned(stack)) {
                taken += stack.getCount();
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        return taken;
    }

}
