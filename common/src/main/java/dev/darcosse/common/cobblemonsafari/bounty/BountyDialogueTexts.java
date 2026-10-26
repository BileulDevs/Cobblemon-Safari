package dev.darcosse.common.cobblemonsafari.bounty;

import com.cobblemon.mod.common.api.dialogue.ActiveDialogue;
import com.cobblemon.mod.common.api.dialogue.DialogueText;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * The Cobblemon dialogue texts of the clerk's mission swap, which name what
 * only the server knows: the player's own missions and how many swaps they
 * have left. Same pattern as RestHouseNameText: built on the server, sent
 * as components that every client translates.
 *
 * <p>Built by Cobblemon's dialogue Gson from the JSON, which fills the
 * public fields and ignores the "type" key.
 */
public final class BountyDialogueTexts {

    private BountyDialogueTexts() {
    }

    /** Adds both types to Cobblemon's dialogue text types; once, from common init, before any datapack loads. */
    public static void register() {
        DialogueText.Companion.getTypes().put(Mission.TYPE, Mission.class);
        DialogueText.Companion.getTypes().put(SwapPrompt.TYPE, SwapPrompt.class);
    }

    /**
     * One of the player's missions, for the swap answers:
     * <pre>{"type": "cobblemon_safari:bounty_mission", "slot": 0}</pre>
     */
    public static final class Mission implements DialogueText {

        /** The type's id in dialogue files. Renaming it breaks every dialogue using it. */
        public static final String TYPE = CobblemonSafari.MOD_ID + ":bounty_mission";

        /** The mission's place in the list, from 0. */
        public int slot;

        /** Gson's constructor. */
        public Mission() {
        }

        /** The mission and its reward, or a dash when the slot is empty (the answer is hidden then anyway). */
        @Override
        public MutableComponent invoke(ActiveDialogue dialogue) {
            List<Bounty> mine = SafariBounties.missionsOf(dialogue.getPlayerEntity());
            if (slot < 0 || slot >= mine.size()) {
                return Component.literal("-");
            }
            Bounty bounty = mine.get(slot);
            return Component.empty().append(bounty.describe()).append(Component.translatable(
                    CobblemonSafari.MOD_ID + ".bounty.reward", SafariPointsIcon.amount(bounty.reward())));
        }
    }

    /**
     * The clerk's question before the swap, with its cost and the swaps left:
     * <pre>{"type": "cobblemon_safari:bounty_swap_prompt"}</pre>
     */
    public static final class SwapPrompt implements DialogueText {

        /** The type's id in dialogue files. Renaming it breaks every dialogue using it. */
        public static final String TYPE = CobblemonSafari.MOD_ID + ":bounty_swap_prompt";

        /** Gson's constructor. */
        public SwapPrompt() {
        }

        /** "Which mission should I replace? It costs 50 points; 1 swap left." */
        @Override
        public MutableComponent invoke(ActiveDialogue dialogue) {
            ServerPlayer player = dialogue.getPlayerEntity();
            return Component.translatable(CobblemonSafari.MOD_ID + ".dialogue.safari_clerk.swap_prompt",
                    SafariPointsIcon.amount(SafariConfigManager.get().bounties.swap.cost),
                    SafariBounties.swapsLeft(player));
        }
    }
}
