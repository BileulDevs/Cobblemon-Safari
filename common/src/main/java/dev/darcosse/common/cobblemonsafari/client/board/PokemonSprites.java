package dev.darcosse.common.cobblemonsafari.client.board;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the flat sprite of a Pokémon, for the wall screens' sprite mode.
 * The mod ships only the base sprite of each species, at
 * {@code assets/cobblemon_safari/textures/pokemon_sprites/<species>.png}; a
 * resource pack can override them or add forms and shiny sprites as
 * {@code <species>[_<form>][_shiny].png}, still looked up first,
 * {@code <species>} being the path of the Cobblemon species id
 * ({@code bulbasaur}, {@code mrmime}) and {@code <form>} an aspect with
 * dashes turned into underscores ({@code alolan}, {@code female}).
 * tools/pokemon_sprites_pack.py installs them from a folder of sprites.
 *
 * <p>CLIENT ONLY. Lookups hit the resource manager, so results are cached;
 * entries expire after {@link #CACHE_TTL_MS} so that a resource pack turned
 * on or off is picked up without a restart.
 */
public final class PokemonSprites {

    /** Folder of the sprites inside the mod's namespace. */
    private static final String FOLDER = "textures/pokemon_sprites/";
    private static final String SHINY = "shiny";

    /**
     * How long a lookup is trusted, in ms. Shorter notices a resource pack
     * change sooner, at one resource-manager query per Pokémon on screen and
     * per expiry; longer keeps a stale answer for that long after F3+T.
     */
    private static final long CACHE_TTL_MS = 10_000L;

    /** Lookups by species and aspects. Render thread only. */
    private static final Map<String, Cached> CACHE = new HashMap<>();

    private PokemonSprites() {
    }

    /** One cached answer: the texture, or null when neither the mod nor a pack has one. */
    private record Cached(@Nullable ResourceLocation texture, long expiresAt) {
    }

    /**
     * The sprite for a species with its aspects, or null when neither the
     * mod nor a loaded pack has one. Tries the most specific name first: each form aspect, shiny
     * then plain, then the bare species.
     */
    @Nullable
    public static ResourceLocation find(ResourceLocation species, Set<String> aspects) {
        // Sorted copy: the same Pokémon always gives the same key and the same first form tried.
        Set<String> sorted = new TreeSet<>(aspects);
        String key = species + "|" + sorted;
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.texture();
        }
        ResourceLocation texture = lookup(species.getPath(), sorted);
        CACHE.put(key, new Cached(texture, now + CACHE_TTL_MS));
        return texture;
    }

    /** Drops every cached answer, e.g. when the board leaves sprite mode. */
    public static void clear() {
        CACHE.clear();
    }

    /** Walks the candidate names in order and returns the first that exists. */
    @Nullable
    private static ResourceLocation lookup(String species, Set<String> aspects) {
        boolean shiny = aspects.contains(SHINY);
        List<String> names = new ArrayList<>();
        for (String aspect : aspects) {
            if (aspect.equals(SHINY)) {
                continue;
            }
            String form = species + "_" + clean(aspect);
            if (shiny) {
                names.add(form + "_" + SHINY);
            }
            names.add(form);
        }
        if (shiny) {
            names.add(species + "_" + SHINY);
        }
        names.add(species);
        var resources = Minecraft.getInstance().getResourceManager();
        for (String name : names) {
            ResourceLocation candidate = ResourceLocation.tryBuild(CobblemonSafari.MOD_ID, FOLDER + name + ".png");
            if (candidate != null && resources.getResource(candidate).isPresent()) {
                return candidate;
            }
        }
        return null;
    }

    /** An aspect as a file-name part: lower case, dashes and spaces to underscores. */
    private static String clean(String aspect) {
        return aspect.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
