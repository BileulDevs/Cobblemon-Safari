package dev.darcosse.common.cobblemonsafari.egg;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.spawn.EggSpecies;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A straw nest holding a Pokémon egg, put near hunting players by
 * {@code EggNests}. A player takes the egg with a right click: with
 * {@code firstComeFirstServed} the nest is then gone, otherwise each player
 * may take one. It disappears when its time is up, even after a restart
 * (the time is the level's game time, saved with it). It cannot be hurt,
 * pushed or walked into. Its species is drawn when it is put out, or at the
 * first egg taken for one summoned by hand.
 */
public class SafariNestEntity extends Entity {

    /** Ticks in a minute, for the config's lifetime. */
    private static final long TICKS_PER_MINUTE = 1200L;

    private static final String SPECIES_FIELD = "species";
    private static final String EXPIRES_FIELD = "expires_at";
    private static final String TAKERS_FIELD = "takers";

    /** Species of the egg, by Cobblemon id; null until drawn. */
    @Nullable
    private ResourceLocation speciesId;

    /** Game time at which the nest is gone; 0 until set (a nest summoned by hand gets the config's lifetime). */
    private long expiresAt;

    /** Players who already took an egg here, for nests shared between players. */
    private final Set<UUID> takers = new HashSet<>();

    /** Entity type constructor, used by the game to create and load nests. */
    public SafariNestEntity(EntityType<? extends SafariNestEntity> type, Level level) {
        super(type, level);
        // A nest sits where it was put: no falling into a hole dug under it later.
        setNoGravity(true);
    }

    /**
     * Sets the egg's species and when the nest is gone, before it is added
     * to the level.
     */
    public void setUp(Species species, long expiresAt) {
        this.speciesId = species.getResourceIdentifier();
        this.expiresAt = expiresAt;
    }

    /** No synced data: clients only draw the nest, whatever its egg. */
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    /** Removes the nest once its time is up; server side, a comparison per tick. */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        if (expiresAt == 0L) {
            expiresAt = level().getGameTime() + lifetimeTicks();
        }
        if (level().getGameTime() >= expiresAt) {
            discard();
        }
    }

    /**
     * Gives the egg to the player who clicks. Both sides answer SUCCESS so the
     * arm swings at once; only the server gives anything.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        // One answer per click: the off hand would ask a second time.
        if (hand != InteractionHand.MAIN_HAND || isRemoved()) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            take(serverPlayer);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    /** Hands an egg to the player, unless they already took one here; empties a first-come nest. */
    private void take(ServerPlayer player) {
        SafariConfig.EggNests config = SafariConfigManager.get().events.eggNests;
        if (!config.firstComeFirstServed && takers.contains(player.getUUID())) {
            player.displayClientMessage(Component.translatable(key("egg.already_taken")), true);
            return;
        }
        Species species = species();
        if (species == null) {
            // Every line filtered out by the config: an egg with nothing to hatch would be a lie.
            player.displayClientMessage(Component.translatable(key("egg.empty_nest")), true);
            discard();
            return;
        }
        ItemStack egg = SafariEggItem.create(species, config.hatchMinutes);
        if (!player.addItem(egg)) {
            // A full inventory still gets the egg, at the player's feet, rather than losing the nest.
            player.drop(egg, false);
        }
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.TURTLE_EGG_CRACK, SoundSource.NEUTRAL,
                1.0F, 1.0F);
        player.displayClientMessage(Component.translatable(key("egg.taken")), true);
        if (config.firstComeFirstServed) {
            discard();
        } else {
            takers.add(player.getUUID());
        }
    }

    /**
     * The egg's species: the one drawn, or a new draw when it is unknown
     * (summoned by hand, or its species removed by a data pack since).
     */
    @Nullable
    private Species species() {
        Species species = speciesId == null ? null : PokemonSpecies.INSTANCE.getByIdentifier(speciesId);
        if (species == null) {
            // A nest summoned by hand takes an egg of its habitat too; outside any
            // Safari habitat (a test in another world) any line will do.
            if (level() instanceof ServerLevel server) {
                species = EggSpecies.drawFor(server, blockPosition(), server.getRandom());
            }
            if (species == null) {
                species = EggSpecies.draw(level().getRandom());
            }
            if (species != null) {
                speciesId = species.getResourceIdentifier();
            }
        }
        return species;
    }

    /** The config's nest lifetime, in ticks. */
    private static long lifetimeTicks() {
        return SafariConfigManager.get().events.eggNests.lifetimeMinutes * TICKS_PER_MINUTE;
    }

    /** Clickable: the crosshair must be able to pick the nest for a right click. */
    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    /** Nothing pushes a nest around. */
    @Override
    public boolean isPushable() {
        return false;
    }

    /** A left click does nothing rather than "attacking" the nest. */
    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        return true;
    }

    /** Nothing hurts a nest: only its time, or a player taking its egg, ends it. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    /** Loads the species, end of life and takers. */
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        speciesId = tag.contains(SPECIES_FIELD, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(SPECIES_FIELD)) : null;
        expiresAt = tag.getLong(EXPIRES_FIELD);
        takers.clear();
        ListTag list = tag.getList(TAKERS_FIELD, Tag.TAG_INT_ARRAY);
        for (Tag entry : list) {
            takers.add(NbtUtils.loadUUID(entry));
        }
    }

    /** Saves the species, end of life and takers. */
    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (speciesId != null) {
            tag.putString(SPECIES_FIELD, speciesId.toString());
        }
        tag.putLong(EXPIRES_FIELD, expiresAt);
        ListTag list = new ListTag();
        for (UUID uuid : takers) {
            list.add(NbtUtils.createUUID(uuid));
        }
        tag.put(TAKERS_FIELD, list);
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
