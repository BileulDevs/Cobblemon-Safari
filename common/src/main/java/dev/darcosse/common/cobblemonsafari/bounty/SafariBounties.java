package dev.darcosse.common.cobblemonsafari.bounty;

import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.network.SafariBountiesPayload;
import dev.darcosse.common.cobblemonsafari.platform.Platform;
import dev.darcosse.common.cobblemonsafari.points.SafariPoints;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.spawn.HabitatEntry;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import dev.darcosse.common.cobblemonsafari.spawn.SpawnSource;
import dev.darcosse.common.cobblemonsafari.spawn.SpeciesRules;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Bounties: the missions of the day, drawn with each rotation of the
 * specials, the same for every player, and paid once per player in Safari
 * points when completed.
 *
 * <p>The draw is deterministic (rotation number and world salt, like the
 * specials), so it needs no saving and survives restarts. Only each player's
 * progress is saved ({@link SafariBountyStore}). Server side.
 */
public final class SafariBounties {

    /**
     * Mixed into the draw seed so that missions and specials, drawn from the
     * same rotation and salt, are not correlated. Any constant works; changing
     * it redraws today's missions.
     */
    private static final long SEED_MIX = 0x5AFA_B0B7_1E5L;

    /** Ticks between two checks for a new rotation (and a redraw to push). */
    private static final int CHECK_INTERVAL_TICKS = Ticks.PER_SECOND;

    /** A common species is asked twice, an uncommon one once: both take about as long. */
    private static final int COMMON_SPECIES_COUNT = 2;
    private static final int UNCOMMON_SPECIES_COUNT = 1;

    /** Draw attempts before giving up on filling a slot (the pool may be too small for distinct missions). */
    private static final int DRAW_ATTEMPTS = 20;

    private static List<Bounty> current = List.of();
    private static long currentPeriod = Long.MIN_VALUE;
    private static long currentRevision = Long.MIN_VALUE;
    private static int tickCounter;
    /** Missions last broadcast, to push only when they change. */
    private static List<Bounty> sent;

    /**
     * How many Pokémon of a type a "catch N of a type" mission asks for,
     * drawn in this range. Higher makes the mission a long hunt in a single
     * rotation; the reward is per catch, so it scales anyway. Quoted in the
     * comment of bounties.typeRewardPerCatch (SafariConfig): keep both in step.
     */
    private static final int TYPE_MIN_COUNT = 3;
    private static final int TYPE_MAX_COUNT = 5;

    private SafariBounties() {
    }

    /** Subscribes to captures. Called once from common init. */
    public static void register() {
        // LOWEST: the capture is final, as for points and catch records.
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.LOWEST, SafariBounties::onCaptured);
    }

    /** Forgets cached missions; call when a server starts, since single player reuses this class. */
    public static void reset() {
        current = List.of();
        currentPeriod = Long.MIN_VALUE;
        currentRevision = Long.MIN_VALUE;
        tickCounter = 0;
        sent = null;
    }

    /**
     * Today's missions; empty when switched off or while Cobblemon's species
     * are not loaded yet. Redrawn when the rotation or the habitat index
     * changes (data or config reload).
     */
    public static List<Bounty> today(MinecraftServer server) {
        SafariConfig.Bounties config = SafariConfigManager.get().bounties;
        if (!config.enabled || config.count == 0) {
            return List.of();
        }
        long period = FeaturedRotation.period();
        // today() first: it may start a new rotation, which moves the revision.
        List<HabitatEntry> specials = FeaturedRotation.today(server);
        long revision = FeaturedRotation.revision();
        if (period != currentPeriod || revision != currentRevision || current.isEmpty()) {
            current = draw(server, period, config, !specials.isEmpty());
            currentPeriod = period;
            currentRevision = revision;
        }
        return current;
    }

    /**
     * Runs from the server tick: once a second, pushes the missions to every
     * player when they changed. One counter increment per tick otherwise.
     */
    public static void tick(MinecraftServer server) {
        if (++tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        List<Bounty> bounties = today(server);
        if (!bounties.equals(sent)) {
            sent = bounties;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                sendTo(player);
            }
        }
    }

    /** Sends today's missions and the player's progress. */
    public static void sendTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<Bounty> bounties = today(server);
        SafariBountyStore.Progress progress = SafariBountyStore.get(server)
                .progress(player.getUUID(), currentPeriod, bounties.size());
        List<Integer> counts = new ArrayList<>(bounties.size());
        for (int count : progress.counts) {
            counts.add(count);
        }
        SafariConfig.Bounties config = SafariConfigManager.get().bounties;
        Platform.get().sendToPlayer(player, new SafariBountiesPayload(config.enabled, bounties, counts,
                config.allDoneBonus));
    }

    /** Chat lines of the clerk's "missions" answer: each mission with the player's progress and reward. */
    public static List<Component> lines(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        List<Component> lines = new ArrayList<>();
        List<Bounty> bounties = server == null ? List.of() : today(server);
        if (bounties.isEmpty()) {
            lines.add(Component.translatable(key("bounty.none")).withStyle(ChatFormatting.GRAY));
            return lines;
        }
        SafariBountyStore.Progress progress = SafariBountyStore.get(server)
                .progress(player.getUUID(), currentPeriod, bounties.size());
        lines.add(Component.translatable(key("bounty.header")).withStyle(ChatFormatting.GOLD));
        for (int i = 0; i < bounties.size(); i++) {
            Bounty bounty = bounties.get(i);
            boolean done = progress.counts[i] >= bounty.count();
            lines.add(Component.literal(done ? "✔ " : "• ")
                    .append(bounty.describe())
                    .append(Component.literal(" " + Math.min(progress.counts[i], bounty.count()) + "/" + bounty.count()))
                    .append(Component.translatable(key("bounty.reward"), SafariPointsIcon.amount(bounty.reward())).withStyle(ChatFormatting.GOLD))
                    .withStyle(done ? ChatFormatting.GREEN : ChatFormatting.WHITE));
        }
        int bonus = SafariConfigManager.get().bounties.allDoneBonus;
        if (bonus > 0) {
            lines.add(Component.translatable(key("bounty.all_done_hint"), SafariPointsIcon.amount(bonus)).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    /** Counts a capture made inside the Safari toward every matching mission, paying those it completes. */
    private static void onCaptured(PokemonCapturedEvent event) {
        ServerPlayer player = event.getPlayer();
        MinecraftServer server = player.getServer();
        if (server == null || !SafariDimensions.isSafari(player.level())) {
            return;
        }
        List<Bounty> bounties = today(server);
        if (bounties.isEmpty()) {
            return;
        }
        SafariBountyStore store = SafariBountyStore.get(server);
        SafariBountyStore.Progress progress = store.progress(player.getUUID(), currentPeriod, bounties.size());
        Pokemon pokemon = event.getPokemon();
        boolean moved = false;
        for (int i = 0; i < bounties.size(); i++) {
            Bounty bounty = bounties.get(i);
            if (progress.counts[i] >= bounty.count() || !matches(server, bounty, pokemon)) {
                continue;
            }
            progress.counts[i]++;
            moved = true;
            if (progress.counts[i] >= bounty.count()) {
                complete(player, bounty);
            }
        }
        if (!moved) {
            return;
        }
        if (!progress.bonusPaid && allDone(bounties, progress)) {
            progress.bonusPaid = true;
            int bonus = SafariConfigManager.get().bounties.allDoneBonus;
            if (bonus > 0) {
                int added = SafariPoints.add(player, bonus);
                player.sendSystemMessage(Component.translatable(key("bounty.all_done"), SafariPointsIcon.amount(added))
                        .withStyle(ChatFormatting.GOLD));
            }
        }
        store.changed();
        sendTo(player);
    }

    /** Pays a completed mission and celebrates it. */
    private static void complete(ServerPlayer player, Bounty bounty) {
        int added = SafariPoints.add(player, bounty.reward());
        player.sendSystemMessage(Component.translatable(key("bounty.completed"), bounty.describe(), SafariPointsIcon.amount(added))
                .withStyle(ChatFormatting.GREEN));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.2F);
    }

    /** Whether a caught Pokémon counts toward a mission. */
    private static boolean matches(MinecraftServer server, Bounty bounty, Pokemon pokemon) {
        Species species = pokemon.getSpecies();
        return switch (bounty.kind()) {
            case TYPE -> {
                for (ElementalType type : pokemon.getTypes()) {
                    if (type.getName().equals(bounty.target())) {
                        yield true;
                    }
                }
                yield false;
            }
            case SPECIES -> species.getResourceIdentifier().toString().equals(bounty.target());
            case FEATURED -> FeaturedRotation.today(server).stream().anyMatch(entry -> entry.species() == species);
        };
    }

    /** Whether the player has completed every mission of the rotation. */
    private static boolean allDone(List<Bounty> bounties, SafariBountyStore.Progress progress) {
        for (int i = 0; i < bounties.size(); i++) {
            if (progress.counts[i] < bounties.get(i).count()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Draws the missions of a rotation. A specials mission first when there
     * are specials, then type and species missions in turn, all from
     * species that spawn in the Safari as common or uncommon, so every
     * mission can be done within one rotation.
     */
    private static List<Bounty> draw(MinecraftServer server, long period, SafariConfig.Bounties config,
                                     boolean specialsToday) {
        List<Species> pool = new ArrayList<>();
        Map<String, Integer> typeWeights = new LinkedHashMap<>();
        for (HabitatEntry entry : FeaturedRotation.index(server).values()) {
            // Natural rules, not the board's: missions are met with what
            // Cobblemon's spawner brings, so a species it never lets in would
            // make a mission impossible.
            if ((entry.rarity() != Rarity.COMMON && entry.rarity() != Rarity.UNCOMMON)
                    || !SpeciesRules.isAllowed(entry.species(), SpawnSource.NATURAL)
                    || SpeciesRules.isSpecial(entry.species())) {
                continue;
            }
            pool.add(entry.species());
            for (ElementalType type : entry.species().getTypes()) {
                typeWeights.merge(type.getName(), 1, Integer::sum);
            }
        }
        if (pool.isEmpty()) {
            return List.of();
        }
        Random random = new Random(period * 31L ^ SafariWorldState.get(server).rotationSalt() ^ SEED_MIX);
        List<Bounty> drawn = new ArrayList<>();
        if (specialsToday && config.featuredReward > 0) {
            drawn.add(new Bounty(Bounty.Kind.FEATURED, "", 1, config.featuredReward));
        }
        boolean typeTurn = true;
        while (drawn.size() < config.count) {
            Bounty next = null;
            for (int attempt = 0; attempt < DRAW_ATTEMPTS && next == null; attempt++) {
                Bounty candidate = typeTurn ? drawType(random, typeWeights, config) : drawSpecies(random, pool,
                        server, config);
                if (candidate != null && drawn.stream().noneMatch(b -> b.kind() == candidate.kind()
                        && b.target().equals(candidate.target()))) {
                    next = candidate;
                }
            }
            if (next == null) {
                break;
            }
            drawn.add(next);
            typeTurn = !typeTurn;
        }
        return List.copyOf(drawn);
    }

    /** A type mission, the type weighted by how many Safari species have it. */
    private static Bounty drawType(Random random, Map<String, Integer> weights, SafariConfig.Bounties config) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int roll = random.nextInt(total);
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                int count = TYPE_MIN_COUNT + random.nextInt(TYPE_MAX_COUNT - TYPE_MIN_COUNT + 1);
                return new Bounty(Bounty.Kind.TYPE, entry.getKey(), count, count * config.typeRewardPerCatch);
            }
        }
        return null;
    }

    /** A species mission: two of a common species or one of an uncommon one. */
    private static Bounty drawSpecies(Random random, List<Species> pool, MinecraftServer server,
                                      SafariConfig.Bounties config) {
        Species species = pool.get(random.nextInt(pool.size()));
        HabitatEntry entry = FeaturedRotation.index(server).get(species.getResourceIdentifier());
        int count = entry != null && entry.rarity() == Rarity.COMMON ? COMMON_SPECIES_COUNT : UNCOMMON_SPECIES_COUNT;
        return new Bounty(Bounty.Kind.SPECIES, species.getResourceIdentifier().toString(), count,
                config.speciesReward);
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
