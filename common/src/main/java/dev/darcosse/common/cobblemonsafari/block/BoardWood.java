package dev.darcosse.common.cobblemonsafari.block;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Wood of the planks behind a Safari Board, chosen by right-clicking a frame
 * with vanilla planks. Each value maps to {@code minecraft:<name>_planks},
 * both for the item that selects it and for the texture the model shows.
 *
 * <p>Adding a value adds a blockstate property value: the blockstate and the
 * {@code safari_board_frame_planks_*_<wood>} models must be regenerated with
 * it (tools/board_frame_models.py), or the planks render as missing texture.
 */
public enum BoardWood implements StringRepresentable {
    OAK, SPRUCE, BIRCH, JUNGLE, ACACIA, DARK_OAK, MANGROVE, CHERRY, BAMBOO, CRIMSON, WARPED;

    /** Wood of newly placed frames, matching the look the frame had before woods were selectable. */
    public static final BoardWood DEFAULT = SPRUCE;

    /** Name used in the blockstate, the models and the planks id. */
    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Id of the vanilla planks item (and block) of this wood. */
    public ResourceLocation planksId() {
        return ResourceLocation.withDefaultNamespace(getSerializedName() + "_planks");
    }

    /**
     * The wood a held item selects, or null when the item is not one of the
     * vanilla planks above (modded planks have no texture we can point to).
     */
    @Nullable
    public static BoardWood fromItem(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        for (BoardWood wood : values()) {
            if (wood.planksId().equals(id)) {
                return wood;
            }
        }
        return null;
    }
}
