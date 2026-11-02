package dev.darcosse.common.cobblemonsafari.protection;

import com.cobblemon.mod.common.block.ApricornBlock;
import com.cobblemon.mod.common.block.BerryBlock;
import com.cobblemon.mod.common.block.BugwortBlock;
import com.cobblemon.mod.common.block.HeartyGrainsBlock;
import com.cobblemon.mod.common.block.MedicinalLeekBlock;
import com.cobblemon.mod.common.block.MintBlock;
import com.cobblemon.mod.common.block.NutBushBlock;
import com.cobblemon.mod.common.block.RevivalHerbBlock;
import com.cobblemon.mod.common.block.RootBlock;
import com.cobblemon.mod.common.block.VivichokeBlock;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.block.Block;

import java.util.function.Predicate;

/**
 * The Cobblemon plants players may be allowed to harvest in the Safari
 * despite the terrain protection, one switch each in
 * {@code protection.interact}.
 *
 * <p>Each category says how Cobblemon harvests it, because that decides
 * which protection check must let it through:
 * <ul>
 *   <li>by a click on the block (berries, apricorns, Galarica nuts): the
 *       plant stays and grows back. Off, any right-click on it is refused
 *       (and, for apricorns, the left-click that also picks them);</li>
 *   <li>by breaking it (mints, herbs, grains, bugwort): the plant is gone
 *       until the next map. On, breaking it is allowed;</li>
 *   <li>roots, both: shears turn them into a smaller root and drop one, and
 *       breaking them drops them too.</li>
 * </ul>
 *
 * <p>The ordinal is sent to the client as a bit of
 * {@code SafariRulesPayload#harvestMask}: append new categories at the end,
 * never reorder, and raise the payload's wire version when adding one.
 */
public enum HarvestCategory {
    BERRIES(BerryBlock.class, true, false, null),
    APRICORNS(ApricornBlock.class, true, false, null),
    GALARICA_NUTS(NutBushBlock.class, true, false, null),
    MINTS(MintBlock.class, false, true, null),
    REVIVAL_HERBS(RevivalHerbBlock.class, false, true, null),
    MEDICINAL_LEEKS(MedicinalLeekBlock.class, false, true, null),
    VIVICHOKES(VivichokeBlock.class, false, true, null),
    HEARTY_GRAINS(HeartyGrainsBlock.class, false, true, null),
    BUGWORT(BugwortBlock.class, false, true, null),
    // Big and energy roots share RootBlock; shearing is their click harvest.
    ROOTS(RootBlock.class, true, true, stack -> stack.getItem() instanceof ShearsItem);

    private static final HarvestCategory[] VALUES = values();

    private final Class<? extends Block> blockClass;
    private final boolean byClick;
    private final boolean byBreak;
    private final Predicate<ItemStack> harvestTool;

    /**
     * @param blockClass  Cobblemon block class of the plant (subclasses included)
     * @param byClick     whether Cobblemon harvests it with a click on the block
     * @param byBreak     whether Cobblemon harvests it by breaking the block
     * @param harvestTool the item the click harvest needs, which the protection
     *                    would refuse otherwise; null when an empty hand does it
     */
    HarvestCategory(Class<? extends Block> blockClass, boolean byClick, boolean byBreak,
                    Predicate<ItemStack> harvestTool) {
        this.blockClass = blockClass;
        this.byClick = byClick;
        this.byBreak = byBreak;
        this.harvestTool = harvestTool;
    }

    /**
     * The category of a block, or null for any other block. A short loop of
     * instanceof checks, run only on a click or a break.
     */
    public static HarvestCategory of(Block block) {
        for (HarvestCategory category : VALUES) {
            if (category.blockClass.isInstance(block)) {
                return category;
            }
        }
        return null;
    }

    /** Whether Cobblemon harvests this plant with a click on the block. */
    public boolean byClick() {
        return byClick;
    }

    /** Whether Cobblemon harvests this plant by breaking the block. */
    public boolean byBreak() {
        return byBreak;
    }

    /** Whether the item in hand is this plant's harvest tool (shears for roots). */
    public boolean isHarvestTool(ItemStack stack) {
        return harvestTool != null && harvestTool.test(stack);
    }

    /** This category's switch in {@code protection.interact}. */
    public boolean allowedBy(SafariConfig.Interact interact) {
        return switch (this) {
            case BERRIES -> interact.berries;
            case APRICORNS -> interact.apricorns;
            case GALARICA_NUTS -> interact.galaricaNuts;
            case MINTS -> interact.mints;
            case REVIVAL_HERBS -> interact.revivalHerbs;
            case MEDICINAL_LEEKS -> interact.medicinalLeeks;
            case VIVICHOKES -> interact.vivichokes;
            case HEARTY_GRAINS -> interact.heartyGrains;
            case BUGWORT -> interact.bugwort;
            case ROOTS -> interact.roots;
        };
    }

    /** The switches of {@code protection.interact} as a bit set by ordinal, for the client. */
    public static int maskOf(SafariConfig.Interact interact) {
        int mask = 0;
        for (HarvestCategory category : VALUES) {
            if (category.allowedBy(interact)) {
                mask |= 1 << category.ordinal();
            }
        }
        return mask;
    }

    /** Whether this category's bit is set in a mask built by {@link #maskOf}. */
    public boolean allowedBy(int mask) {
        return (mask & (1 << ordinal())) != 0;
    }
}
