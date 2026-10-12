package dev.darcosse.common.cobblemonsafari.points;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.spawn.HabitatEntry;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Safari points: earned by catching Pokémon inside the Safari, spent at the
 * warden's shop. A currency of its own, separate from banked Safari time.
 *
 * <p>The one place that reads and writes {@link SafariPointsStore}, so the
 * cap cannot be bypassed. Server side only.
 */
public final class SafariPoints {

    /**
     * Most points one player can hold. Only a safety stop against an int
     * overflow from a runaway reward; no real balance comes near it.
     */
    private static final int MAX_BALANCE = 1_000_000;

    private SafariPoints() {
    }

    /** Subscribes to Cobblemon's capture event. Called once from common init. */
    public static void register() {
        // LOWEST: the capture is final when this fires, like CatchTracker.
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.LOWEST, SafariPoints::onCaptured);
    }

    /** A player's points. */
    public static int balance(ServerPlayer player) {
        return SafariPointsStore.get(player.getServer()).get(player.getUUID());
    }

    /**
     * Adds points, up to the configured cap.
     *
     * @return the points actually added; 0 when the balance is already full
     */
    public static int add(ServerPlayer player, int amount) {
        SafariPointsStore store = SafariPointsStore.get(player.getServer());
        int before = store.get(player.getUUID());
        // long: a huge reward on a huge balance must not wrap around.
        int after = (int) Math.min(MAX_BALANCE, (long) before + Math.max(0, amount));
        if (after > before) {
            store.set(player.getUUID(), after);
        }
        return Math.max(0, after - before);
    }

    /**
     * Takes points if the player has enough.
     *
     * @return whether the points were taken
     */
    public static boolean spend(ServerPlayer player, int amount) {
        SafariPointsStore store = SafariPointsStore.get(player.getServer());
        int before = store.get(player.getUUID());
        if (amount < 0 || before < amount) {
            return false;
        }
        store.set(player.getUUID(), before - amount);
        return true;
    }

    /** Rewards a capture made inside the Safari and tells the player. */
    private static void onCaptured(PokemonCapturedEvent event) {
        ServerPlayer player = event.getPlayer();
        SafariConfig.Points config = SafariConfigManager.get().points;
        if (!config.enabled || !SafariDimensions.isSafari(player.level())) {
            return;
        }
        int reward = reward(player, event.getPokemon(), config);
        int added = add(player, reward);
        if (added > 0) {
            player.displayClientMessage(Component.translatable(CobblemonSafari.MOD_ID + ".message.points_earned",
                    SafariPointsIcon.amount(added), SafariPointsIcon.amount(balance(player))), true);
        }
    }

    /**
     * Points for one capture: by the species' rarity in the Safari, plus the
     * bonuses. A species the Safari index does not know (it never spawns
     * there naturally, e.g. brought by another mod) counts as common.
     */
    private static int reward(ServerPlayer player, Pokemon pokemon, SafariConfig.Points config) {
        ResourceLocation speciesId = pokemon.getSpecies().getResourceIdentifier();
        HabitatEntry entry = FeaturedRotation.index(player.getServer()).get(speciesId);
        Rarity rarity = entry != null ? entry.rarity() : Rarity.COMMON;
        int points = switch (rarity) {
            case COMMON -> config.common;
            case UNCOMMON -> config.uncommon;
            case RARE -> config.rare;
            case ULTRA_RARE -> config.ultraRare;
        };
        boolean featured = FeaturedRotation.today(player.getServer()).stream()
                .anyMatch(special -> special.species() == pokemon.getSpecies());
        if (featured) {
            points += config.featuredBonus;
        }
        if (pokemon.getShiny()) {
            points += config.shinyBonus;
        }
        return points;
    }
}
