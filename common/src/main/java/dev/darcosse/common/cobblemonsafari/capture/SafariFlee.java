package dev.darcosse.common.cobblemonsafari.capture;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokeball.PokeBallCaptureCalculatedEvent;
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.spawn.HabitatEntry;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wild Pokémon running away from a failed ball in the Safari, as in the
 * Safari Zone of the games ({@code capture.flee}).
 *
 * <p>When Cobblemon has computed a throw at a wild Pokémon outside of a
 * battle and it fails, a chance is rolled: that of the species' rarity in the
 * Safari (or of its legendary-class category), plus a share for each ball
 * that already failed on that Pokémon, capped. A Pokémon that runs away
 * does so once it is out of the ball and free again (Cobblemon's shaking
 * ends, its busy lock goes), a moment later: it vanishes in a puff and the thrower is told. Which rarities and
 * legendary-class categories may run away, and how likely, is set entry
 * by entry ({@code capture.flee.rarities}); shinies are spared unless the
 * config says otherwise.
 *
 * <p>Server side. Cost: one roll per failed ball; while a flight is
 * pending, one entity lookup per pending Pokémon each tick, for a few
 * seconds.
 */
public final class SafariFlee {

    /**
     * Ticks the Pokémon stays on the spot once free before it vanishes: long
     * enough to see it pop out of the ball, short enough that a second ball
     * is usually too late.
     */
    private static final int FLEE_DELAY_TICKS = 20;

    /**
     * Longest wait for the Pokémon to be free again, in ticks, after which
     * the flight is dropped (the Pokémon was caught by another ball, or its
     * chunk unloaded). Cobblemon's longest shaking takes about 5 s.
     */
    private static final int MAX_WAIT_TICKS = 200;

    /**
     * Pokémon remembered with their count of failed balls. Bounded: beyond
     * this many the oldest are forgotten (a Pokémon nobody threw at for a
     * while starts afresh), so the map never grows with the session.
     */
    private static final int FAILED_MEMORY = 512;

    /** Volume and pitch of the puff heard around the Pokémon. */
    private static final float PUFF_VOLUME = 0.6F;
    private static final float PUFF_PITCH = 1.6F;
    /** Particles of the puff, and their spread relative to the Pokémon's size. */
    private static final int PUFF_PARTICLES = 20;
    private static final double PUFF_SPREAD = 0.5D;
    private static final double PUFF_SPEED = 0.02D;

    /** Failed balls per Pokémon (Pokémon UUID), oldest first. Server thread only. */
    private static final Map<UUID, Integer> FAILED_BALLS = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Integer> eldest) {
            return size() > FAILED_MEMORY;
        }
    };

    /** Flights rolled and waiting for the Pokémon to be free, by entity UUID. Server thread only. */
    private static final Map<UUID, Pending> PENDING = new LinkedHashMap<>();

    private SafariFlee() {
    }

    /**
     * One flight waiting to happen.
     *
     * @param thrower  the player told when it happens
     * @param rolledAt server tick of the failed throw
     * @param freeAt   server tick the Pokémon was first seen free, or -1
     */
    private record Pending(UUID thrower, int rolledAt, int freeAt) {
    }

    /** Subscribes to Cobblemon's capture computation. Called once from common init. */
    public static void register() {
        // LOWEST: the result other addons may have changed is the one that counts.
        CobblemonEvents.POKE_BALL_CAPTURE_CALCULATED.subscribe(Priority.LOWEST, SafariFlee::onCaptureCalculated);
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    public static void reset() {
        FAILED_BALLS.clear();
        PENDING.clear();
    }

    /** Rolls a flight for a failed throw at a wild Pokémon outside a battle, in the Safari. */
    private static void onCaptureCalculated(PokeBallCaptureCalculatedEvent event) {
        if (!(event.getThrower() instanceof ServerPlayer player) || event.getCaptureResult().isSuccessfulCapture()) {
            return;
        }
        PokemonEntity entity = event.getPokemonEntity();
        SafariConfig.Flee config = SafariConfigManager.get().capture.flee;
        MinecraftServer server = player.getServer();
        if (!config.enabled || server == null || entity.getBattleId() != null
                || !SafariDimensions.isSafari(entity.level()) || !entity.getPokemon().isWild()) {
            return;
        }
        Pokemon pokemon = entity.getPokemon();
        Integer before = FAILED_BALLS.get(pokemon.getUuid());
        int failedBefore = before == null ? 0 : before;
        FAILED_BALLS.put(pokemon.getUuid(), failedBefore + 1);
        Double base = baseChance(pokemon.getSpecies(), safariRarity(server, pokemon), config.rarities);
        if (base == null || (!config.shinies && pokemon.getShiny())) {
            return;
        }
        double chance = Math.min(config.maxChance, base + config.perFailedBall * failedBefore);
        if (entity.level().getRandom().nextDouble() < chance) {
            PENDING.put(entity.getUUID(), new Pending(player.getUUID(), server.getTickCount(), -1));
        }
    }

    /** The Pokémon's rarity in the Safari; common when the habitat index does not know it. */
    private static Rarity safariRarity(MinecraftServer server, Pokemon pokemon) {
        HabitatEntry entry = FeaturedRotation.index(server).get(pokemon.getSpecies().getResourceIdentifier());
        return entry == null ? Rarity.COMMON : entry.rarity();
    }

    /**
     * The chance of {@code capture.flee.rarities} that applies to this
     * Pokémon, or null when it may not run away at all.
     *
     * <p>A legendary-class species answers to its categories only. With two
     * of them (a species may carry two labels) it is spared as soon as one
     * is off, the stricter switch winning, and otherwise takes the higher
     * chance. Any other species answers to its rarity in the Safari.
     */
    private static Double baseChance(Species species, Rarity rarity, SafariConfig.FleeRarities rarities) {
        Double best = null;
        for (String label : species.getLabels()) {
            SafariConfig.FleeRarity entry = switch (label) {
                case CobblemonPokemonLabels.LEGENDARY -> rarities.legendary;
                case CobblemonPokemonLabels.MYTHICAL -> rarities.mythical;
                case CobblemonPokemonLabels.ULTRA_BEAST -> rarities.ultraBeast;
                case CobblemonPokemonLabels.PARADOX -> rarities.paradox;
                default -> null;
            };
            if (entry == null) {
                continue;
            }
            if (!entry.enabled) {
                return null;
            }
            best = best == null ? entry.chance : Math.max(best, entry.chance);
        }
        if (best != null) {
            return best;
        }
        SafariConfig.FleeRarity entry = switch (rarity) {
            case COMMON -> rarities.common;
            case UNCOMMON -> rarities.uncommon;
            case RARE -> rarities.rare;
            case ULTRA_RARE -> rarities.ultraRare;
        };
        return entry.enabled ? entry.chance : null;
    }

    /**
     * Carries out the flights whose Pokémon is free again. Called each
     * server tick; returns at once when none is pending.
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        ServerLevel safari = SafariDimensions.level(server);
        int now = server.getTickCount();
        Iterator<Map.Entry<UUID, Pending>> pending = PENDING.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<UUID, Pending> flight = pending.next();
            Pending state = flight.getValue();
            Entity found = safari == null ? null : safari.getEntity(flight.getKey());
            if (!(found instanceof PokemonEntity entity) || !entity.isAlive() || !entity.getPokemon().isWild()
                    || now - state.rolledAt() > MAX_WAIT_TICKS) {
                // Caught, gone or stuck: nothing to run away from any more.
                pending.remove();
                continue;
            }
            // Busy while a ball holds it, in battle if one started meanwhile: wait, or drop.
            if (entity.getBattleId() != null) {
                pending.remove();
                continue;
            }
            if (entity.isBusy()) {
                continue;
            }
            if (state.freeAt() < 0) {
                flight.setValue(new Pending(state.thrower(), state.rolledAt(), now));
                continue;
            }
            if (now - state.freeAt() >= FLEE_DELAY_TICKS) {
                pending.remove();
                flee(server, safari, entity, state.thrower());
            }
        }
    }

    /** Makes the Pokémon vanish in a puff and tells the thrower. */
    private static void flee(MinecraftServer server, ServerLevel level, PokemonEntity entity, UUID throwerId) {
        double height = entity.getBbHeight();
        double width = entity.getBbWidth();
        level.sendParticles(ParticleTypes.POOF, entity.getX(), entity.getY() + height / 2.0D, entity.getZ(),
                PUFF_PARTICLES, width * PUFF_SPREAD, height * PUFF_SPREAD, width * PUFF_SPREAD, PUFF_SPEED);
        level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.FIRE_EXTINGUISH,
                SoundSource.NEUTRAL, PUFF_VOLUME, PUFF_PITCH);
        FAILED_BALLS.remove(entity.getPokemon().getUuid());
        Component name = entity.getPokemon().getSpecies().getTranslatedName();
        entity.discard();
        ServerPlayer thrower = server.getPlayerList().getPlayer(throwerId);
        if (thrower != null) {
            thrower.sendSystemMessage(Component.translatable(CobblemonSafari.MOD_ID + ".capture.fled", name)
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
