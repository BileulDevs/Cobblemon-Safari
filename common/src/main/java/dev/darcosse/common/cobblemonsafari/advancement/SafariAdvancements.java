package dev.darcosse.common.cobblemonsafari.advancement;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.fishing.BobberSpawnPokemonEvent;
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels;
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.capture.FishedTracker;
import dev.darcosse.common.cobblemonsafari.capture.ThrowTracker;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.reset.SafariMapReset;
import dev.darcosse.common.cobblemonsafari.session.SafariSession;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionListener;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.session.SessionEndReason;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.spawn.HabitatEntry;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import dev.darcosse.common.cobblemonsafari.stats.PlayerSafariStats;
import dev.darcosse.common.cobblemonsafari.stats.SafariStatistics;
import dev.darcosse.common.cobblemonsafari.stats.SafariStatsStore;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Feeds the Safari advancements ({@code data/cobblemon_safari/advancement/safari/})
 * through {@link SafariTrigger}: listens to sessions, captures, fishing and
 * the Safari statistics, and names each moment with an event id.
 *
 * <p>Event ids are the advancements' JSON contract: renaming one silently
 * breaks every advancement listening to it. Counting events carry the
 * player's running total as value ({@code "value": {"min": 10}} in the
 * JSON); every other event carries 1. Statistics fire under their own
 * name (the path of the stat id, e.g. {@code hunts}), so any Safari
 * statistic can drive an advancement without code.
 *
 * <p>Server side only. Every hook is event-driven: nothing here runs each tick.
 */
public final class SafariAdvancements {

    /** Id of the trigger in the trigger registry, as written in the advancement JSON. */
    public static final ResourceLocation TRIGGER_ID = CobblemonSafari.id("safari_event");
    /** The trigger itself; registered by each loader through {@link #registerTrigger}. */
    public static final SafariTrigger TRIGGER = new SafariTrigger();

    /** A Pokémon was caught in the Safari. */
    public static final String CATCH = "catch";
    /** A shiny Pokémon was caught in the Safari. */
    public static final String CATCH_SHINY = "catch_shiny";
    /** One of the rotation's specials was caught. */
    public static final String CATCH_SPECIAL = "catch_special";
    /** An ultra-rare special was caught. */
    public static final String CATCH_ULTRA_RARE_SPECIAL = "catch_ultra_rare_special";
    /** A Pokémon was caught at night (Cobblemon's spawner's night). */
    public static final String CATCH_AT_NIGHT = "catch_at_night";
    /** A Pokémon outside any battle was caught with the park's Safari Ball. */
    public static final String CATCH_SAFARI_BALL_OUT_OF_BATTLE = "catch_safari_ball_out_of_battle";
    /** A Pokémon was caught with a Master Ball in the Safari. */
    public static final String CATCH_MASTER_BALL = "catch_master_ball";
    /** A legendary or mythical Pokémon was caught in the Safari. */
    public static final String CATCH_LEGENDARY = "catch_legendary";
    /** A Pokémon was caught in the last seconds of a hunt ({@link #LAST_SECONDS_TICKS}). */
    public static final String CATCH_LAST_SECONDS = "catch_last_seconds";
    /** A Pokémon that came to eat a Poké Snack was caught. */
    public static final String CATCH_SNACK_VISITOR = "catch_snack_visitor";
    /** A Pokémon fished in the Safari was caught. */
    public static final String CATCH_FISHED = "catch_fished";
    /** Counting: Pokémon caught in the current hunt. */
    public static final String HUNT_CATCHES = "hunt_catches";
    /** Counting: distinct species ever caught in the Safari. */
    public static final String SPECIES_CAUGHT = "species_caught";
    /** Counting: distinct maps the player has hunted on. */
    public static final String MAPS_HUNTED = "maps_hunted";
    /** A hunt of the longest allowed length ran out without the player leaving. */
    public static final String FULL_HUNT = "full_hunt";
    /** The time bank reached its configured cap. */
    public static final String BANK_FULL = "bank_full";
    /** The player left through a clerk and got unused time back. */
    public static final String REFUND = "refund";
    /** An out-of-battle mission was completed. */
    public static final String MISSION_OUT_OF_BATTLE = "mission_out_of_battle";
    /** Every mission of the rotation was completed. */
    public static final String ALL_MISSIONS = "all_missions";
    /** A clerk swapped a mission. */
    public static final String MISSION_SWAP = "mission_swap";
    /** Something was bought with Safari points. */
    public static final String SHOP_PURCHASE = "shop_purchase";
    /** Overworld goods were traded at the shop. */
    public static final String SHOP_TRADE = "shop_trade";
    /** The player tried to hurt the Safari Warden or one of his Pokémon. */
    public static final String GATE_RESIDENT_HIT = "gate_resident_hit";
    /** A Pokémon was fished in the Safari; the player predicate sees where the angler stands. */
    public static final String FISHED = "fished";
    /** A Pokémon was fished with bait on the rod. */
    public static final String FISHED_WITH_BAIT = "fished_with_bait";
    /** A shiny Pokémon was fished. */
    public static final String FISHED_SHINY = "fished_shiny";
    /** One of the rotation's specials was fished. */
    public static final String FISHED_SPECIAL = "fished_special";

    /**
     * Time left, in ticks, under which a capture counts as "in the last
     * seconds" (10 s). Raising it makes the hidden advancement easier.
     */
    private static final int LAST_SECONDS_TICKS = 200;

    /** Cobblemon's Master Ball, compared with the ball of a capture. */
    private static final ResourceLocation MASTER_BALL = ResourceLocation.fromNamespaceAndPath("cobblemon", "master_ball");

    private SafariAdvancements() {
    }

    /**
     * Registers the trigger into the trigger registry. Called once per
     * loader while that registry is open (Fabric: mod init; NeoForge: its
     * RegisterEvent), before any advancement is loaded.
     */
    public static void registerTrigger(BiConsumer<ResourceLocation, CriterionTrigger<?>> registrar) {
        registrar.accept(TRIGGER_ID, TRIGGER);
    }

    /** Subscribes to sessions and fishing. Called once from common init. */
    public static void register() {
        SafariSessionManager.addListener(new SafariSessionListener() {
            /** Counts the map the hunt starts on. */
            @Override
            public void onStart(ServerPlayer player, SafariSession session) {
                onHuntStarted(player);
            }

            /** A hunt that ran its full allowed length to the end. */
            @Override
            public void onEnd(ServerPlayer player, SafariSession session, SessionEndReason reason) {
                if (reason == SessionEndReason.EXPIRED
                        && session.grantedTicks() >= SafariConfigManager.maxDurationTicks()) {
                    fire(player, FULL_HUNT);
                }
            }
        });
        // LOWEST: only reads the spawn, after any addon that might change the Pokémon.
        CobblemonEvents.BOBBER_SPAWN_POKEMON_POST.subscribe(Priority.LOWEST, SafariAdvancements::onFished);
    }

    /** Fires an event worth 1. */
    public static void fire(ServerPlayer player, String event) {
        TRIGGER.trigger(player, event, 1);
    }

    /** Fires a counting event with the player's running total. */
    public static void fire(ServerPlayer player, String event, int value) {
        TRIGGER.trigger(player, event, value);
    }

    /**
     * A Safari statistic changed: fires it under its own name with the
     * player's new total. Called by {@link SafariStatistics} on every change.
     */
    public static void onStatChanged(ServerPlayer player, ResourceLocation stat) {
        fire(player, stat.getPath(), player.getStats().getValue(Stats.CUSTOM.get(stat)));
    }

    /**
     * A player joined: replays every counting event with the current
     * totals, so a player who passed a threshold before these advancements
     * existed earns them now, and a datapack that lowers a threshold takes
     * effect at the next login. A dozen trigger calls per login.
     */
    public static void onJoin(ServerPlayer player) {
        for (ResourceLocation stat : SafariStatistics.all()) {
            onStatChanged(player, stat);
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PlayerSafariStats record = SafariStatsStore.get(server).find(player.getUUID());
        if (record != null) {
            fire(player, SPECIES_CAUGHT, record.speciesCaught().size());
            fire(player, MAPS_HUNTED, record.mapsHunted());
        }
    }

    /** Counts the current map in the player's record when a hunt starts on a new one. */
    private static void onHuntStarted(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        fire(player, MAPS_HUNTED, SafariStatsStore.get(server).markMapHunted(player.getUUID(), SafariMapReset.salt()));
    }

    /**
     * A capture made in the Safari, already recorded on the session: fires
     * one event per remarkable trait of it. Called by the capture tracker.
     */
    public static void onCatch(ServerPlayer player, Pokemon pokemon, EmptyPokeBallEntity ball) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Species species = pokemon.getSpecies();
        fire(player, CATCH);
        if (pokemon.getShiny()) {
            fire(player, CATCH_SHINY);
        }
        HabitatEntry special = specialOf(server, species);
        if (special != null) {
            fire(player, CATCH_SPECIAL);
            if (special.rarity() == Rarity.ULTRA_RARE) {
                fire(player, CATCH_ULTRA_RARE_SPECIAL);
            }
        }
        // Where the Pokémon was: the ball lies on it. The player when there is no ball.
        Entity where = ball != null ? ball : player;
        if (ThrowTracker.isNight(where)) {
            fire(player, CATCH_AT_NIGHT);
        }
        if (ball != null) {
            ResourceLocation ballId = ball.getPokeBall().getName();
            // A ball the catch rate never saw (another mod's capture) is not counted as out of battle.
            if (Boolean.FALSE.equals(ThrowTracker.wasInBattle(ball))
                    && ballId.toString().equals(SafariConfigManager.get().balls.loan.ballItem)) {
                fire(player, CATCH_SAFARI_BALL_OUT_OF_BATTLE);
            }
            if (ballId.equals(MASTER_BALL)) {
                fire(player, CATCH_MASTER_BALL);
            }
        }
        Set<String> labels = species.getLabels();
        if (labels.contains(CobblemonPokemonLabels.LEGENDARY) || labels.contains(CobblemonPokemonLabels.MYTHICAL)) {
            fire(player, CATCH_LEGENDARY);
        }
        // Forced aspects: where Cobblemon puts the snack's mark on the Pokémon that ate it, and nothing removes it.
        if (pokemon.getForcedAspects().contains(PokeSnackBlockEntity.POKE_SNACK_CRUMBED_ASPECT)) {
            fire(player, CATCH_SNACK_VISITOR);
        }
        if (FishedTracker.wasFished(pokemon.getUuid())) {
            fire(player, CATCH_FISHED);
        }
        SafariSession session = SafariSessionManager.getSession(player);
        if (session != null) {
            fire(player, HUNT_CATCHES, session.catches().size());
            if (session.remainingTicks() > 0 && session.remainingTicks() <= LAST_SECONDS_TICKS) {
                fire(player, CATCH_LAST_SECONDS);
            }
        }
        PlayerSafariStats record = SafariStatsStore.get(server).find(player.getUUID());
        if (record != null) {
            fire(player, SPECIES_CAUGHT, record.speciesCaught().size());
        }
    }

    /** A bobber pulled a Pokémon out of the water: counts it if it happened in the Safari. */
    private static void onFished(BobberSpawnPokemonEvent.Post event) {
        Player owner = event.getBobber().getPlayerOwner();
        if (!(owner instanceof ServerPlayer player) || !SafariDimensions.isSafari(player.level())) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Pokemon pokemon = event.getPokemon().getPokemon();
        FishedTracker.remember(pokemon.getUuid());
        SafariStatistics.increment(player, SafariStatistics.POKEMON_FISHED);
        fire(player, FISHED);
        if (!event.getBait().isEmpty()) {
            fire(player, FISHED_WITH_BAIT);
        }
        if (pokemon.getShiny()) {
            fire(player, FISHED_SHINY);
        }
        if (specialOf(server, pokemon.getSpecies()) != null) {
            fire(player, FISHED_SPECIAL);
        }
    }

    /**
     * Damage to a gate resident was cancelled: if a player dealt it, that
     * player tried to hurt the warden or his Pokémon.
     */
    public static void onGateResidentHit(DamageSource source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            fire(player, GATE_RESIDENT_HIT);
        }
    }

    /** Today's special entry of a species, or null when it is not one of them. */
    private static HabitatEntry specialOf(MinecraftServer server, Species species) {
        for (HabitatEntry entry : FeaturedRotation.today(server)) {
            if (entry.species() == species) {
                return entry;
            }
        }
        return null;
    }
}
