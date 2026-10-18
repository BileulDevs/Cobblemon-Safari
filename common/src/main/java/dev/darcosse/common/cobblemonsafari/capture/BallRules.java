package dev.darcosse.common.cobblemonsafari.capture;

import com.cobblemon.mod.common.item.PokeBallItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Identifies Poké Balls and tests them against an allow-list.
 *
 * <p>Balls are identified by Cobblemon's {@code PokeBall.name}, which is also
 * the id used in the config ({@code cobblemon:safari_ball}). Addon balls that
 * extend {@link PokeBallItem} are covered automatically.
 */
public final class BallRules {

    private BallRules() {
    }

    /** The ball id of a stack, or null when the stack is not a Poké Ball. */
    @Nullable
    public static ResourceLocation ballId(ItemStack stack) {
        if (stack.getItem() instanceof PokeBallItem ballItem) {
            return ballItem.getPokeBall().getName();
        }
        return null;
    }

    /** Whether a ball id is in the given allow-list. */
    public static boolean isAllowed(ResourceLocation ballId, Set<ResourceLocation> allowed) {
        return allowed.contains(ballId);
    }

    /**
     * Whether the player carries at least one ball of the allow-list, in any
     * inventory slot (main, armour, offhand). Reads the whole inventory: for
     * one-off checks such as the entry, not every tick.
     */
    public static boolean carriesAllowed(Player player, Set<ResourceLocation> allowed) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ResourceLocation id = ballId(inventory.getItem(slot));
            if (id != null && allowed.contains(id)) {
                return true;
            }
        }
        return false;
    }
}
