package dev.darcosse.common.cobblemonsafari.bounty;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokeball.PokemonCatchRateEvent;
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.properties.HiddenAbilityProperty;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.BountyKindSettings;
import dev.darcosse.common.cobblemonsafari.config.BountyKinds;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.network.SafariBountiesPayload;
import dev.darcosse.common.cobblemonsafari.platform.Platform;
import dev.darcosse.common.cobblemonsafari.points.SafariPoints;
import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.spawn.HabitatEntry;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import dev.darcosse.common.cobblemonsafari.stats.SafariStatistics;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Bounties: the missions of the rotation, drawn with each rotation of the
 * specials among the kinds {@code bounties.allowedKinds} lets in, the same
 * for every player, and paid once per player in Safari points when
 * completed. A player may swap one at the clerk for another, for themselves
 * only.
 *
 * <p>The draws are deterministic (rotation number and world salt, like the
 * specials, plus the player and slot for a swap), so the shared missions
 * need no saving and survive restarts. Each player's progress and swapped
 * missions are saved ({@link SafariBountyStore}). Server side.
 */
public final class SafariBounties {

    /**
     * Mixed into the draw seed so that missions and specials, drawn from the
     * same rotation and salt, are not correlated. Any constant works; changing
     * it redraws today's missions.
     */
    private static final long SEED_MIX = 0x5AFA_B0B7_1E5L;

    /**
     * Most missions per rotation (bounties.count is clamped to it). The
     * clerk's dialogue has one swap answer per mission, so raising it needs
     * as many new answers in the dialogue files, and as many functions
     * (SafariDialogueFunctions registers them up to this).
     */
    public static final int MAX_MISSIONS = 8;

    /** Mixed into a swap's seed, for the same reason against the shared draw. */
    private static final long SWAP_SEED_MIX = 0x5A_F0_C1E7L;

    /** Ticks between two checks for a new rotation (and a redraw to push). */
    private static final int CHECK_INTERVAL_TICKS = Ticks.PER_SECOND;

    /** Draw attempts per mission before giving up on the slot (the pool may be too small for distinct missions). */
    private static final int DRAW_ATTEMPTS = 20;

    /**
     * Fewest species a habitat must offer for a habitat mission to name it:
     * fewer and the mission is in effect a species mission, often a slow one.
     */
    private static final int MIN_HABITAT_SPECIES = 3;

    /** The level a level mission asks is a multiple of this: "level 25 or higher" reads better than 27. */
    private static final int LEVEL_STEP = 5;

    /**
     * Night as Cobblemon's spawner counts it (its "night" time range, in
     * ticks of the day), so that a night mission and the night spawns agree.
     */
    private static final long NIGHT_START = 12_542L;
    private static final long NIGHT_END = 23_459L;
    private static final long TICKS_PER_DAY = 24_000L;

    private static List<Bounty> current = List.of();
    private static long currentPeriod = Long.MIN_VALUE;
    private static long currentRevision = Long.MIN_VALUE;
    private static int tickCounter;
    /** Missions last broadcast, to push only when they change. */
    private static List<Bounty> sent;

    /**
     * Balls thrown at a Pokémon in a battle, recorded when Cobblemon computes
     * the catch rate (which it does for every throw, in battle or not) and
     * read on capture. Weak: a ball that missed or broke free is forgotten
     * with its entity.
     */
    private static final Map<EmptyPokeBallEntity, Boolean> IN_BATTLE = new WeakHashMap<>();

    private SafariBounties() {
    }

    /** Subscribes to throws and captures. Called once from common init. */
    public static void register() {
        // LOWEST: the capture is final, as for points and catch records.
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.LOWEST, SafariBounties::onCaptured);
        CobblemonEvents.POKEMON_CATCH_RATE.subscribe(Priority.LOWEST, SafariBounties::onCatchRate);
    }

    /** Forgets cached missions; call when a server starts, since single player reuses this class. */
    public static void reset() {
        current = List.of();
        currentPeriod = Long.MIN_VALUE;
        currentRevision = Long.MIN_VALUE;
        tickCounter = 0;
        sent = null;
        IN_BATTLE.clear();
        BountyPool.invalidate();
    }

    /**
     * The shared missions of the rotation; empty when switched off or while
     * Cobblemon's species are not loaded yet. Redrawn when the rotation or
     * the habitat index changes (data or config reload).
     */
    public static List<Bounty> today(MinecraftServer server) {
        SafariConfig.Bounties config = SafariConfigManager.get().bounties;
        if (!config.enabled || config.count == 0) {
            return List.of();
        }
        long period = FeaturedRotation.period();
        // today() first: it may start a new rotation, which moves the revision.
        FeaturedRotation.today(server);
        long revision = FeaturedRotation.revision();
        if (period != currentPeriod || revision != currentRevision || current.isEmpty()) {
            if (revision != currentRevision) {
                // A data or config reload: the spawns or the filter may have changed.
                BountyPool.invalidate();
            }
            current = draw(server, period, config);
            currentPeriod = period;
            currentRevision = revision;
        }
        return current;
    }

    /**
     * A player's missions: the shared ones, with those the player swapped in
     * their slots.
     */
    public static List<Bounty> missionsOf(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return List.of();
        }
        List<Bounty> shared = today(server);
        return missionsOf(shared, progressOf(server, player, shared));
    }

    private static List<Bounty> missionsOf(List<Bounty> shared, SafariBountyStore.Progress progress) {
        if (progress.swapped.isEmpty()) {
            return shared;
        }
        List<Bounty> mine = new ArrayList<>(shared);
        progress.swapped.forEach(mine::set);
        return List.copyOf(mine);
    }

    private static SafariBountyStore.Progress progressOf(MinecraftServer server, ServerPlayer player,
                                                         List<Bounty> shared) {
        return SafariBountyStore.get(server).progress(player.getUUID(), currentPeriod, shared.size());
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

    /** Sends the player's missions and progress. */
    public static void sendTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<Bounty> shared = today(server);
        SafariBountyStore.Progress progress = progressOf(server, player, shared);
        List<Integer> counts = new ArrayList<>(shared.size());
        for (int count : progress.counts) {
            counts.add(count);
        }
        SafariConfig.Bounties config = SafariConfigManager.get().bounties;
        Platform.get().sendToPlayer(player, new SafariBountiesPayload(config.enabled, missionsOf(shared, progress),
                counts, config.allDoneBonus));
    }

    /** Chat lines of the clerk's "missions" answer: each mission with the player's progress and reward. */
    public static List<Component> lines(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        List<Component> lines = new ArrayList<>();
        List<Bounty> shared = server == null ? List.of() : today(server);
        if (shared.isEmpty()) {
            lines.add(Component.translatable(key("bounty.none")).withStyle(ChatFormatting.GRAY));
            return lines;
        }
        SafariBountyStore.Progress progress = progressOf(server, player, shared);
        List<Bounty> bounties = missionsOf(shared, progress);
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

    // ---------------------------------------------------------------- swaps

    /**
     * Whether the clerk offers a swap: swaps are on, the player has one left
     * this rotation and a mission not done yet. Points are checked on the
     * swap itself, so that a player short of them is told why.
     */
    public static boolean canSwapAny(ServerPlayer player) {
        List<Bounty> mine = missionsOf(player);
        for (int slot = 0; slot < mine.size(); slot++) {
            if (canSwap(player, slot)) {
                return true;
            }
        }
        return false;
    }

    /** Whether mission {@code slot} (from 0) of the player can be swapped. */
    public static boolean canSwap(ServerPlayer player, int slot) {
        MinecraftServer server = player.getServer();
        SafariConfig.Bounties config = SafariConfigManager.get().bounties;
        if (server == null || !config.swap.enabled || config.swap.perRotation == 0) {
            return false;
        }
        List<Bounty> shared = today(server);
        if (slot < 0 || slot >= shared.size()) {
            return false;
        }
        SafariBountyStore.Progress progress = progressOf(server, player, shared);
        return progress.swaps < config.swap.perRotation
                && progress.counts[slot] < missionsOf(shared, progress).get(slot).count();
    }

    /**
     * Replaces mission {@code slot} of the player with another, for
     * {@code bounties.swap.cost} points. The new mission starts from zero.
     * Tells the player the outcome in chat.
     *
     * @return whether the mission was replaced
     */
    public static boolean swap(ServerPlayer player, int slot) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        SafariConfig.Bounties config = SafariConfigManager.get().bounties;
        if (!canSwap(player, slot)) {
            // Out of swaps is the one reason worth naming; the others (switched off, mission
            // done or gone) only happen with a stale dialogue page.
            String reason = config.swap.enabled && swapsLeft(player) == 0 ? "bounty.swap.none_left"
                    : "bounty.swap.unavailable";
            player.sendSystemMessage(Component.translatable(key(reason)).withStyle(ChatFormatting.RED));
            return false;
        }
        List<Bounty> shared = today(server);
        SafariBountyStore store = SafariBountyStore.get(server);
        SafariBountyStore.Progress progress = progressOf(server, player, shared);
        List<Bounty> mine = missionsOf(shared, progress);
        Random random = new Random(currentPeriod * 31L ^ SafariWorldState.get(server).rotationSalt() ^ SWAP_SEED_MIX
                ^ player.getUUID().getMostSignificantBits() ^ player.getUUID().getLeastSignificantBits() * 17L
                ^ (long) slot << 40 ^ (long) progress.swaps << 48);
        Bounty replacement = drawOne(server, random, config, mine);
        if (replacement == null) {
            player.sendSystemMessage(Component.translatable(key("bounty.swap.no_other")).withStyle(ChatFormatting.RED));
            return false;
        }
        if (config.swap.cost > 0 && !SafariPoints.spend(player, config.swap.cost)) {
            player.sendSystemMessage(Component.translatable(key("bounty.swap.no_points"),
                    SafariPointsIcon.amount(config.swap.cost)).withStyle(ChatFormatting.RED));
            return false;
        }
        SafariStatistics.add(player, SafariStatistics.POINTS_SPENT, config.swap.cost);
        progress.swapped.put(slot, replacement);
        progress.clearSlot(slot);
        progress.swaps++;
        store.changed();
        player.sendSystemMessage(Component.translatable(key("bounty.swap.done"), replacement.describe(),
                SafariPointsIcon.amount(replacement.reward())).withStyle(ChatFormatting.GREEN));
        sendTo(player);
        return true;
    }

    /** Swaps the player has left this rotation, for the clerk's prompt. */
    public static int swapsLeft(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return 0;
        }
        SafariBountyStore.Progress progress = progressOf(server, player, today(server));
        return Math.max(0, SafariConfigManager.get().bounties.swap.perRotation - progress.swaps);
    }

    // ------------------------------------------------------------- captures

    /** Remembers whether a ball was thrown in a battle, for the out-of-battle missions. */
    private static void onCatchRate(PokemonCatchRateEvent event) {
        if (event.getPokeBallEntity() != null && event.getPokemonEntity() != null) {
            IN_BATTLE.put(event.getPokeBallEntity(), event.getPokemonEntity().getBattleId() != null);
        }
    }

    /** Counts a capture made inside the Safari toward every matching mission, paying those it completes. */
    private static void onCaptured(PokemonCapturedEvent event) {
        ServerPlayer player = event.getPlayer();
        MinecraftServer server = player.getServer();
        if (server == null || !SafariDimensions.isSafari(player.level())) {
            return;
        }
        List<Bounty> shared = today(server);
        if (shared.isEmpty()) {
            return;
        }
        SafariBountyStore store = SafariBountyStore.get(server);
        SafariBountyStore.Progress progress = progressOf(server, player, shared);
        List<Bounty> bounties = missionsOf(shared, progress);
        Capture capture = Capture.of(server, player, event);
        boolean moved = false;
        for (int i = 0; i < bounties.size(); i++) {
            Bounty bounty = bounties.get(i);
            if (progress.counts[i] >= bounty.count() || !matches(bounty, capture, progress.seen.get(i))) {
                continue;
            }
            if (bounty.kind() == BountyKind.DIFFERENT) {
                progress.seen.get(i).add(capture.speciesId);
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
                SafariStatistics.add(player, SafariStatistics.POINTS_EARNED, added);
                player.sendSystemMessage(Component.translatable(key("bounty.all_done"), SafariPointsIcon.amount(added))
                        .withStyle(ChatFormatting.GOLD));
            }
        }
        store.changed();
        sendTo(player);
    }

    /**
     * What the missions need to know of a capture, read once for all of
     * them.
     */
    private record Capture(Pokemon pokemon, String speciesId, boolean featured, boolean inBattle, boolean night,
                           String biome) {

        static Capture of(MinecraftServer server, ServerPlayer player, PokemonCapturedEvent event) {
            Pokemon pokemon = event.getPokemon();
            Species species = pokemon.getSpecies();
            boolean featured = FeaturedRotation.today(server).stream().anyMatch(entry -> entry.species() == species);
            EmptyPokeBallEntity ball = event.getPokeBallEntity();
            // A ball the catch rate never saw (another mod's capture) counts as out of battle.
            Boolean inBattle = ball == null ? null : IN_BATTLE.remove(ball);
            // Where the Pokémon was: the ball lies on it. The player when there is no ball.
            Entity where = ball != null ? ball : player;
            long time = Math.floorMod(where.level().getDayTime(), TICKS_PER_DAY);
            String biome = where.level().getBiome(where.blockPosition()).unwrapKey()
                    .map(ResourceKey::location).map(Object::toString).orElse("");
            return new Capture(pokemon, species.getResourceIdentifier().toString(), featured,
                    Boolean.TRUE.equals(inBattle), time >= NIGHT_START && time <= NIGHT_END, biome);
        }
    }

    /** Whether a capture counts toward a mission; {@code seen} is the mission's already counted species. */
    private static boolean matches(Bounty bounty, Capture capture, Set<String> seen) {
        Pokemon pokemon = capture.pokemon;
        return switch (bounty.kind()) {
            case TYPE -> {
                for (ElementalType type : pokemon.getTypes()) {
                    if (type.getName().equals(bounty.target())) {
                        yield true;
                    }
                }
                yield false;
            }
            case SPECIES -> capture.speciesId.equals(bounty.target());
            case FEATURED -> capture.featured;
            case HABITAT -> capture.biome.equals(bounty.target());
            case ANY -> true;
            case DIFFERENT -> !seen.contains(capture.speciesId);
            case OUT_OF_BATTLE -> !capture.inBattle;
            case NIGHT -> capture.night;
            case EVOLVED -> pokemon.getSpecies().getEvolutions().isEmpty()
                    && pokemon.getSpecies().getPreEvolution() != null;
            case LEVEL -> pokemon.getLevel() >= bounty.level();
            case HIDDEN_ABILITY -> new HiddenAbilityProperty(true).matches(pokemon);
            case SHINY -> pokemon.getShiny();
        };
    }

    /** Pays a completed mission and celebrates it. */
    private static void complete(ServerPlayer player, Bounty bounty) {
        int added = SafariPoints.add(player, bounty.reward());
        SafariStatistics.add(player, SafariStatistics.POINTS_EARNED, added);
        SafariStatistics.increment(player, SafariStatistics.MISSIONS_COMPLETED);
        player.sendSystemMessage(Component.translatable(key("bounty.completed"), bounty.describe(), SafariPointsIcon.amount(added))
                .withStyle(ChatFormatting.GREEN));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.2F);
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

    // ---------------------------------------------------------------- draws

    /**
     * Draws the shared missions of a rotation: {@code bounties.count}
     * missions, each of a kind drawn by weight among the allowed kinds, all
     * different kinds while there are enough of them.
     */
    private static List<Bounty> draw(MinecraftServer server, long period, SafariConfig.Bounties config) {
        Random random = new Random(period * 31L ^ SafariWorldState.get(server).rotationSalt() ^ SEED_MIX);
        List<Bounty> drawn = new ArrayList<>();
        while (drawn.size() < config.count) {
            Bounty next = drawOne(server, random, config, drawn);
            if (next == null) {
                break;
            }
            drawn.add(next);
        }
        return List.copyOf(drawn);
    }

    /**
     * One mission unlike any in {@code taken}: of a kind none of them has
     * when one can be drawn, otherwise of any allowed kind. Null when no
     * allowed kind can make a new mission (pool too small, kinds all off).
     */
    private static Bounty drawOne(MinecraftServer server, Random random, SafariConfig.Bounties config,
                                  List<Bounty> taken) {
        Set<BountyKind> takenKinds = new HashSet<>();
        taken.forEach(bounty -> takenKinds.add(bounty.kind()));
        List<BountyKind> fresh = new ArrayList<>();
        List<BountyKind> all = new ArrayList<>();
        for (BountyKind kind : config.allowedKinds) {
            if (config.kinds.of(kind).weight > 0) {
                all.add(kind);
                if (!takenKinds.contains(kind)) {
                    fresh.add(kind);
                }
            }
        }
        BountyPool pool = BountyPool.of(server, config.speciesFilter);
        Bounty bounty = drawFrom(server, random, config, pool, fresh, taken);
        return bounty != null ? bounty : drawFrom(server, random, config, pool, all, taken);
    }

    /** A mission of one of {@code kinds}, drawn by weight; a kind that fails is dropped for this draw. */
    private static Bounty drawFrom(MinecraftServer server, Random random, SafariConfig.Bounties config,
                                   BountyPool pool, List<BountyKind> kinds, List<Bounty> taken) {
        List<BountyKind> left = new ArrayList<>(kinds);
        while (!left.isEmpty()) {
            BountyKind kind = pickKind(random, config.kinds, left);
            for (int attempt = 0; attempt < DRAW_ATTEMPTS; attempt++) {
                Bounty candidate = make(server, random, config.kinds, pool, kind);
                if (candidate == null) {
                    // Cannot be met this rotation (no specials, empty pool): no use retrying.
                    break;
                }
                if (taken.stream().noneMatch(candidate::sameAs)) {
                    return candidate;
                }
            }
            left.remove(kind);
        }
        return null;
    }

    /** A kind among {@code kinds}, by their configured weights (all positive). */
    private static BountyKind pickKind(Random random, BountyKinds settings, List<BountyKind> kinds) {
        int total = 0;
        for (BountyKind kind : kinds) {
            total += settings.of(kind).weight;
        }
        int roll = random.nextInt(total);
        for (BountyKind kind : kinds) {
            roll -= settings.of(kind).weight;
            if (roll < 0) {
                return kind;
            }
        }
        return kinds.get(kinds.size() - 1);
    }

    /** A mission of a kind, or null when the kind cannot be met this rotation. */
    private static Bounty make(MinecraftServer server, Random random, BountyKinds settings, BountyPool pool,
                               BountyKind kind) {
        BountyKindSettings tuning = settings.of(kind);
        return switch (kind) {
            case TYPE -> {
                String type = weighted(random, pool.typeWeights);
                yield type == null ? null : counted(random, kind, type, tuning);
            }
            case SPECIES -> {
                if (pool.species.isEmpty()) {
                    yield null;
                }
                Species species = pool.species.get(random.nextInt(pool.species.size()));
                BountyKinds.SpeciesBounty speciesTuning = settings.species;
                Rarity rarity = pool.rarity.get(species);
                int count = rarity == Rarity.COMMON ? speciesTuning.commonCount
                        : rarity == Rarity.UNCOMMON ? speciesTuning.uncommonCount : speciesTuning.rareCount;
                yield new Bounty(kind, species.getResourceIdentifier().toString(), count, tuning.rewardFor(count));
            }
            case FEATURED -> {
                List<HabitatEntry> specials = FeaturedRotation.today(server);
                if (specials.isEmpty()) {
                    yield null;
                }
                // Never more than there are specials: each is a different species,
                // and asking three of a single rare one is not the intent.
                Bounty bounty = counted(random, kind, "", tuning);
                int count = Math.min(bounty.count(), specials.size());
                yield new Bounty(kind, "", count, tuning.rewardFor(count));
            }
            case HABITAT -> {
                List<String> habitats = new ArrayList<>();
                pool.habitats.forEach((biome, species) -> {
                    if (species >= MIN_HABITAT_SPECIES) {
                        habitats.add(biome.location().toString());
                    }
                });
                yield habitats.isEmpty() ? null
                        : counted(random, kind, habitats.get(random.nextInt(habitats.size())), tuning);
            }
            case DIFFERENT -> {
                if (pool.species.isEmpty()) {
                    yield null;
                }
                Bounty bounty = counted(random, kind, "", tuning);
                int count = Math.min(bounty.count(), pool.species.size());
                yield new Bounty(kind, "", count, tuning.rewardFor(count));
            }
            case EVOLVED -> pool.evolved.isEmpty() ? null : counted(random, kind, "", tuning);
            case LEVEL -> {
                int highest = 0;
                for (int level : pool.maxLevel.values()) {
                    highest = Math.max(highest, level);
                }
                BountyKinds.LevelBounty levelTuning = settings.level;
                int top = Math.min(levelTuning.maxLevel, highest);
                if (top < 1) {
                    yield null;
                }
                int bottom = Math.min(levelTuning.minLevel, top);
                int level = bottom + random.nextInt(top - bottom + 1);
                // Rounded down to a step, but never below what the config allows.
                int rounded = Math.max(bottom, level / LEVEL_STEP * LEVEL_STEP);
                yield counted(random, kind, Integer.toString(rounded), tuning);
            }
            // Met by any Pokémon of the right moment, way or luck: always possible.
            case ANY, OUT_OF_BATTLE, NIGHT, HIDDEN_ABILITY, SHINY -> pool.species.isEmpty() ? null
                    : counted(random, kind, "", tuning);
        };
    }

    /** A mission asking a drawn count between the kind's bounds, paid as its tuning says. */
    private static Bounty counted(Random random, BountyKind kind, String target, BountyKindSettings tuning) {
        int count = tuning.minCount + random.nextInt(tuning.maxCount - tuning.minCount + 1);
        return new Bounty(kind, target, count, tuning.rewardFor(count));
    }

    /** A key drawn by weight; null when the map is empty. */
    private static String weighted(Random random, Map<String, Integer> weights) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
