package dev.darcosse.common.cobblemonsafari.item;

import com.cobblemon.mod.common.api.dialogue.Dialogue;
import com.cobblemon.mod.common.api.dialogue.DialogueManager;
import com.cobblemon.mod.common.api.dialogue.Dialogues;
import com.cobblemon.mod.common.battles.BattleRegistry;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.teleport.RestHouseTravel;
import dev.darcosse.common.cobblemonsafari.teleport.SafariTeleporter;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

/**
 * What the Escape Rope does: it opens a small dialogue
 * ({@code data/cobblemon_safari/dialogues/escape_rope.json}) offering to
 * climb back to the open air, when the player is under cover, and to go to
 * the nearest rest house; the dialogue's options call {@link #climb} and
 * {@link #toRestHouse} through the player's MoLang functions
 * (command.SafariDialogueFunctions).
 *
 * <p>Every action checks again what the item checked before opening the
 * dialogue (inside the Safari, not in a battle): the dialogue stays open as
 * long as the player likes, and a battle may have started meanwhile.
 *
 * <p>Server side. The wait between two trips to a rest house is shared with
 * the Safari Guide, see {@link RestHouseTravel}.
 */
public final class EscapeRopeActions {

    /** The rope's dialogue. Part of the datapack contract: a server may override the file, not rename it. */
    public static final ResourceLocation DIALOGUE_ID = CobblemonSafari.id("escape_rope");

    /**
     * Seconds the rope stays unusable after any of its teleports, so a quick
     * second use does not open the dialogue again while the player is still
     * arriving.
     */
    private static final int TELEPORT_COOLDOWN_SECONDS = 5;

    /** Whether the missing dialogue was already logged, to log it once rather than on every use. */
    private static boolean missingDialogueReported;

    private EscapeRopeActions() {
    }

    /**
     * Why the rope cannot be used right now, or null when it can: outside
     * the Safari it does nothing, and teleporting a battler would leave
     * Cobblemon's battle with a participant elsewhere.
     */
    @Nullable
    public static Component refusal(ServerPlayer player) {
        if (!SafariDimensions.isSafari(player.level())) {
            return Component.translatable(key("message.rope_outside"));
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return Component.translatable(key("message.rope_in_battle"));
        }
        return null;
    }

    /**
     * Opens the rope's dialogue. Without it (a datapack removed the file),
     * falls back to the rope's first job: climbing out when under cover.
     */
    public static void open(ServerPlayer player) {
        Dialogue dialogue = Dialogues.INSTANCE.getDialogues().get(DIALOGUE_ID);
        if (dialogue == null) {
            if (!missingDialogueReported) {
                missingDialogueReported = true;
                CobblemonSafari.LOGGER.warn("Dialogue {} is missing; the Escape Rope only climbs out", DIALOGUE_ID);
            }
            climb(player);
            return;
        }
        DialogueManager.startDialogue(player, dialogue);
    }

    /**
     * Whether there is somewhere to climb to: the player's eyes are under
     * cover (a cave, an overhang, a canopy). The dialogue hides the option
     * otherwise.
     */
    public static boolean canClimb(ServerPlayer player) {
        return SafariDimensions.isSafari(player.level())
                && !player.level().canSeeSky(BlockPos.containing(player.getEyePosition()));
    }

    /**
     * Climbs to the best place to stand in the open air near the player
     * (SafariTeleporter.sendToSurface), not straight up: straight up can be
     * the inside of a tree crown.
     *
     * @return whether the player was moved
     */
    public static boolean climb(ServerPlayer player) {
        if (refuse(player)) {
            return false;
        }
        if (!canClimb(player)) {
            player.displayClientMessage(Component.translatable(key("message.rope_open_air")), true);
            return false;
        }
        if (!SafariTeleporter.sendToSurface(player, (ServerLevel) player.level(),
                player.getBlockX(), player.getBlockZ())) {
            player.displayClientMessage(Component.translatable(key("message.rope_no_spot")), true);
            return false;
        }
        arrived(player);
        return true;
    }

    /**
     * Takes the player in front of the nearest rest house, once per
     * {@code escapeRope.restHouseCooldownSeconds} (a wait shared with the
     * Safari Guide). Works in the open air too.
     *
     * @return whether the player was moved
     */
    public static boolean toRestHouse(ServerPlayer player) {
        if (refuse(player)) {
            return false;
        }
        int remaining = RestHouseTravel.remainingTicks(player);
        if (remaining > 0) {
            player.displayClientMessage(Component.translatable(key("message.rope_rest_house_wait"),
                    Ticks.format(remaining)), true);
            return false;
        }
        if (!SafariTeleporter.sendToRestHouse(player, (ServerLevel) player.level())) {
            player.displayClientMessage(Component.translatable(key("message.rope_no_rest_house")), true);
            return false;
        }
        RestHouseTravel.markTravelled(player);
        arrived(player);
        return true;
    }

    /** Tells the player why the rope refuses, if it does. */
    private static boolean refuse(ServerPlayer player) {
        Component reason = refusal(player);
        if (reason != null) {
            player.displayClientMessage(reason, true);
            return true;
        }
        return false;
    }

    /** The rope's sound where the player lands, and its short cooldown. */
    private static void arrived(ServerPlayer player) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        int cooldown = Ticks.fromSeconds(TELEPORT_COOLDOWN_SECONDS);
        if (cooldown > 0) {
            player.getCooldowns().addCooldown(SafariItems.escapeRope(), cooldown);
        }
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
