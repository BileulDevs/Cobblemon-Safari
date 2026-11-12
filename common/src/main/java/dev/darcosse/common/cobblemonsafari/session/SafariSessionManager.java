package dev.darcosse.common.cobblemonsafari.session;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleRegistry;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.access.EntryResult;
import dev.darcosse.common.cobblemonsafari.capture.BallRules;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.loan.EscapeRopes;
import dev.darcosse.common.cobblemonsafari.loan.LoanedBalls;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.park.ParkBoundary;
import dev.darcosse.common.cobblemonsafari.park.SafariBorder;
import dev.darcosse.common.cobblemonsafari.reset.LiveReset;
import dev.darcosse.common.cobblemonsafari.stats.SafariStatistics;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.teleport.RestHouseTravel;
import dev.darcosse.common.cobblemonsafari.teleport.SafariTeleporter;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The server-side authority on Safari sessions: starts and ends them, runs the
 * clock, and keeps "who is inside" consistent with "who has a session".
 *
 * <p>Consistency is enforced by a periodic sweep rather than by reacting to
 * every way a player can change dimension (portals, /tp, /execute, respawn,
 * other mods' teleports). Events can be missed or fire in surprising orders;
 * a sweep over the actual state cannot. The cost is at most
 * {@link #CONSISTENCY_INTERVAL_TICKS} ticks of latency.
 */
public final class SafariSessionManager {

    /**
     * Ticks between two consistency sweeps. The sweep is O(online players);
     * lowering this tightens the reaction to a player sneaking in or out, at a
     * proportional CPU cost. One second is imperceptible and cheap.
     */
    private static final int CONSISTENCY_INTERVAL_TICKS = Ticks.PER_SECOND;

    /**
     * Ticks between two routine clock re-syncs. The client counts down on its
     * own between them, so this only corrects drift; lowering it costs one tiny
     * packet per player in the Safari per interval.
     */
    private static final int RESYNC_INTERVAL_TICKS = 5 * Ticks.PER_SECOND;

    /**
     * Time left, in seconds, from which a chat warning turns yellow, then
     * red. Above the first one it is white. They follow the default
     * warnings (5 minutes in yellow; 1 minute, 30 and 10 seconds in red);
     * a warning time added in the config takes the colour of its range.
     */
    private static final int WARNING_YELLOW_SECONDS = 300;
    private static final int WARNING_RED_SECONDS = 60;

    private static final List<SafariSessionListener> LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * Where each online player last stood outside the Safari, refreshed by
     * every sweep. A player who gets in without a session (a waystone,
     * another mod's teleport, a command) is sent back there rather than to
     * the world spawn, so a misdirected teleport costs them nothing. Not
     * saved: after a restart, a player logging in inside is handled by
     * {@link #settleJoined}. Server thread only.
     */
    private static final Map<UUID, ReturnPoint> LAST_OUTSIDE = new HashMap<>();

    /**
     * Players who asked to leave (the clerk's dialogue), handled at the start
     * of the next server tick ({@link #processLeaves}). The clerk's answer
     * runs from inside Cobblemon's dialogue handling, where a cross-dimension
     * teleport ends the session without moving the player; on the next tick
     * nothing of the dialogue is on the stack any more. Concurrent because nothing guarantees the dialogue
     * runs its actions on the server thread.
     */
    private static final Queue<UUID> PENDING_LEAVES = new ConcurrentLinkedQueue<>();

    /**
     * Players inside whose clock is stopped by their game mode, as of the
     * last tick: a change is what triggers the message and the HUD update.
     * Server thread only; trimmed to the online players by the consistency
     * sweep.
     */
    private static final Set<UUID> FROZEN = new HashSet<>();

    /**
     * Players inside whose clock is paused by where they are (a rest house)
     * or what they do (a travel cinematic), as of the last tick: a change is
     * what triggers the HUD update. No chat message, unlike {@link #FROZEN}:
     * walking in and out of a house would flood the chat. Server thread only;
     * trimmed like FROZEN.
     */
    private static final Set<UUID> RESTING = new HashSet<>();

    private static int tickCounter;

    private SafariSessionManager() {
    }

    /** Registers a listener notified of session starts, time changes and ends. */
    public static void addListener(SafariSessionListener listener) {
        LISTENERS.add(listener);
    }

    /** The session of a player, or null. */
    @Nullable
    public static SafariSession getSession(ServerPlayer player) {
        return SafariSessionStore.get(player.getServer()).get(player.getUUID());
    }

    /**
     * Starts a session and sends the player in. Prefer
     * {@link dev.darcosse.common.cobblemonsafari.access.SafariAccess#requestEntry},
     * which also applies entry requirements.
     */
    public static EntryResult start(ServerPlayer player, int durationTicks, String source) {
        SafariSessionStore store = SafariSessionStore.get(player.getServer());
        if (store.get(player.getUUID()) != null) {
            return EntryResult.denied(Component.translatable(key("message.already_inside")));
        }
        // Every way in goes through here: none while a live reset closes the map.
        if (LiveReset.isClosing()) {
            return EntryResult.denied(Component.translatable(key("message.reset_closed")));
        }
        // Teleporting a player out of a running battle leaves Cobblemon with a
        // participant in another dimension; the same reason exists for the
        // time-up grace period below.
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return EntryResult.denied(Component.translatable(key("message.in_battle")));
        }
        // An exempt player (creative or spectator) may come in with no time at
        // all: the session then holds 0 and stays frozen until they change
        // game mode, at which point it expires like any other.
        boolean freeEntry = durationTicks <= 0 && isClockExempt(player);
        int ticks = freeEntry ? 0 : clampDuration(durationTicks);
        boolean alreadyInside = SafariDimensions.isSafari(player.level());
        // A player already standing in the Safari (an exempt builder) has no
        // meaningful return point inside it; exiting then falls back to world spawn.
        ReturnPoint returnPoint = alreadyInside ? null : ReturnPoint.of(player);
        int ballsLoaned = SafariConfigManager.loanedBallItem() != null ? SafariConfigManager.get().balls.loan.count : 0;
        SafariSession session = new SafariSession(player.getUUID(), ticks, ticks, returnPoint, source, ballsLoaned);

        // Stored BEFORE the teleport: the consistency sweep must never see the
        // player inside without a session, even if a listener ticks the server.
        store.put(session);
        if (!alreadyInside && !SafariTeleporter.sendIntoSafari(player)) {
            store.remove(player.getUUID());
            return EntryResult.denied(Component.translatable(key("message.no_arrival")));
        }
        // Vanilla just sent the OVERWORLD border with the dimension change.
        SafariBorder.sendTo(player);

        // Lent after the teleport: anything that does not fit is dropped at the
        // arrival point, inside the Safari, where the reclaim rules still apply.
        int lent = LoanedBalls.lend(player, ballsLoaned);
        EscapeRopes.Lent rope = EscapeRopes.lend(player);
        boolean frozen = isClockExempt(player);
        setFrozen(player.getUUID(), frozen);
        // Recorded now so that the next tick only sends the HUD a real change;
        // the arrival may stand on a house's front steps.
        setResting(player.getUUID(), !frozen && isClockResting(player));
        SafariNetwork.sendTimer(player, session, isClockRunning(player));
        if (freeEntry) {
            player.sendSystemMessage(Component.translatable(key("message.entered_exempt")));
        } else {
            player.sendSystemMessage(Component.translatable(key("message.entered"), Ticks.format(ticks)));
            if (frozen) {
                player.sendSystemMessage(Component.translatable(key("message.clock_paused_exempt")));
            }
        }
        if (lent > 0) {
            player.sendSystemMessage(Component.translatable(key("message.balls_lent"), lent));
        } else if (!BallRules.carriesAllowed(player, SafariConfigManager.allowedBalls())) {
            // Without the loan, a player who came empty-handed would only find
            // out at the first throw; told now, while the gate's shop is near.
            player.sendSystemMessage(Component.translatable(key("message.no_allowed_balls")));
        }
        if (rope == EscapeRopes.Lent.GIVEN) {
            player.sendSystemMessage(Component.translatable(key("message.rope_lent")));
        } else if (rope == EscapeRopes.Lent.DROPPED) {
            player.sendSystemMessage(Component.translatable(key("message.rope_dropped")));
        }
        for (SafariSessionListener listener : LISTENERS) {
            listener.onStart(player, session);
        }
        return EntryResult.ok();
    }

    /**
     * Adds (or, with a negative delta, removes) time from a player's session.
     *
     * @return the new remaining ticks, or -1 when the player has no session
     */
    public static int addTime(ServerPlayer player, int deltaTicks) {
        SafariSession session = getSession(player);
        if (session == null) {
            return -1;
        }
        long target = (long) session.remainingTicks() + deltaTicks;
        return setTime(player, (int) Math.max(0, Math.min(Integer.MAX_VALUE, target)));
    }

    /**
     * Sets a player's remaining time. Zero makes the session expire on the next tick.
     *
     * @return the new remaining ticks, or -1 when the player has no session
     */
    public static int setTime(ServerPlayer player, int ticks) {
        SafariSessionStore store = SafariSessionStore.get(player.getServer());
        SafariSession session = store.get(player.getUUID());
        if (session == null) {
            return -1;
        }
        int previous = session.remainingTicks();
        session.setRemainingTicks(Math.min(ticks, SafariConfigManager.maxDurationTicks()));
        store.setDirty();
        SafariNetwork.sendTimer(player, session, isClockRunning(player));
        for (SafariSessionListener listener : LISTENERS) {
            listener.onTimeChanged(player, session, previous);
        }
        return session.remainingTicks();
    }

    /**
     * Ends a player's session, teleporting them out when the reason requires it.
     *
     * @return false when the player had no session
     */
    public static boolean end(ServerPlayer player, SessionEndReason reason) {
        SafariSession session = SafariSessionStore.get(player.getServer()).remove(player.getUUID());
        if (session == null) {
            return false;
        }
        // Removed first: the teleport below changes dimension, and nothing must
        // see a half-ended session in between.
        SafariNetwork.sendNoSession(player);
        if (reason.teleportsOut && SafariDimensions.isSafari(player.level())) {
            SafariTeleporter.sendOut(player, session.returnPoint());
        }
        // Taken back whatever the reason. On DEATH the player is still inside
        // and the balls usually dropped with the inventory: they stay on the
        // ground in the Safari, which is harmless.
        int returned = LoanedBalls.reclaim(player);
        EscapeRopes.reclaim(player);
        player.sendSystemMessage(Component.translatable(reason.translationKey()));
        player.sendSystemMessage(SafariRecap.build(player, session, returned));
        for (SafariSessionListener listener : LISTENERS) {
            listener.onEnd(player, session, reason);
        }
        return true;
    }

    /**
     * Sends a joining player the rules, the Safari border if inside, and the
     * state of their clock. Packets only: the player may not be placed in
     * their level yet (see SafariServerEvents.onPlayerJoin); moving them is
     * {@link #settleJoined}'s job.
     */
    public static void onPlayerJoin(ServerPlayer player) {
        // A position from before the login (another world in single player,
        // or a session before a restart) means nothing now.
        LAST_OUTSIDE.remove(player.getUUID());
        SafariNetwork.sendRules(player);
        if (SafariDimensions.isSafari(player.level())) {
            SafariBorder.sendTo(player);
        }
        SafariSession session = getSession(player);
        if (session != null) {
            SafariNetwork.sendTimer(player, session, isClockRunning(player));
        } else {
            SafariNetwork.sendNoSession(player);
        }
    }

    /**
     * Sends out a player who logged in inside the Safari without a session.
     * Called at the end of the tick they joined in, once they are fully
     * placed in their level.
     *
     * <p>Unlike the sweep, no creative/spectator exemption here: the
     * exemption is for builders who chose to go in, and logging in is not a
     * choice. Without this, a creative player whose session ended while
     * offline (or a single-player world opened with another account, which
     * inherits the host's position) would stay inside with no clock. A builder
     * who relogs simply teleports back in.
     */
    public static void settleJoined(ServerPlayer player) {
        if (getSession(player) == null && SafariDimensions.isSafari(player.level())) {
            SafariTeleporter.sendToWorldSpawn(player);
            player.sendSystemMessage(Component.translatable(key("message.no_session")));
        }
    }

    /**
     * Records a Pokémon caught by a player during their session and tells the
     * session listeners. Ignored when the player has no session.
     */
    public static void recordCatch(ServerPlayer player, CatchRecord record) {
        SafariSessionStore store = SafariSessionStore.get(player.getServer());
        SafariSession session = store.get(player.getUUID());
        if (session == null) {
            return;
        }
        session.recordCatch(record);
        store.setDirty();
        for (SafariSessionListener listener : LISTENERS) {
            listener.onCatch(player, session, record);
        }
    }

    /**
     * Adds a statistic change to the player's session recap: points earned
     * and missions completed. Other statistics are ignored, and so is a
     * player without a session (points earned outside a hunt).
     */
    public static void recordStat(ServerPlayer player, ResourceLocation stat, int amount) {
        if (amount <= 0 || player.getServer() == null) {
            return;
        }
        boolean points = stat.equals(SafariStatistics.POINTS_EARNED);
        if (!points && !stat.equals(SafariStatistics.MISSIONS_COMPLETED)) {
            return;
        }
        SafariSessionStore store = SafariSessionStore.get(player.getServer());
        SafariSession session = store.get(player.getUUID());
        if (session == null) {
            return;
        }
        if (points) {
            session.addPointsEarned(amount);
        } else {
            session.addMissionCompleted();
        }
        store.setDirty();
    }

    /**
     * Asks for a player to leave the Safari on the next server tick: their
     * session ends and they are sent out, or, inside without a session (an
     * exempt builder), they are simply sent to the world spawn. Safe to call
     * from any thread.
     */
    public static void requestLeave(ServerPlayer player) {
        PENDING_LEAVES.add(player.getUUID());
    }

    /** Handles the leave requests of the last tick; see {@link #PENDING_LEAVES}. */
    private static void processLeaves(MinecraftServer server) {
        UUID id;
        while ((id = PENDING_LEAVES.poll()) != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            // Logged out in between: the login rules take over.
            if (player == null || end(player, SessionEndReason.VOLUNTARY)) {
                continue;
            }
            if (SafariDimensions.isSafari(player.level())) {
                SafariTeleporter.sendToWorldSpawn(player);
                player.sendSystemMessage(Component.translatable(SessionEndReason.VOLUNTARY.translationKey()));
            }
        }
    }

    /** Ends the session of a player who died inside. The respawn takes them out. */
    public static void onPlayerDeath(ServerPlayer player) {
        if (SafariDimensions.isSafari(player.level())) {
            end(player, SessionEndReason.DEATH);
        }
    }

    /**
     * Runs every server tick.
     *
     * <p>Cost per tick: an emptiness check of the leave requests, one pass
     * over active sessions (a map lookup each). Every
     * {@link #CONSISTENCY_INTERVAL_TICKS}, one extra pass over online players.
     * Nothing here loads chunks except a boundary correction or an eviction,
     * which are rare.
     */
    public static void tick(MinecraftServer server) {
        tickCounter++;
        if (!PENDING_LEAVES.isEmpty()) {
            processLeaves(server);
        }
        SafariSessionStore store = SafariSessionStore.get(server);
        if (!store.isEmpty()) {
            tickSessions(server, store);
        }
        if (tickCounter % CONSISTENCY_INTERVAL_TICKS == 0) {
            enforceConsistency(server, store);
        }
    }

    /** Counts every running clock down by one tick and handles warnings, resyncs and expiry. */
    private static void tickSessions(MinecraftServer server, SafariSessionStore store) {
        SafariConfig config = SafariConfigManager.get();
        boolean resync = tickCounter % RESYNC_INTERVAL_TICKS == 0;
        boolean changed = false;
        // Collected, then handled after the loop: ending a session mutates the store.
        List<ServerPlayer> outOfTime = new ArrayList<>();

        for (SafariSession session : store.all()) {
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId());
            boolean inside = player != null && SafariDimensions.isSafari(player.level());
            boolean frozen = inside && isClockExempt(player);
            if (inside && setFrozen(session.playerId(), frozen)) {
                announceFrozen(player, session, frozen);
            }
            boolean resting = inside && !frozen && isClockResting(player);
            if (inside && setResting(session.playerId(), resting) && !frozen) {
                SafariNetwork.sendTimer(player, session, !resting);
            }
            boolean counting = (inside && !frozen && !resting) || (player == null && !config.hunt.timer.pauseWhileOffline);

            if (counting && session.remainingTicks() > 0) {
                session.tickDown();
                changed = true;
                if (inside && SafariConfigManager.warningTicks().contains(session.remainingTicks())) {
                    player.sendSystemMessage(Component.translatable(key("message.time_warning"),
                            Ticks.format(session.remainingTicks())).withStyle(warningColour(session.remainingTicks())));
                }
            }
            // A frozen clock never runs out: a free entry holds 0 until the
            // player leaves creative or spectator. A resting one does: a clock
            // already at zero (a battle's grace) must not let a player stay
            // for good by stepping into a house.
            if (inside && !frozen && session.remainingTicks() == 0) {
                outOfTime.add(player);
            } else if (inside && resync) {
                SafariNetwork.sendTimer(player, session, !frozen && !resting);
            }
        }
        if (changed) {
            store.setDirty();
        }
        for (ServerPlayer player : outOfTime) {
            handleTimeUp(player, store.get(player.getUUID()));
        }
    }

    /**
     * Escorts a player whose clock hit zero out, unless they are mid-battle and
     * still have grace left: tearing a battle apart with a teleport would leave
     * Cobblemon to clean up a battle whose participant vanished.
     */
    private static void handleTimeUp(ServerPlayer player, @Nullable SafariSession session) {
        if (session == null) {
            return;
        }
        boolean inBattle = BattleRegistry.getBattleByParticipatingPlayer(player) != null;
        if (inBattle && session.graceTicksUsed() < SafariConfigManager.battleGraceTicks()) {
            if (!session.isTimeUpAnnounced()) {
                session.markTimeUpAnnounced();
                SafariNetwork.sendTimer(player, session, false);
                player.sendSystemMessage(Component.translatable(key("message.time_up_after_battle")));
            }
            session.consumeGraceTick();
            return;
        }
        end(player, SessionEndReason.EXPIRED);
    }

    /**
     * Reconciles the world with the session store: keeps the Safari border in
     * place, evicts players inside without a session back to where they last
     * stood outside, ends sessions of players who got out by other means, and
     * brings back anyone outside the border.
     */
    private static void enforceConsistency(MinecraftServer server, SafariSessionStore store) {
        SafariBorder.enforce(server);
        // Copy: evictions and ends below change the player's level and the store.
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        for (ServerPlayer player : players) {
            boolean inside = SafariDimensions.isSafari(player.level());
            boolean hasSession = store.get(player.getUUID()) != null;
            boolean exempt = isClockExempt(player);

            // Loaned balls never survive outside the Safari, however the player
            // got out (end of session, crash, a mod teleport, a ball tossed to
            // a friend at the border...).
            if (!inside) {
                LoanedBalls.reclaim(player);
                EscapeRopes.reclaim(player);
            }
            if (!inside) {
                LAST_OUTSIDE.put(player.getUUID(), ReturnPoint.of(player));
            }
            if (inside && !hasSession && !exempt) {
                // Got in without going through a gate: whatever their banked
                // time, an entry only starts at a gate (or an operator's
                // /safari enter), which records the return point and lends the balls.
                SafariTeleporter.sendOut(player, LAST_OUTSIDE.get(player.getUUID()));
                player.sendSystemMessage(Component.translatable(key("message.no_session")));
            } else if (!inside && hasSession) {
                end(player, SessionEndReason.LEFT);
            } else if (inside && !exempt && ParkBoundary.isOutside(player.getX(), player.getZ())) {
                ParkBoundary.pullBack(player);
            }
        }
        // Forgets players who logged out, so the map stays the size of the player list.
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : players) {
            online.add(player.getUUID());
        }
        LAST_OUTSIDE.keySet().retainAll(online);
        FROZEN.retainAll(online);
        RESTING.retainAll(online);
    }

    /**
     * Takes a player out of the Safari for a live map reset: any battle
     * stopped first (a player teleported out of a battle leaves Cobblemon
     * with a participant in another dimension), then the hunt ended with
     * {@link SessionEndReason#MAP_RESET}, which gives the unused time back;
     * a player inside without a session (an exempt builder) is sent to where
     * they last stood outside.
     */
    public static void evictForReset(ServerPlayer player) {
        PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
        if (battle != null) {
            battle.stop();
        }
        if (!end(player, SessionEndReason.MAP_RESET)) {
            SafariTeleporter.sendOut(player, LAST_OUTSIDE.get(player.getUUID()));
            player.sendSystemMessage(Component.translatable(SessionEndReason.MAP_RESET.translationKey()));
        }
    }

    /**
     * Whether this player's Safari clock is stopped by their game mode:
     * {@code hunt.exemptCreativeAndSpectator} on, and in creative or spectator.
     * Such a player may also enter with no banked time, and roam without a
     * session.
     */
    public static boolean isClockExempt(ServerPlayer player) {
        return SafariConfigManager.get().hunt.exemptCreativeAndSpectator && (player.isCreative() || player.isSpectator());
    }

    /**
     * Whether a player's clock is paused by the config's comfort pauses:
     * standing in a built rest house ({@code hunt.timer.pauseInRestHouses}), whose
     * square holds the walls and the front steps, or riding a guide or
     * Escape Rope trip, cinematic included ({@code hunt.timer.pauseDuringTravel}).
     * Cost, per player inside and per tick: a map lookup, then a few
     * comparisons per house (about ten houses).
     */
    public static boolean isClockResting(ServerPlayer player) {
        SafariConfig.Timer timer = SafariConfigManager.get().hunt.timer;
        if (timer.pauseDuringTravel && RestHouseTravel.isTravelling(player)) {
            return true;
        }
        if (!timer.pauseInRestHouses) {
            return false;
        }
        RestHouses.HouseSite site = RestHouses.siteAt(player.getBlockX(), player.getBlockY(), player.getBlockZ(), 0);
        // A planned house is only a spot kept clear: nothing to rest in yet.
        return site != null && site.built();
    }

    /**
     * Whether a player's clock is counting down: inside the Safari, not
     * exempt by game mode, and not paused in a rest house or a trip.
     */
    private static boolean isClockRunning(ServerPlayer player) {
        return SafariDimensions.isSafari(player.level()) && !isClockExempt(player) && !isClockResting(player);
    }

    /**
     * Records whether a player's clock is frozen by their game mode.
     *
     * @return true when this changes what was recorded
     */
    private static boolean setFrozen(UUID playerId, boolean frozen) {
        return frozen ? FROZEN.add(playerId) : FROZEN.remove(playerId);
    }

    /**
     * Records whether a player's clock is paused by a rest house or a trip.
     *
     * @return true when this changes what was recorded
     */
    private static boolean setResting(UUID playerId, boolean resting) {
        return resting ? RESTING.add(playerId) : RESTING.remove(playerId);
    }

    /**
     * Tells a player inside that their clock just stopped or started again
     * after a game mode change, and updates their HUD at once rather than at
     * the next resync. Nothing is said when it starts again at zero: the
     * time-up message follows on the same tick.
     */
    private static void announceFrozen(ServerPlayer player, SafariSession session, boolean frozen) {
        SafariNetwork.sendTimer(player, session, !frozen);
        if (frozen) {
            player.sendSystemMessage(Component.translatable(key("message.clock_paused_exempt")));
        } else if (session.remainingTicks() > 0) {
            player.sendSystemMessage(Component.translatable(key("message.clock_resumed"),
                    Ticks.format(session.remainingTicks())));
        }
    }

    /** Keeps a requested duration between one second and the configured maximum. */
    private static int clampDuration(int ticks) {
        return Math.max(Ticks.PER_SECOND, Math.min(ticks, SafariConfigManager.maxDurationTicks()));
    }

    /** Colour of a chat warning, by the time left: see {@link #WARNING_YELLOW_SECONDS}. */
    private static ChatFormatting warningColour(int remainingTicks) {
        int seconds = Ticks.toSecondsCeil(remainingTicks);
        if (seconds <= WARNING_RED_SECONDS) {
            return ChatFormatting.RED;
        }
        return seconds <= WARNING_YELLOW_SECONDS ? ChatFormatting.YELLOW : ChatFormatting.WHITE;
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
