package dev.darcosse.common.cobblemonsafari.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.access.EntryResult;
import dev.darcosse.common.cobblemonsafari.access.SafariAccess;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.session.SafariSession;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;

/**
 * The Safari Ticket: the built-in item way into the Safari.
 *
 * <p>Used outside, it starts a session through {@link SafariAccess}, so any
 * registered entry requirement (fee, cooldown...) still applies, and it is
 * consumed only once the player is actually inside. Used inside, it extends the
 * running session when the config allows it: one item covers both "ticket"
 * and "time extension" from the design brief.
 *
 * <p>A ticket may carry its own duration in its custom data, e.g.
 * {@code /give @p cobblemon_safari:safari_ticket[custom_data={duration_seconds:600}]},
 * so a shop can sell short and long hunts without new items.
 */
public class SafariTicketItem extends Item {

    /** Custom-data key holding the ticket's duration. Part of the public /give contract. */
    public static final String DURATION_KEY = "duration_seconds";

    /** Source id stored on sessions started with a ticket; statistics and logs group by it. */
    public static final String SOURCE = CobblemonSafari.MOD_ID + ":ticket";

    private static final MapCodec<Integer> DURATION_FIELD = Codec.INT.fieldOf(DURATION_KEY);

    /** Plain item; properties come from {@link SafariItems}. */
    public SafariTicketItem(Properties properties) {
        super(properties);
    }

    /**
     * Redeems the ticket on the server. The client only reports success so the
     * hand swings; the server's answer is what counts.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (!redeem(serverPlayer, stack)) {
            return InteractionResultHolder.fail(stack);
        }
        // Creative players keep the ticket, like any consumable.
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /** Shows the hunt duration when the ticket carries one. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        // The client does not know the server's default, so only an explicit
        // duration can be shown as a number.
        Component duration = customDuration(stack)
                .<Component>map(seconds -> Component.literal(Ticks.format(Ticks.fromSeconds(seconds))))
                .orElseGet(() -> Component.translatable(CobblemonSafari.MOD_ID + ".item.safari_ticket.standard"));
        tooltip.add(Component.translatable(CobblemonSafari.MOD_ID + ".item.safari_ticket.duration", duration)
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(CobblemonSafari.MOD_ID + ".item.safari_ticket.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * Starts or extends a session.
     *
     * @return true when the ticket was used and must be consumed
     */
    private static boolean redeem(ServerPlayer player, ItemStack stack) {
        SafariConfig.Ticket config = SafariConfigManager.get().ticket;
        int ticks = Ticks.fromSeconds(customDuration(stack).orElse(config.defaultDurationSeconds));
        SafariSession session = SafariSessionManager.getSession(player);

        if (session != null) {
            if (!config.extendsActiveSession) {
                player.displayClientMessage(Component.translatable(key("message.already_inside")), true);
                return false;
            }
            int before = session.remainingTicks();
            int after = SafariSessionManager.addTime(player, ticks);
            // Already at the cap: consuming the ticket would take it for nothing.
            if (after <= before) {
                player.displayClientMessage(Component.translatable(key("message.ticket_at_max")), true);
                return false;
            }
            player.displayClientMessage(Component.translatable(key("message.ticket_extended"),
                    Ticks.format(after - before), Ticks.format(after)), true);
            return true;
        }

        EntryResult result = SafariAccess.requestEntry(player, ticks, SOURCE, false);
        if (!result.success() && result.reason() != null) {
            player.displayClientMessage(result.reason(), true);
        }
        return result.success();
    }

    /** The duration written on the ticket, if any and positive. */
    private static Optional<Integer> customDuration(ItemStack stack) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (data.isEmpty()) {
            return Optional.empty();
        }
        return data.read(DURATION_FIELD).result().filter(seconds -> seconds > 0);
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
