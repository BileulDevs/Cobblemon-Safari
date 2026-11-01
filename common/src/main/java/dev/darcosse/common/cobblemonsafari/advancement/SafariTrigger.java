package dev.darcosse.common.cobblemonsafari.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * The one advancement criterion of the Safari ({@code cobblemon_safari:safari_event}):
 * something happened to a player in the Safari, named by an event id
 * ({@link SafariAdvancements} lists them), with a value (a count, or 1).
 *
 * <p>JSON conditions: {@code event} (required), {@code value} (a vanilla
 * int range, any value when absent), {@code player} (a vanilla player
 * predicate, checked where the player stands when the event fires, e.g.
 * a biome or a structure). One generic trigger instead of one class per
 * advancement: adding an advancement only takes a JSON file and, for a new
 * kind of moment, one call to {@link #trigger}.
 */
public final class SafariTrigger extends SimpleCriterionTrigger<SafariTrigger.Instance> {

    /** Codec of the conditions; the field names are the JSON format of every Safari advancement. */
    private static final Codec<Instance> CODEC = RecordCodecBuilder.create(builder -> builder.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.fieldOf("event").forGetter(Instance::event),
            MinMaxBounds.Ints.CODEC.optionalFieldOf("value", MinMaxBounds.Ints.ANY).forGetter(Instance::value)
    ).apply(builder, Instance::new));

    /** {@inheritDoc} */
    @Override
    public Codec<Instance> codec() {
        return CODEC;
    }

    /**
     * Fires an event for one player: every criterion listening to that event
     * whose value range holds {@code value} (and whose player predicate
     * matches) is granted. Cheap when nobody listens: vanilla only walks the
     * listeners of this player, and a granted criterion stops listening.
     */
    public void trigger(ServerPlayer player, String event, int value) {
        trigger(player, instance -> instance.event.equals(event) && instance.value.matches(value));
    }

    /**
     * Conditions of one criterion.
     *
     * @param player predicate on the player, checked at the moment of the event
     * @param event  the event id the criterion listens to
     * @param value  the range the event's value must fall in
     */
    public record Instance(Optional<ContextAwarePredicate> player, String event, MinMaxBounds.Ints value)
            implements SimpleCriterionTrigger.SimpleInstance {
    }
}
