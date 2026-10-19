package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;

/**
 * Text of the specials list shown by {@code /safari today}. Everything is a translatable component resolved on each
 * client, so species and habitat names appear in the reader's language.
 */
public final class FeaturedBoard {

    private static final long MILLIS_PER_SECOND = 1000L;

    private FeaturedBoard() {
    }

    /** Full board: header, one line per special, time until the next rotation. */
    public static List<Component> lines(MinecraftServer server) {
        List<HabitatEntry> specials = FeaturedRotation.today(server);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(key("board.header")).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        if (specials.isEmpty()) {
            lines.add(Component.translatable(key("board.empty")).withStyle(ChatFormatting.GRAY));
            return lines;
        }
        for (HabitatEntry entry : specials) {
            lines.add(line(entry));
        }
        lines.add(Component.translatable(key("board.next_rotation"), untilNextRotation())
                .withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    /** One board line: species, rarity and main habitat. */
    private static Component line(HabitatEntry entry) {
        MutableComponent species = Component.translatable(entry.species().getTranslationKey())
                .withStyle(ChatFormatting.WHITE);
        // A star for legendary-class specials, so they stand out at a glance.
        if (SpeciesRules.isSpecial(entry.species())) {
            species = Component.literal("★ ").withStyle(ChatFormatting.GOLD).append(species);
        }
        ResourceKey<Biome> habitat = entry.mainHabitat();
        Component habitatName = habitat != null
                ? Component.translatable("biome." + habitat.location().getNamespace() + "." + habitat.location().getPath())
                : Component.translatable(key("board.habitat_unknown"));
        return Component.translatable(key("board.line"), species, entry.rarity().displayName(),
                habitatName.copy().withStyle(ChatFormatting.GRAY));
    }

    /** Time left in the rotation, formatted like the Safari timer. */
    private static String untilNextRotation() {
        long seconds = FeaturedRotation.millisUntilNextRotation() / MILLIS_PER_SECOND;
        return Ticks.format(Ticks.fromSeconds(seconds));
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
