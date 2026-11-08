package dev.darcosse.common.cobblemonsafari.egg;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.capture.SafariMark;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.item.SafariItems;
import dev.darcosse.common.cobblemonsafari.spawn.EggSpecies;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The Safari Egg, found in a straw nest. It hatches by itself after a while
 * in the inventory of a connected player, anywhere, into the first stage of
 * its line at level 1, with the Nest Mark; a party with one of the warming
 * abilities (Flame Body by default) hatches it faster. The Pokémon goes to
 * the party, or the PC when the party is full.
 *
 * <p>Its custom data holds the species, the time it takes and the time
 * already spent, in seconds. Only the species is decided when the egg is
 * found; the Pokémon itself (nature, IVs, shiny) is rolled at the hatch, so
 * the client cannot read it beforehand, and the shiny boosts of the moment
 * (an aurora, a Shiny Charm) apply.
 */
public class SafariEggItem extends Item {

    private static final String SPECIES_FIELD = "species";
    private static final String PROGRESS_FIELD = "progress";
    private static final String HATCH_FIELD = "hatch";

    /**
     * Ticks between two steps of the hatch, each adding its seconds at once.
     * Each step rewrites the stack, which the game sends to the client: every
     * second would send a packet per egg per second for nothing visible.
     * Shorter makes the tooltip tick more finely, for more packets.
     */
    private static final int STEP_TICKS = 100;
    /** Seconds in a step, from {@link #STEP_TICKS}. */
    private static final int STEP_SECONDS = STEP_TICKS / 20;

    /**
     * When the hatch finds no room in the party nor the PC, the egg waits
     * this many seconds before trying again, so Cobblemon's "no room" message
     * comes once a minute rather than at every step.
     */
    private static final int NO_ROOM_RETRY_SECONDS = 60;

    /** Level of a hatched Pokémon, as in the games. */
    private static final int HATCH_LEVEL = 1;

    /** Seconds in a minute, for the config and the tooltip. */
    private static final int SECONDS_PER_MINUTE = 60;

    /** Item constructor; the stack size is set by the registration (one egg per stack). */
    public SafariEggItem(Properties properties) {
        super(properties);
    }

    /**
     * A new egg of the species, hatching after {@code hatchMinutes} minutes.
     * The hatch time is written on the egg, so the tooltip can show it and a
     * config change only affects the eggs found afterwards.
     */
    public static ItemStack create(Species species, int hatchMinutes) {
        ItemStack stack = new ItemStack(SafariItems.safariEgg());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(SPECIES_FIELD, species.getResourceIdentifier().toString());
            tag.putInt(HATCH_FIELD, hatchMinutes * SECONDS_PER_MINUTE);
            tag.putInt(PROGRESS_FIELD, 0);
        });
        return stack;
    }

    /**
     * Advances the hatch of an egg carried by a player, once per step, and
     * hatches it when its time is up. Server side only; the cost is a party
     * scan of six Pokémon per egg every {@link #STEP_TICKS} ticks.
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player) || level.getGameTime() % STEP_TICKS != 0L) {
            return;
        }
        SafariConfig.EggNests config = SafariConfigManager.get().events.eggNests;
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        // An egg without a time (from the creative tab or a command) takes the config's.
        int hatch = data.getInt(HATCH_FIELD) > 0 ? data.getInt(HATCH_FIELD) : config.hatchMinutes * SECONDS_PER_MINUTE;
        int step = isWarmed(player, config) ? (int) Math.round(STEP_SECONDS * config.hatchSpeedMultiplier) : STEP_SECONDS;
        int progress = data.getInt(PROGRESS_FIELD) + step;
        if (progress >= hatch) {
            if (hatch(player, stack, data)) {
                return;
            }
            // No room for it: wait a minute rather than trying at every step.
            progress = Math.max(0, hatch - NO_ROOM_RETRY_SECONDS);
        }
        int saved = progress;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putInt(PROGRESS_FIELD, saved);
            tag.putInt(HATCH_FIELD, hatch);
        });
    }

    /** Whether one of the player's party has an ability that warms the eggs. */
    private static boolean isWarmed(ServerPlayer player, SafariConfig.EggNests config) {
        if (config.hatchSpeedAbilities.isEmpty()) {
            return false;
        }
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            String ability = pokemon.getAbility().getName().toLowerCase(Locale.ROOT);
            for (String wanted : config.hatchSpeedAbilities) {
                if (ability.equals(wanted.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Hatches the egg into its Pokémon, given to the party (or the PC), and
     * uses the egg up. False, with the egg kept, when there is no room or no
     * species to hatch.
     */
    private static boolean hatch(ServerPlayer player, ItemStack stack, CompoundTag data) {
        Species species = speciesOf(data);
        if (species == null) {
            species = EggSpecies.draw(player.getRandom());
        }
        if (species == null) {
            return false;
        }
        PokemonProperties properties = new PokemonProperties();
        properties.setSpecies(species.getResourceIdentifier().toString());
        properties.setLevel(HATCH_LEVEL);
        // The player is passed so the shiny roll sees their boosts, as for a wild spawn.
        Pokemon pokemon = properties.create(player);
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        // Cobblemon sends it to the PC when the party is full, and tells the player when neither has room.
        if (!party.add(pokemon)) {
            return false;
        }
        // After the add: the mark is synced to its owner, who is only known once it is stored.
        SafariMark.markHatched(pokemon);
        stack.shrink(1);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TURTLE_EGG_HATCH,
                SoundSource.PLAYERS, 1.0F, 1.0F);
        player.sendSystemMessage(Component.translatable(CobblemonSafari.MOD_ID + ".egg.hatched",
                pokemon.getSpecies().getTranslatedName().copy().withStyle(ChatFormatting.GOLD)));
        CobblemonSafari.LOGGER.debug("Safari egg of {} hatched for {}", species.getResourceIdentifier(),
                player.getGameProfile().getName());
        return true;
    }

    /** The species written on the egg, or null when absent or no longer loaded. */
    @Nullable
    private static Species speciesOf(CompoundTag data) {
        if (!data.contains(SPECIES_FIELD, Tag.TAG_STRING)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(data.getString(SPECIES_FIELD));
        return id == null ? null : PokemonSpecies.INSTANCE.getByIdentifier(id);
    }

    /**
     * Shows roughly how long the egg still needs, in whole minutes rounded
     * up, without naming the species: what hatches is the surprise.
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        int hatch = data.getInt(HATCH_FIELD);
        if (hatch <= 0) {
            return;
        }
        int left = Math.max(0, hatch - data.getInt(PROGRESS_FIELD));
        int minutes = (left + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE;
        Component line = minutes <= 1
                ? Component.translatable(CobblemonSafari.MOD_ID + ".egg.tooltip.soon")
                : Component.translatable(CobblemonSafari.MOD_ID + ".egg.tooltip.minutes", minutes);
        tooltip.add(line.copy().withStyle(ChatFormatting.GRAY));
    }
}
