package dev.darcosse.common.cobblemonsafari.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Exposes what a live map reset needs to rebuild the Safari level the way
 * {@code MinecraftServer.createLevels} built it: the level map, the executor
 * and the world storage. Read-only accessors; the map itself is mutated by
 * {@code SafariLevelSwap} only, at the end of a server tick.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    /** The server's levels by dimension; mutable, the very map the tick loop reads. */
    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> cobblemonSafari$levels();

    /** The executor every level's chunk work runs on. */
    @Accessor("executor")
    Executor cobblemonSafari$executor();

    /** The world's storage, where each level keeps its folder. */
    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess cobblemonSafari$storageSource();
}
