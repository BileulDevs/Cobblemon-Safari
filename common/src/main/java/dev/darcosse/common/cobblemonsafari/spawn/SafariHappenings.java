package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.capture.ThrowTracker;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import dev.darcosse.common.cobblemonsafari.util.HabitatNames;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the Safari's rare happenings ({@code events}) up to date for the
 * spawn threads and tells the players about them: the swarm of the rotation
 * ({@link Swarm}) once per rotation to each player in the Safari, the full
 * moon once per night, the downpour ({@link Downpour}), the storm
 * ({@link Storm}), the heatwave ({@link Heatwave}) and the aurora
 * ({@link Aurora}) when they start and end; the storm also brings its
 * Pokémon from here, the aurora its promised ones.
 *
 * <p>The effect on the spawns themselves is {@link SafariHappeningsInfluence}'s.
 * Server side. Cost: once a second, a few comparisons, and the swarm's draw
 * when a rotation starts.
 */
public final class SafariHappenings {

    /** How often the happenings are checked, in ticks. A player entering hears of them at most this late. */
    private static final int CHECK_INTERVAL_TICKS = Ticks.PER_SECOND;

    /** Phase of the full moon in Minecraft's moon cycle (0 to 7). */
    private static final int FULL_MOON_PHASE = 0;

    /** Volume and pitch of the notice sound, heard by the told player only. */
    private static final float NOTICE_VOLUME = 0.8F;
    private static final float NOTICE_PITCH = 1.2F;

    /** Whether a full moon night is on in the Safari, for the spawn threads. */
    private static volatile boolean fullMoon;
    /** Lower-case type names the full moon favours, for the spawn threads. */
    private static volatile Set<String> fullMoonTypes = Set.of();

    /** Per player, the swarm last announced to them (rotation and species), so each hears of it once. */
    private static final Map<UUID, String> SWARM_TOLD = new HashMap<>();
    /** Players told of the current full moon night; emptied when the night ends. */
    private static final Set<UUID> MOON_TOLD = new HashSet<>();
    private static int tickCounter;

    private SafariHappenings() {
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    public static void reset() {
        fullMoon = false;
        fullMoonTypes = Set.of();
        SWARM_TOLD.clear();
        MOON_TOLD.clear();
        tickCounter = 0;
        Swarm.reset();
        Downpour.reset();
        Storm.reset();
        Heatwave.reset();
        Aurora.reset();
        EggNests.reset();
    }

    /** Whether a full moon night is on, for the spawn threads. */
    static boolean fullMoon() {
        return fullMoon;
    }

    /** Whether a full moon night is on in the Safari right now (as of the last second's check). */
    public static boolean isFullMoon() {
        return fullMoon;
    }

    /** Type names the full moon favours, lower case, for the spawn threads. */
    static Set<String> fullMoonTypes() {
        return fullMoonTypes;
    }

    /** Updates the happenings and announces them. Called each server tick. */
    public static void tick(MinecraftServer server) {
        if (++tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        ServerLevel safari = SafariDimensions.level(server);
        SafariConfig.FullMoon moon = SafariConfigManager.get().events.fullMoon;
        boolean moonNow = safari != null && moon.enabled && !moon.types.isEmpty()
                && safari.getMoonPhase() == FULL_MOON_PHASE && ThrowTracker.isNight(safari.getDayTime());
        Set<String> types = new HashSet<>();
        for (String type : moon.types) {
            types.add(type.toLowerCase(Locale.ROOT));
        }
        fullMoonTypes = Set.copyOf(types);
        fullMoon = moonNow;
        if (!moonNow) {
            MOON_TOLD.clear();
        }
        Downpour.tick(safari);
        Storm.tick(server, safari);
        Heatwave.tick(safari);
        Aurora.tick(server, safari);
        EggNests.tick(safari);
        Swarm.Active swarm = Swarm.current(server);
        if (safari == null) {
            return;
        }
        String swarmKey = swarm == null ? null : FeaturedRotation.period() + "|" + swarm.speciesId();
        for (ServerPlayer player : safari.players()) {
            if (swarmKey != null && !swarmKey.equals(SWARM_TOLD.get(player.getUUID()))) {
                SWARM_TOLD.put(player.getUUID(), swarmKey);
                tell(player, swarmMessage(swarm));
            }
            if (moonNow && MOON_TOLD.add(player.getUUID())) {
                tell(player, moonMessage(moon.types));
            }
        }
    }

    /** The swarm's line, also shown by {@code /safari today}. */
    public static Component swarmMessage(Swarm.Active swarm) {
        Component species = swarm.species().getTranslatedName().copy().withStyle(swarm.rarity().color);
        // Short name, as on the specials list this line is part of.
        Component habitat = HabitatNames.name(swarm.biome().location()).copy().withStyle(ChatFormatting.WHITE);
        return Component.translatable(key("events.swarm.announce"), species, habitat)
                .withStyle(ChatFormatting.GOLD);
    }

    /** The full moon's line, naming the favoured types. */
    private static Component moonMessage(List<String> typeNames) {
        return Component.translatable(key("events.full_moon.announce"), typeNames(typeNames))
                .withStyle(ChatFormatting.AQUA);
    }

    /**
     * Cobblemon type names as their translated names, comma separated; a
     * name Cobblemon does not know is shown as written.
     */
    static Component typeNames(List<String> typeNames) {
        MutableComponent types = Component.empty();
        boolean first = true;
        for (String name : typeNames) {
            ElementalType type = ElementalTypes.get(name.toLowerCase(Locale.ROOT));
            if (!first) {
                types.append(", ");
            }
            types.append(type != null ? type.getDisplayName() : Component.literal(name));
            first = false;
        }
        return types;
    }

    /** Sends a notice with a short chime. */
    static void tell(ServerPlayer player, Component message) {
        player.sendSystemMessage(message);
        player.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, NOTICE_VOLUME, NOTICE_PITCH);
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
