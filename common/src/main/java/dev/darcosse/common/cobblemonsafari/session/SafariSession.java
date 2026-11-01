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
    /**
     * Wall-clock time the session started, in epoch milliseconds, for the
     * advancements listed in the recap; 0 for a session saved before 1.4.0,
     * whose recap then lists none.
     */
    private long startedAtMillis;
    /** Recap tally: Safari points earned during the session (captures and missions). */
    private int pointsEarned;
    /** Recap tally: missions completed during the session. */
    private int missionsCompleted;
    /** Recap tally: species caught for the first time ever during the session. */
    private int newSpecies;

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
        this.startedAtMillis = System.currentTimeMillis();
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

    /** Wall-clock start of the session, in epoch milliseconds; 0 when unknown (saved before 1.4.0). */
    public long startedAtMillis() {
        return startedAtMillis;
    }

    /** Safari points earned during the session. */
    public int pointsEarned() {
        return pointsEarned;
    }

    /** Missions completed during the session. */
    public int missionsCompleted() {
        return missionsCompleted;
    }

    /** Species caught for the first time ever during the session. */
    public int newSpecies() {
        return newSpecies;
    }

    /** Adds earned points to the recap tally; the manager marks the store dirty. */
    void addPointsEarned(int points) {
        pointsEarned += Math.max(0, points);
    }

    /** Counts a completed mission in the recap tally; the manager marks the store dirty. */
    void addMissionCompleted() {
        missionsCompleted++;
    }

    /**
     * Counts a species new to the player in the recap tally. Public for the
     * statistics listener, which is the one that knows; it runs inside
     * {@link SafariSessionManager#recordCatch}, which has already marked the
     * store dirty. Touches nothing the HUD shows.
     */
    public void addNewSpecies() {
        newSpecies++;
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
        tag.putLong("started_at", startedAtMillis);
        tag.putInt("points_earned", pointsEarned);
        tag.putInt("missions_completed", missionsCompleted);
        tag.putInt("new_species", newSpecies);
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
        // Absent keys read as 0 / empty, which is exactly "no loan, no catch yet".
        SafariSession session = new SafariSession(tag.getUUID("player"), Math.max(0, tag.getInt("remaining")),
                tag.getInt("granted"), returnPoint, tag.getString("source"), Math.max(0, tag.getInt("balls_loaned")));
        // Absent before 1.4.0: 0, which the recap reads as "unknown" for the start, "none" for the tallies.
        session.startedAtMillis = tag.getLong("started_at");
        session.pointsEarned = Math.max(0, tag.getInt("points_earned"));
        session.missionsCompleted = Math.max(0, tag.getInt("missions_completed"));
        session.newSpecies = Math.max(0, tag.getInt("new_species"));
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
