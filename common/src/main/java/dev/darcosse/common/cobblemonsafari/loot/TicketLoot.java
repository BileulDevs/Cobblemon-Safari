package dev.darcosse.common.cobblemonsafari.loot;

import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.item.SafariItems;
import net.minecraft.advancements.critereon.LocationPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LocationCheck;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

import java.util.Optional;

/**
 * Safari Tickets in overworld chests: the extra loot pool each loader adds to
 * the tables listed in {@code ticketLoot} of the server config.
 *
 * <p>Built while loot tables load (server start and /reload), never during
 * play: the cost is a scan of the configured sources per table loaded.
 */
public final class TicketLoot {

    /** One ticket per successful roll; the chance decides whether there is one at all. */
    private static final float TICKETS_PER_POOL = 1.0F;

    private TicketLoot() {
    }

    /**
     * The pool to add to a loot table, or empty when the table is not listed
     * (or the feature is off). The pool rolls once: a ticket with the
     * table's chance, never inside the Safari dimension.
     */
    public static Optional<LootPool.Builder> poolFor(ResourceLocation table) {
        SafariConfig.TicketLoot config = SafariConfigManager.get().ticketLoot;
        if (!config.enabled) {
            return Optional.empty();
        }
        double chance = chanceFor(config, table.toString());
        if (chance <= 0.0D) {
            return Optional.empty();
        }
        return Optional.of(LootPool.lootPool()
                .setRolls(ConstantValue.exactly(TICKETS_PER_POOL))
                .when(LootItemRandomChanceCondition.randomChance((float) chance))
                // Not in the Safari: its map is rebuilt every day with fresh
                // ruin chests, which would make tickets endless.
                .when(InvertedLootItemCondition.invert(
                        LocationCheck.checkLocation(LocationPredicate.Builder.inDimension(SafariDimensions.key()))))
                .add(LootItem.lootTableItem(SafariItems.safariTicket())));
    }

    /**
     * The chance configured for a table: an exact entry, else the longest
     * folder entry it falls under, so a specific table can override its
     * folder. 0 when nothing matches.
     */
    private static double chanceFor(SafariConfig.TicketLoot config, String table) {
        double chance = 0.0D;
        int matched = -1;
        for (SafariConfig.TicketLoot.Source source : config.sources) {
            if (source.table.equals(table)) {
                return source.chance;
            }
            if (source.table.endsWith("/") && table.startsWith(source.table) && source.table.length() > matched) {
                chance = source.chance;
                matched = source.table.length();
            }
        }
        return chance;
    }
}
