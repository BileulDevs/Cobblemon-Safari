package dev.darcosse.common.cobblemonsafari.session;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * One Pokémon caught during a Safari session.
 *
 * <p>Stores the species translation key rather than a resolved name: the
 * recap is rendered on each player's client in their own language, and the
 * record survives restarts without depending on Cobblemon's species registry
 * being loaded when the session store is read.
 *
 * @param speciesId      Cobblemon species id, e.g. {@code cobblemon:tauros}; used by statistics
 * @param translationKey Cobblemon species name key, e.g. {@code cobblemon.species.tauros.name}
 * @param shiny          whether the caught Pokémon was shiny
 */
public record CatchRecord(String speciesId, String translationKey, boolean shiny) {

    /** Display name in the reader's language, with a star for shinies. */
    public Component displayName() {
        Component name = Component.translatable(translationKey);
        return shiny ? Component.empty().append(name).append("★") : name;
    }

    /** Serialises into a compound. Keys are the save format. */
    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("species", speciesId);
        tag.putString("name", translationKey);
        tag.putBoolean("shiny", shiny);
        return tag;
    }

    /** Reads a compound written by {@link #save()}, or null when it carries no species. */
    @Nullable
    static CatchRecord load(CompoundTag tag) {
        String species = tag.getString("species");
        if (species.isEmpty()) {
            return null;
        }
        return new CatchRecord(species, tag.getString("name"), tag.getBoolean("shiny"));
    }
}
