package dev.darcosse.common.cobblemonsafari.session;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persists every active session with the world save.
 *
 * <p>Attached to the overworld rather than the Safari level: the overworld is
 * always loaded, and the Safari dimension may be missing if its config or
 * datapack changes; sessions must survive that so players can still be sent home.
 */
public final class SafariSessionStore extends SavedData {

    /** File name under data/. Changing it orphans every existing session. */
    private static final String DATA_NAME = CobblemonSafari.MOD_ID + "_sessions";

    private static final SavedData.Factory<SafariSessionStore> FACTORY =
            new SavedData.Factory<>(SafariSessionStore::new, SafariSessionStore::load, null);

    private final Map<UUID, SafariSession> sessions = new HashMap<>();

    /** The store of the given server, created on first access. */
    public static SafariSessionStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** The session of a player, or null. */
    @Nullable
    SafariSession get(UUID playerId) {
        return sessions.get(playerId);
    }

    /** Read-only view; callers that end sessions while iterating must copy it first. */
    Collection<SafariSession> all() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /** Whether no session is running, so the per-tick loop can stop early. */
    boolean isEmpty() {
        return sessions.isEmpty();
    }

    /** Adds or replaces a player's session and marks the store for saving. */
    void put(SafariSession session) {
        sessions.put(session.playerId(), session);
        setDirty();
    }

    /** Removes a player's session, marking the store for saving; returns it, or null when there was none. */
    @Nullable
    SafariSession remove(UUID playerId) {
        SafariSession removed = sessions.remove(playerId);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    /** Writes every session as a list of compounds. */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (SafariSession session : sessions.values()) {
            list.add(session.save());
        }
        nbt.put("sessions", list);
        return nbt;
    }

    /** Reads what {@link #save} wrote, skipping unreadable sessions. */
    private static SafariSessionStore load(CompoundTag nbt, HolderLookup.Provider registries) {
        SafariSessionStore store = new SafariSessionStore();
        ListTag list = nbt.getList("sessions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            SafariSession session = SafariSession.load(list.getCompound(i));
            if (session != null) {
                store.sessions.put(session.playerId(), session);
            }
        }
        return store;
    }
}
