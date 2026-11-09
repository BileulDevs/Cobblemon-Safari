package dev.darcosse.common.cobblemonsafari.reset;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.mixin.MinecraftServerAccessor;
import dev.darcosse.common.cobblemonsafari.park.SafariBorder;
import dev.darcosse.common.cobblemonsafari.platform.Platform;
import dev.darcosse.common.cobblemonsafari.structure.RestHousePrebuilder;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.teleport.RestHouseTravel;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Replaces the Safari level with a brand new one while the server runs: the
 * heart of the live map reset ({@link LiveReset}).
 *
 * <p>The terrain seed of a level is read once, when it is built (see
 * {@code ChunkMapMixin}), so a new map needs a new level. This does what the
 * server does at a stop and a start, for this one level: unload event,
 * removal from the server's map, close (which saves what is still loaded),
 * folder moved aside, new salt, a new level built exactly as
 * {@code MinecraftServer.createLevels} builds a non-overworld level, load
 * event, then the mod's per-map state as at a server start.
 *
 * <p>Must run at the end of a server tick, never while the levels tick.
 */
final class SafariLevelSwap {

    /**
     * Progress listener of the new level: nothing to report, since no chunk
     * is generated in advance (vanilla only prepares the overworld's spawn).
     */
    private static final ChunkProgressListener NO_PROGRESS = new ChunkProgressListener() {
        @Override
        public void updateSpawnPos(ChunkPos pos) {
        }

        @Override
        public void onStatusChange(ChunkPos pos, @Nullable ChunkStatus status) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    };

    private SafariLevelSwap() {
    }

    /**
     * Swaps the levels. The players must be out already.
     *
     * @return true when the Safari has a new map; false when it was kept (folder
     *         locked, state file not writable) or no Safari level exists
     */
    static boolean swap(MinecraftServer server) {
        ServerLevel old = SafariDimensions.level(server);
        if (old == null) {
            return false;
        }
        Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).cobblemonSafari$levels();
        long started = System.nanoTime();

        Platform.get().fireLevelUnload(server, old);
        levels.remove(SafariDimensions.key());
        Platform.get().levelsChanged(server);
        try {
            old.close();
        } catch (IOException e) {
            CobblemonSafari.LOGGER.error("Could not close the old Safari level cleanly", e);
        }

        Path folder = SafariMapReset.folder(server);
        Path moved = SafariMapReset.moveAside(folder);
        boolean fresh = false;
        if (moved != null) {
            fresh = SafariMapReset.beginNewMap(server);
            if (!fresh && !moveBack(moved, folder)) {
                // Both failed: the level below starts empty with the old salt and regrows the same terrain,
                // without the rest houses the world state lists. Rare (a disk that refuses writes); said loudly.
                CobblemonSafari.LOGGER.error("Safari map neither reset nor restored: {} holds the old map;"
                        + " stop the server and move it back to {}", moved, folder);
            }
        }

        ServerLevel created = create(server);
        levels.put(SafariDimensions.key(), created);
        Platform.get().levelsChanged(server);
        Platform.get().fireLevelLoad(server, created);
        // Only a folder that was moved and is not needed back; never the new level's own.
        if (fresh && moved != null && !moved.equals(folder)) {
            SafariMapReset.deleteInBackground(moved);
        }
        afterSwap(server);
        CobblemonSafari.LOGGER.info("Safari level swapped in {} ms ({})", (System.nanoTime() - started) / 1_000_000L,
                fresh ? "new map, salt " + SafariMapReset.salt() : "map kept");
        return fresh;
    }

    /** Puts a moved folder back after the new map could not be started; false when it fails too. */
    private static boolean moveBack(Path moved, Path folder) {
        if (moved.equals(folder)) {
            return true;
        }
        try {
            Files.move(moved, folder);
            return true;
        } catch (IOException e) {
            CobblemonSafari.LOGGER.error("Could not move {} back", moved, e);
            return false;
        }
    }

    /**
     * Builds the Safari level the way {@code MinecraftServer.createLevels}
     * builds every level but the overworld: derived level data (time and
     * weather follow the overworld's), the obfuscated world seed for the
     * biome zoom, no custom spawner, not ticking time itself, the
     * overworld's random sequences. The terrain seed comes from
     * {@code ChunkMapMixin}, which reads the new salt.
     */
    private static ServerLevel create(MinecraftServer server) {
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        WorldData worldData = server.getWorldData();
        Registry<LevelStem> stems = server.registries().compositeAccess().registryOrThrow(Registries.LEVEL_STEM);
        LevelStem stem = stems.get(SafariDimensions.key().location());
        if (stem == null) {
            // The data pack declaring the dimension is gone: nothing to rebuild from. Cannot happen
            // while the old level existed, since it was built from this very entry.
            throw new IllegalStateException("No level stem for " + SafariDimensions.key().location());
        }
        long biomeZoomSeed = BiomeManager.obfuscateSeed(worldData.worldGenOptions().seed());
        return new ServerLevel(server, access.cobblemonSafari$executor(), access.cobblemonSafari$storageSource(),
                new DerivedLevelData(worldData, worldData.overworldData()), SafariDimensions.key(), stem,
                NO_PROGRESS, worldData.isDebugWorld(), biomeZoomSeed, List.<CustomSpawner>of(), false,
                server.overworld().getRandomSequences());
    }

    /**
     * The mod's per-map state, as at a server start (SafariServerEvents.onServerStarted):
     * the border of the new level, the rest houses forgotten and searched for
     * again on the new terrain, the prebuilder and the trips restarted.
     */
    private static void afterSwap(MinecraftServer server) {
        SafariBorder.apply(server);
        SafariWorldState.get(server).forgetHousesIfMapChanged(SafariMapReset.salt());
        RestHouseTravel.reset();
        RestHousePrebuilder.reset();
        RestHouses.reset();
        RestHouses.prepare(server);
    }
}
