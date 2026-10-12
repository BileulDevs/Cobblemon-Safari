package dev.darcosse.common.cobblemonsafari.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.credit.SafariCredit;
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
 * A Safari Ticket: Safari time in item form.
 *
 * <p>Used outside the Safari, it banks its time ({@link SafariCredit}); the
 * player then spends the bank to enter. Used inside, it extends the running
 * session when the config allows it.
 *
 * <p>A ticket adds {@code ticket.defaultDurationSeconds} (30 min by default),
 * or the length written in its own custom data, e.g.
 * {@code /give @p cobblemon_safari:safari_ticket[custom_data={duration_seconds:600}]},
 * so a server can still hand out other lengths. When the bank (or the hunt)
 * is nearly full, the ticket adds only what fits, but never less than
 * {@code ticket.minAddedSeconds}: below that it is refused and kept.
 */
public class SafariTicketItem extends Item {

    /** Custom-data key holding the ticket's duration. Part of the public /give contract. */
    public static final String DURATION_KEY = "duration_seconds";

    /** Source id stored on sessions started with a ticket; statistics and logs group by it. */
    public static final String SOURCE = CobblemonSafari.MOD_ID + ":ticket";

    private static final MapCodec<Integer> DURATION_FIELD = Codec.INT.fieldOf(DURATION_KEY);

    /** Ticket item; properties come from {@link SafariItems}. */
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
     * Banks the ticket's time, or adds it to the running session when used
     * inside and the config allows it.
     *
     * @return true when the ticket was used and must be consumed
     */
    private boolean redeem(ServerPlayer player, ItemStack stack) {
        SafariConfig.Ticket config = SafariConfigManager.get().ticket;
        int ticks = Ticks.fromSeconds(customDuration(stack).orElse(config.defaultDurationSeconds));
        // A ticket shorter than the minimum only needs room for itself.
        int minimum = Math.min(ticks, Ticks.fromSeconds(config.minAddedSeconds));
        SafariSession session = SafariSessionManager.getSession(player);

        if (session != null && config.extendsActiveSession) {
            int before = session.remainingTicks();
            int room = SafariConfigManager.maxDurationTicks() - before;
            if (!hasRoom(player, room, minimum, "message.ticket_at_max")) {
                return false;
            }
            int after = SafariSessionManager.addTime(player, Math.min(ticks, room));
            player.displayClientMessage(Component.translatable(key("message.ticket_extended"),
                    Ticks.format(after - before), Ticks.format(after)), true);
            return true;
        }

        int room = Ticks.fromSeconds(config.maxCreditSeconds) - SafariCredit.balance(player);
        if (!hasRoom(player, room, minimum, "message.credit_full")) {
            return false;
        }
        int added = SafariCredit.add(player, Math.min(ticks, room));
        player.displayClientMessage(Component.translatable(key("message.credit_added"),
                Ticks.format(added), Ticks.format(SafariCredit.balance(player))), true);
        return true;
    }

    /**
     * Whether a ticket may be used given the room left under the cap; tells
     * the player why not otherwise. No room at all and too little room get
     * different messages: "full" is clearer than "only 0:00 left".
     *
     * @param room       ticks that can still be added before the cap
     * @param minimum    fewest ticks the ticket may add
     * @param fullKey    message key for a completely full bank or hunt
     */
    private static boolean hasRoom(ServerPlayer player, int room, int minimum, String fullKey) {
        if (room <= 0) {
            player.displayClientMessage(Component.translatable(key(fullKey)), true);
            return false;
        }
        if (room < minimum) {
            player.displayClientMessage(Component.translatable(key("message.ticket_too_little_room"),
                    Ticks.format(room), Ticks.format(minimum)), true);
            return false;
        }
        return true;
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
