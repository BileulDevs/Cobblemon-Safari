package dev.darcosse.common.cobblemonsafari.item;

import com.cobblemon.mod.common.battles.BattleRegistry;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.teleport.SafariTeleporter;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The Escape Rope: used in a cave of the Safari, it brings the player back to
 * the open air right above them, as in the games.
 *
 * <p>Lent on entry and taken back on exit ({@code dev.darcosse.common.cobblemonsafari.loan.EscapeRopes});
 * it never wears out and only works inside the Safari. Any copy found outside
 * the Safari is removed, so it cannot become a free teleport elsewhere.
 */
public class SafariEscapeRopeItem extends Item {

    /**
     * Seconds before the rope can be used again, so a double click does not
     * send a second request while the first teleport is on its way.
     */
    private static final int COOLDOWN_SECONDS = 5;

    /** Rope item; properties come from {@link SafariItems}. */
    public SafariEscapeRopeItem(Properties properties) {
        super(properties);
    }

    /** Climbs out on the server; the client only swings. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (!SafariDimensions.isSafari(level)) {
            serverPlayer.displayClientMessage(Component.translatable(key("message.rope_outside")), true);
            return InteractionResultHolder.fail(stack);
        }
        // Teleporting a battler leaves Cobblemon's battle with a participant elsewhere.
        if (BattleRegistry.getBattleByParticipatingPlayer(serverPlayer) != null) {
            serverPlayer.displayClientMessage(Component.translatable(key("message.rope_in_battle")), true);
            return InteractionResultHolder.fail(stack);
        }
        BlockPos eyes = BlockPos.containing(player.getEyePosition());
        if (level.canSeeSky(eyes)) {
            serverPlayer.displayClientMessage(Component.translatable(key("message.rope_open_air")), true);
            return InteractionResultHolder.fail(stack);
        }
        SafariTeleporter.sendToSurface(serverPlayer, serverLevel, player.getBlockX(), player.getBlockZ());
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CHORUS_FRUIT_TELEPORT,
                SoundSource.PLAYERS, 1.0F, 1.0F);
        int cooldown = Ticks.fromSeconds(COOLDOWN_SECONDS);
        if (cooldown > 0) {
            player.getCooldowns().addCooldown(this, cooldown);
        }
        // Never consumed nor damaged: the rope is lent for the whole hunt.
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /** Explains where it works, which nothing else shows. */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(key("item.safari_escape_rope.hint")).withStyle(ChatFormatting.GRAY));
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
