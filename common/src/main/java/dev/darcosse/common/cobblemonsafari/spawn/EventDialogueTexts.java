package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.dialogue.ActiveDialogue;
import com.cobblemon.mod.common.api.dialogue.DialogueText;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.util.HabitatNames;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;

/**
 * The clerk's explanations of the Safari's events, one dialogue page each.
 * Built on the server from the current config, so the chances, types and
 * places the clerk names are the ones in force, and sent as components each
 * client translates (same pattern as the mission swap texts).
 *
 * <p>Built by Cobblemon's dialogue Gson from the JSON, which fills the
 * public fields and ignores the "type" key.
 */
public final class EventDialogueTexts {

    /** Event ids, as the dialogue file, the translation keys and the visibility functions name them. */
    public static final String SWARM = "swarm";
    public static final String FULL_MOON = "full_moon";
    public static final String DOWNPOUR = "downpour";
    public static final String STORM = "storm";
    public static final String HEATWAVE = "heatwave";
    public static final String AURORA = "aurora";
    public static final String EGG_NESTS = "egg_nests";

    /** Every event the clerk can explain, in the order of the dialogue. */
    public static final List<String> EVENTS = List.of(SWARM, FULL_MOON, DOWNPOUR, STORM, HEATWAVE, AURORA, EGG_NESTS);

    /** Hundred, to turn a chance from 0 to 1 into the percentage the clerk says. */
    private static final double PERCENT = 100.0D;

    private EventDialogueTexts() {
    }

    /** Adds the type to Cobblemon's dialogue text types; once, from common init, before any datapack loads. */
    public static void register() {
        DialogueText.Companion.getTypes().put(EventInfo.TYPE, EventInfo.class);
    }

    /**
     * Whether an event is on in the config; the clerk only offers to explain
     * those. An unknown id is off.
     */
    public static boolean isEnabled(String event) {
        SafariConfig.Events events = SafariConfigManager.get().events;
        return switch (event) {
            case SWARM -> events.swarm.enabled;
            case FULL_MOON -> events.fullMoon.enabled;
            case DOWNPOUR -> events.downpour.enabled;
            case STORM -> events.storm.enabled;
            case HEATWAVE -> events.heatwave.enabled;
            case AURORA -> events.aurora.enabled;
            case EGG_NESTS -> events.eggNests.enabled;
            default -> false;
        };
    }

    /**
     * One event's explanation, followed by its mark when the mark is on:
     * <pre>{"type": "cobblemon_safari:event_info", "event": "aurora"}</pre>
     */
    public static final class EventInfo implements DialogueText {

        /** The type's id in dialogue files. Renaming it breaks every dialogue using it. */
        public static final String TYPE = CobblemonSafari.MOD_ID + ":event_info";

        /** Which event, one of {@link #EVENTS}. */
        public String event;

        /** Gson's constructor. */
        public EventInfo() {
        }

        /** The explanation with the config's numbers, then the mark's line; a dash for an unknown event. */
        @Override
        public MutableComponent invoke(ActiveDialogue dialogue) {
            SafariConfig config = SafariConfigManager.get();
            SafariConfig.Events events = config.events;
            SafariConfig.Marks marks = config.capture.marks;
            String key = CobblemonSafari.MOD_ID + ".dialogue.safari_clerk.event." + event;
            return switch (event == null ? "" : event) {
                case SWARM -> withMark(Component.translatable(key, percent(events.swarm.chance)), marks.swarm, key);
                case FULL_MOON -> withMark(Component.translatable(key,
                        SafariHappenings.typeNames(events.fullMoon.types)), marks.fullMoon, key);
                case DOWNPOUR -> withMark(Component.translatable(key, percent(events.downpour.chance),
                        SafariHappenings.typeNames(events.downpour.types)), marks.downpour, key);
                case STORM -> withMark(Component.translatable(key, percent(events.storm.chance),
                        SafariHappenings.typeNames(events.storm.types), habitatNames(events.storm.habitats)),
                        marks.storm, key);
                // No mark of its own: the heatwave only weighs Cobblemon's spawns.
                case HEATWAVE -> Component.translatable(key, percent(events.heatwave.chance),
                        SafariHappenings.typeNames(events.heatwave.types), habitatNames(events.heatwave.habitats));
                case AURORA -> withMark(Component.translatable(key, percent(events.aurora.chance),
                        SafariHappenings.typeNames(events.aurora.frozenTypes),
                        SafariHappenings.typeNames(events.aurora.otherTypes),
                        Aurora.formatMultiplier(events.aurora.shinyChanceMultiplier)), marks.aurora, key);
                case EGG_NESTS -> withMark(Component.translatable(key, events.eggNests.hatchMinutes),
                        marks.nest, key);
                default -> Component.literal("-");
            };
        }
    }

    /** The explanation, followed by the sentence about the event's mark when that mark is given. */
    private static MutableComponent withMark(MutableComponent text, boolean markOn, String key) {
        return markOn ? text.append(" ").append(Component.translatable(key + ".mark")) : text;
    }

    /**
     * A chance from 0 to 1 as a percentage without its sign ("15", "2.5"),
     * the sign being in each language's sentence with its own spacing.
     */
    private static String percent(double chance) {
        double value = chance * PERCENT;
        return value == Math.rint(value) ? Long.toString((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }

    /** Habitats of the config as their short names, comma separated; an id that does not parse is shown as written. */
    private static Component habitatNames(List<String> ids) {
        MutableComponent names = Component.empty();
        boolean first = true;
        for (String id : ids) {
            if (!first) {
                names.append(", ");
            }
            ResourceLocation biome = ResourceLocation.tryParse(id);
            names.append(biome != null ? HabitatNames.name(biome) : Component.literal(id));
            first = false;
        }
        return names;
    }
}
