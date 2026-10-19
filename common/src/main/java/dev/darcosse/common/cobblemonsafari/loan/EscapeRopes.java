package dev.darcosse.common.cobblemonsafari.loan;

import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.item.SafariEscapeRopeItem;
import dev.darcosse.common.cobblemonsafari.item.SafariItems;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Lends the Escape Rope on entry and takes it back on exit, like the loaned
 * Safari Balls.
 *
 * <p>No marker is needed, unlike {@link LoanedBalls}: the rope item exists
 * only for the Safari, so every copy outside it is reclaimed, whoever put it
 * there. Server side.
 */
public final class EscapeRopes {

    /** What {@link #lend} did. */
    public enum Lent {
        /** Nothing: ropes are not lent, or the player already has one. */
        NONE,
        /** The rope went into the inventory. */
        GIVEN,
        /** The inventory was full: the rope lies at the player's feet. */
        DROPPED
    }

    private EscapeRopes() {
    }

    /**
     * Gives one rope if the config lends them and the player has none yet
     * (a rope dropped on death inside stays where it fell). With a full
     * inventory the rope is dropped at the player's feet, inside the Safari,
     * and the caller says so, so the player is never told they carry a rope
     * that lies on the ground.
     */
    public static Lent lend(Player player) {
        if (!SafariConfigManager.get().escapeRope.enabled || count(player) > 0) {
            return Lent.NONE;
        }
        ItemStack rope = new ItemStack(SafariItems.escapeRope());
        if (player.getInventory().add(rope)) {
            return Lent.GIVEN;
        }
        // Cobblemon's helper: tries the inventory once more, then drops at the player's feet.
        PlayerExtensionsKt.giveOrDropItemStack(player, rope, true);
        return Lent.DROPPED;
    }

    /**
     * Removes every rope from the inventory. One pass over the 41 slots;
     * called once per second per player outside the Safari, with the
     * loaned-ball reclaim.
     *
     * @return how many ropes were taken back
     */
    public static int reclaim(Player player) {
        Inventory inventory = player.getInventory();
        int taken = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() instanceof SafariEscapeRopeItem) {
                taken += stack.getCount();
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        return taken;
    }

    /** How many ropes the player carries, in any slot. Reads the whole inventory: not for every tick. */
    public static int count(Player player) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem() instanceof SafariEscapeRopeItem) {
                total += inventory.getItem(slot).getCount();
            }
        }
        return total;
    }
}
