package dev.darcosse.common.cobblemonsafari.bounty;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * One mission of the day.
 *
 * @param kind   what has to be caught
 * @param target type name ({@code "grass"}) for {@link Kind#TYPE}, species id for {@link Kind#SPECIES},
 *               empty for {@link Kind#FEATURED}
 * @param count  how many catches complete it
 * @param reward Safari points paid once on completion
 */
public record Bounty(Kind kind, String target, int count, int reward) {

    /**
     * What the mission asks, e.g. "Catch 3 Grass-type Pokémon". Works on both
     * sides: types and species are known to the client too. An unknown
     * target (datapack missing on one side) is shown by its id.
     */
    public Component describe() {
        String prefix = CobblemonSafari.MOD_ID + ".bounty.";
        return switch (kind) {
            case TYPE -> {
                ElementalType type = ElementalTypes.get(target);
                Component typeName = type != null ? type.getDisplayName() : Component.literal(target);
                yield Component.translatable(prefix + "type", count, typeName);
            }
            case SPECIES -> {
                ResourceLocation id = ResourceLocation.tryParse(target);
                Species species = id == null ? null : PokemonSpecies.getByIdentifier(id);
                Component name = species != null ? species.getTranslatedName() : Component.literal(target);
                yield Component.translatable(prefix + "species", count, name);
            }
            case FEATURED -> Component.translatable(prefix + "featured");
        };
    }

    /** Mission kinds. Their ordinals are the wire format: append only, never reorder. */
    public enum Kind {
        /** Catch Pokémon of an elemental type. */
        TYPE,
        /** Catch a given species. */
        SPECIES,
        /** Catch any of today's specials. */
        FEATURED
    }
}
