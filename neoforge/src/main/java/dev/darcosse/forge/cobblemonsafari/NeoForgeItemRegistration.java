package dev.darcosse.forge.cobblemonsafari;

import dev.darcosse.common.cobblemonsafari.block.SafariBlockEntities;
import dev.darcosse.common.cobblemonsafari.block.SafariBlocks;
import dev.darcosse.common.cobblemonsafari.item.SafariCreativeTab;
import dev.darcosse.common.cobblemonsafari.item.SafariItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Registers the Safari blocks, block entity types, items and creative tab on
 * NeoForge.
 */
public final class NeoForgeItemRegistration {

    private NeoForgeItemRegistration() {
    }

    /**
     * Mod-bus handler. RegisterEvent fires once per registry; the key check is
     * done by {@code register} itself, so the items are created exactly once,
     * while the item registry is open.
     */
    static void onRegister(RegisterEvent event) {
        // NeoForge fires the block registry before the item registry, which is
        // the order the block items need.
        event.register(Registries.BLOCK, helper -> SafariBlocks.register(helper::register));
        event.register(Registries.ITEM, helper -> SafariItems.register(helper::register));
        // Vanilla's registration order puts block entity types after blocks.
        // Built here, not in common: BlockEntitySupplier is only public through
        // NeoForge's access transformer (see SafariBlockEntities.TypeFactory).
        event.register(Registries.BLOCK_ENTITY_TYPE, helper -> SafariBlockEntities.register(
                (constructor, block) -> BlockEntityType.Builder.of(constructor::apply, block).build(null),
                helper::register));
        // Vanilla's builder: NeoForge places modded tabs on its own pages,
        // whatever row and column the builder is given.
        event.register(Registries.CREATIVE_MODE_TAB, helper -> helper.register(SafariCreativeTab.ID,
                SafariCreativeTab.build(CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0))));
    }
}
