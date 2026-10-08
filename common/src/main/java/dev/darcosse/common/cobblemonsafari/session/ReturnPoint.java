package dev.darcosse.common.cobblemonsafari.session;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Where a player stood when they entered the Safari, so they can be sent back.
 *
 * <p>Stored as a dimension id rather than a level reference: levels do not
 * survive a restart, and the dimension may be gone by the time the player
 * leaves (datapack removed). Callers must handle that case.
 */
public record ReturnPoint(ResourceLocation dimension, double x, double y, double z, float yaw, float pitch) {

    /** Snapshot of the player's current position. */
    public static ReturnPoint of(ServerPlayer player) {
        return new ReturnPoint(player.level().dimension().location(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    /** The dimension as a key usable with {@code MinecraftServer#getLevel}. */
    public ResourceKey<Level> dimensionKey() {
        return ResourceKey.create(Registries.DIMENSION, dimension);
    }

    /** Serialises into a compound. Field names are the save format: renaming them loses return points. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", dimension.toString());
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putFloat("yaw", yaw);
        tag.putFloat("pitch", pitch);
        return tag;
    }

    /** Reads a compound written by {@link #save()}, or null if it is unusable. */
    @Nullable
    public static ReturnPoint load(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
        if (dimension == null) {
            return null;
        }
        return new ReturnPoint(dimension, tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"),
                tag.getFloat("yaw"), tag.getFloat("pitch"));
    }
}
