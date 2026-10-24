package dev.darcosse.common.cobblemonsafari.item;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The Escape Rope: used in the Safari, it offers to climb back to the open
 * air (from a cave, as in the games) or to go to the nearest rest house.
 * What it does lives in {@link EscapeRopeActions}; this class is the item.
 *
 * <p>Lent on entry and taken back on exit
 * ({@code dev.darcosse.common.cobblemonsafari.loan.EscapeRopes}); it never
 * wears out and only works inside the Safari. Any copy found outside
 * the Safari is removed, so it cannot become a free teleport elsewhere.
 */
public class SafariEscapeRopeItem extends Item {

    /** Rope item; properties come from {@link SafariItems}. */
    public SafariEscapeRopeItem(Properties properties) {
        super(properties);
    }

    /** Opens the rope's choice on the server; the client only swings. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        // Checked before the dialogue opens, so a refusal is said at once
        // rather than after a choice; the actions check again.
        Component refusal = EscapeRopeActions.refusal(serverPlayer);
        if (refusal != null) {
            serverPlayer.displayClientMessage(refusal, true);
            return InteractionResultHolder.fail(stack);
        }
        EscapeRopeActions.open(serverPlayer);
        // Never consumed nor damaged: the rope is lent for the whole hunt.
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /** Explains where it works and what it offers, which nothing else shows. */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(key("item.safari_escape_rope.hint")).withStyle(ChatFormatting.GRAY));
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
