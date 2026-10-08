package dev.darcosse.common.cobblemonsafari.session;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One player's stay in the Safari.
 *
 * <p>Mutated only through {@link SafariSessionManager}, which is responsible for
 * marking the store dirty and re-syncing the client. Mutating a session from
 * anywhere else would leave the HUD showing stale time.
 */
public final class SafariSession {

    private final UUID playerId;
    private int remainingTicks;
    private int grantedTicks;
    @Nullable
    private final ReturnPoint returnPoint;
    private final String source;
    /** Number of loaned Safari Balls handed out at the start; 0 when the loan is disabled. */
    private final int ballsLoaned;
    private final List<CatchRecord> catches = new ArrayList<>();

    /** Not persisted: grace only matters while the player is online and battling. */
    private int graceTicksUsed;
    /** Not persisted: after a restart, "time is up" is simply announced again. */
    private boolean timeUpAnnounced;

    SafariSession(UUID playerId, int remainingTicks, int grantedTicks,
                  @Nullable ReturnPoint returnPoint, String source, int ballsLoaned) {
        this.playerId = playerId;
        this.remainingTicks = remainingTicks;
        this.grantedTicks = grantedTicks;
        this.returnPoint = returnPoint;
        this.source = source;
        this.ballsLoaned = ballsLoaned;
    }

    /** Owner of the session. */
    public UUID playerId() {
        return playerId;
    }

    /** Ticks left before the player is escorted out. */
    public int remainingTicks() {
        return remainingTicks;
    }

    /** Total time granted over the session, including additions; for statistics. */
    public int grantedTicks() {
        return grantedTicks;
    }

    /** Where to send the player back; null if it could not be recorded or read back. */
    @Nullable
    public ReturnPoint returnPoint() {
        return returnPoint;
    }

    /** Free-form id of what granted the session (command, ticket, NPC...), for logs and statistics. */
    public String source() {
        return source;
    }

    /** Loaned Safari Balls handed out when the session started. */
    public int ballsLoaned() {
        return ballsLoaned;
    }

    /** Pokémon caught so far, in order. Read-only view. */
    public List<CatchRecord> catches() {
        return Collections.unmodifiableList(catches);
    }

    /** Ticks actually spent hunting: everything granted minus what is left. */
    public int ticksUsed() {
        return Math.max(0, grantedTicks - remainingTicks);
    }

    /** Appends a catch; the manager marks the store dirty and notifies listeners. */
    void recordCatch(CatchRecord record) {
        catches.add(record);
    }

    /** Whether the "time is up, finish your battle" message was already sent. */
    boolean isTimeUpAnnounced() {
        return timeUpAnnounced;
    }

    /** Remembers that the time-up message was sent, so it is not repeated every tick. */
    void markTimeUpAnnounced() {
        timeUpAnnounced = true;
    }

    /** Ticks of battle grace already spent after the clock hit zero. */
    int graceTicksUsed() {
        return graceTicksUsed;
    }

    /** Spends one tick of battle grace. */
    void consumeGraceTick() {
        graceTicksUsed++;
    }

    /** Removes one tick; never goes below zero. */
    void tickDown() {
        if (remainingTicks > 0) {
            remainingTicks--;
        }
    }

    /**
     * Sets the remaining time. A raise counts towards the granted total so that
     * statistics reflect everything the player was given.
     */
    void setRemainingTicks(int ticks) {
        int clamped = Math.max(0, ticks);
        if (clamped > remainingTicks) {
            grantedTicks += clamped - remainingTicks;
        }
        remainingTicks = clamped;
        // Time was added back: a later expiry must be announced again and grace restarts.
        if (remainingTicks > 0) {
            timeUpAnnounced = false;
            graceTicksUsed = 0;
        }
    }

    /** Serialises the persistent fields. Keys are the save format. */
    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("player", playerId);
        tag.putInt("remaining", remainingTicks);
        tag.putInt("granted", grantedTicks);
        tag.putString("source", source);
        tag.putInt("balls_loaned", ballsLoaned);
        ListTag catchList = new ListTag();
        for (CatchRecord record : catches) {
            catchList.add(record.save());
        }
        tag.put("catches", catchList);
        if (returnPoint != null) {
            tag.put("return", returnPoint.save());
        }
        return tag;
    }

    /** Reads a compound written by {@link #save()}, or null if it has no player id. */
    @Nullable
    static SafariSession load(CompoundTag tag) {
        if (!tag.hasUUID("player")) {
            return null;
        }
        ReturnPoint returnPoint = tag.contains("return", Tag.TAG_COMPOUND)
                ? ReturnPoint.load(tag.getCompound("return"))
                : null;
        // Absent keys (sessions saved by 0.3.0 and earlier) read as 0 / empty,
        // which is exactly "no loan, no catch yet".
        SafariSession session = new SafariSession(tag.getUUID("player"), Math.max(0, tag.getInt("remaining")),
                tag.getInt("granted"), returnPoint, tag.getString("source"), Math.max(0, tag.getInt("balls_loaned")));
        ListTag catchList = tag.getList("catches", Tag.TAG_COMPOUND);
        for (int i = 0; i < catchList.size(); i++) {
            CatchRecord record = CatchRecord.load(catchList.getCompound(i));
            if (record != null) {
                session.catches.add(record);
            }
        }
        return session;
    }
}
