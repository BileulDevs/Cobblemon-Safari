package dev.darcosse.common.cobblemonsafari.bounty;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * One mission of the rotation.
 *
 * @param kind   what has to be caught
 * @param target what the kind is about: the type name ({@code "grass"}) for {@link BountyKind#TYPE}, the
 *               species id for {@link BountyKind#SPECIES}, the biome id for {@link BountyKind#HABITAT},
 *               the level for {@link BountyKind#LEVEL}; empty for the other kinds
 * @param count  how many catches complete it
 * @param reward Safari points paid once on completion
 */
public record Bounty(BountyKind kind, String target, int count, int reward) {

    /**
     * What the mission asks, e.g. "Catch 3 Grass-type Pokémon". Works on both
     * sides: types, species and biome names are known to the client too. An
     * unknown target (datapack missing on one side) is shown by its id.
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
            // "one of" reads better than "1 of": the default asks a single special.
            case FEATURED -> count == 1 ? Component.translatable(prefix + "featured")
                    : Component.translatable(prefix + "featured_many", count);
            case HABITAT -> {
                ResourceLocation id = ResourceLocation.tryParse(target);
                Component biome = id == null ? Component.literal(target)
                        : Component.translatable(Util.makeDescriptionId("biome", id));
                yield Component.translatable(prefix + "habitat", count, biome);
            }
            case ANY -> Component.translatable(prefix + "any", count);
            case DIFFERENT -> Component.translatable(prefix + "different", count);
            case OUT_OF_BATTLE -> Component.translatable(prefix + "out_of_battle", count);
            case NIGHT -> Component.translatable(prefix + "night", count);
            case EVOLVED -> Component.translatable(prefix + "evolved", count);
            case LEVEL -> Component.translatable(prefix + "level", count, target);
            case HIDDEN_ABILITY -> Component.translatable(prefix + "hidden_ability", count);
            case SHINY -> Component.translatable(prefix + "shiny", count);
        };
    }

    /** Whether two missions ask the same thing, whatever their count and reward: a rotation never has both. */
    public boolean sameAs(Bounty other) {
        return kind == other.kind && target.equals(other.target);
    }

    /** The level a {@link BountyKind#LEVEL} mission asks; 0 for a malformed target. */
    public int level() {
        try {
            return Integer.parseInt(target);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
