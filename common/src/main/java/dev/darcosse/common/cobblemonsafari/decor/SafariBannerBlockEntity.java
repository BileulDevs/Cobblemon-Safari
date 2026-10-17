package dev.darcosse.common.cobblemonsafari.decor;

import dev.darcosse.common.cobblemonsafari.block.SafariBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of a Safari Banner, standing or on a wall: remembers which
 * picture the cloth shows ({@link BannerDesigns}).
 *
 * <p>The picture is a texture id, so it cannot be a block state: it travels
 * to clients in the block entity's update packet, sent when the chunk loads
 * and again whenever the design changes.
 */
public class SafariBannerBlockEntity extends BlockEntity {

    /** Save key of the design. Part of the save format: renaming it resets every banner's picture. */
    private static final String DESIGN_KEY = "design";

    private ResourceLocation design = BannerDesigns.DEFAULT;

    /** Block entity of the banner at a position. */
    public SafariBannerBlockEntity(BlockPos pos, BlockState state) {
        super(SafariBlockEntities.safariBanner(), pos, state);
    }

    /** Sprite id of the picture on the cloth, in the block atlas. */
    public ResourceLocation design() {
        return design;
    }

    /**
     * Shows another picture and tells every client watching the chunk.
     * Server side; the id is expected to be valid ({@link BannerDesigns#parse}).
     */
    public void setDesign(ResourceLocation design) {
        this.design = design;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Reads the design; an unreadable or missing one falls back to the default picture. */
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ResourceLocation saved = BannerDesigns.parse(tag.getString(DESIGN_KEY));
        design = saved != null ? saved : BannerDesigns.DEFAULT;
    }

    /** Writes the design. */
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(DESIGN_KEY, design.toString());
    }

    /** The design goes to clients with the chunk, like a sign's text. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    /** Sent by {@link #setDesign} through sendBlockUpdated. */
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
