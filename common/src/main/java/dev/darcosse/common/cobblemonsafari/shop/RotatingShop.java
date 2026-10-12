package dev.darcosse.common.cobblemonsafari.shop;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariShopConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariShopConfigManager;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.spawn.HabitatEntry;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import dev.darcosse.common.cobblemonsafari.spawn.SpeciesRules;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The shop's random Pokémon: a few species that live in the Safari, drawn
 * anew with each rotation of the specials and sold after the shop's fixed
 * entries.
 *
 * <p>Deterministic like the specials: same world, same rotation, same data,
 * same Pokémon, for every player and across restarts. Cached until the
 * rotation, the specials' data or the shop file changes. Server side.
 */
public final class RotatingShop {

    /** Mixed into the seed so the shop never draws in step with the specials. */
    private static final long SHOP_SEED_MIX = 0xC2B2AE3D27D4EB4FL;

    @Nullable
    private static List<SafariShopConfig.Entry> cached;
    private static long cachedPeriod = Long.MIN_VALUE;
    private static long cachedRevision = Long.MIN_VALUE;
    @Nullable
    private static SafariShopConfig cachedConfig;

    private RotatingShop() {
    }

    /** Index of the rotation the random Pokémon belong to; the shop screen sends it back with a purchase. */
    public static long period() {
        return FeaturedRotation.period();
    }

    /**
     * The random Pokémon of the current rotation, as shop entries; empty when
     * switched off or while Cobblemon's data is not loaded yet.
     */
    public static List<SafariShopConfig.Entry> entries(MinecraftServer server) {
        SafariShopConfig config = SafariShopConfigManager.get();
        if (!config.rotating.enabled || config.rotating.count == 0) {
            return List.of();
        }
        long period = period();
        // today() first: it may start a new draw of the specials, which moves the revision.
        List<HabitatEntry> specials = FeaturedRotation.today(server);
        long revision = FeaturedRotation.revision();
        if (cached != null && period == cachedPeriod && revision == cachedRevision && config == cachedConfig) {
            return cached;
        }
        Map<ResourceLocation, HabitatEntry> index = FeaturedRotation.index(server);
        if (index.isEmpty()) {
            // Not cached: Cobblemon is still loading, the next opening retries.
            return List.of();
        }
        List<SafariShopConfig.Entry> drawn = draw(index, specials, config.rotating, period,
                SafariWorldState.get(server).rotationSalt());
        cached = drawn;
        cachedPeriod = period;
        cachedRevision = revision;
        cachedConfig = config;
        CobblemonSafari.LOGGER.info("Safari shop Pokémon for rotation {}: {}", period,
                drawn.stream().map(entry -> entry.pokemon).toList());
        return drawn;
    }

    /** Picks the species of one rotation and turns them into entries, most common first. */
    private static List<SafariShopConfig.Entry> draw(Map<ResourceLocation, HabitatEntry> index,
                                                     List<HabitatEntry> specials, SafariShopConfig.Rotating config,
                                                     long period, long salt) {
        Set<Species> hunted = new HashSet<>();
        if (config.excludeSpecials) {
            for (HabitatEntry special : specials) {
                hunted.add(special.species());
            }
        }
        // The index is sorted by species id: the pool is built in a stable
        // order before shuffling, which the draw needs to be reproducible.
        List<HabitatEntry> pool = new ArrayList<>();
        for (HabitatEntry entry : index.values()) {
            Species species = entry.species();
            if (!entry.natural()
                    || !config.rarities.contains(entry.rarity().bucket)
                    || config.excludedSpecies.contains(species.getResourceIdentifier().toString())
                    || (!config.allowSpecialSpecies && SpeciesRules.isSpecial(species))
                    || hunted.contains(species)) {
                continue;
            }
            pool.add(entry);
        }
        Collections.shuffle(pool, new Random(salt ^ (period * SHOP_SEED_MIX)));
        List<HabitatEntry> picked = new ArrayList<>(pool.subList(0, Math.min(config.count, pool.size())));
        picked.sort((a, b) -> Integer.compare(a.rarity().ordinal(), b.rarity().ordinal()));

        List<SafariShopConfig.Entry> entries = new ArrayList<>(picked.size());
        for (HabitatEntry entry : picked) {
            entries.add(SafariShopConfig.Entry.rotating(properties(entry, config.level), price(entry.rarity(), config),
                    config.tab));
        }
        return List.copyOf(entries);
    }

    /**
     * Properties of the Pokémon sold: its natural spawn's (so a regional form
     * found in the Safari is the one sold) at the configured level.
     */
    private static String properties(HabitatEntry entry, int level) {
        PokemonProperties template = entry.template();
        if (template == null) {
            return entry.species().getResourceIdentifier() + " level=" + level;
        }
        PokemonProperties copy = template.copy();
        copy.setLevel(level);
        return copy.asString(" ");
    }

    /** Price of a Pokémon of the given rarity. */
    private static int price(Rarity rarity, SafariShopConfig.Rotating config) {
        return switch (rarity) {
            case COMMON -> config.commonPrice;
            case UNCOMMON -> config.uncommonPrice;
            case RARE -> config.rarePrice;
            case ULTRA_RARE -> config.ultraRarePrice;
        };
    }
}
