package dev.darcosse.common.cobblemonsafari.decor;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.block.WallScreenSettings;
import dev.darcosse.common.cobblemonsafari.network.SafariBannerOpenPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariBannerSetPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Pictures of the Safari Banners, and the server side of choosing one.
 *
 * <p>A design is any texture under {@code textures/block/banner/}, in any
 * namespace: those land in the block atlas (vanilla's block atlas takes
 * every texture under {@code block/}), so a resource pack adds designs by
 * dropping PNGs there, and a {@code .png.mcmeta} animates one like any block
 * texture. The client lists them; the server, which has no textures, only
 * checks that an id points into that folder.
 *
 * <p>Who may change a banner follows the wall screens
 * ({@link WallScreenSettings#canEdit}): operators anywhere, other players
 * outside the Safari and away from Safari Gates.
 */
public final class BannerDesigns {

    /** Folder of the designs inside the block atlas; also the path prefix of every design id. */
    public static final String FOLDER = "block/banner/";

    /** Picture of a new banner. Must be one of the textures shipped with the mod. */
    public static final ResourceLocation DEFAULT = CobblemonSafari.id(FOLDER + "safari_crest");

    /**
     * Longest design id accepted from a client, in characters. Real ids are
     * a few dozen; the bound keeps a forged packet from storing anything big.
     */
    public static final int MAX_ID_LENGTH = 128;

    /** Farthest a player may stand from the banner they change, in blocks; as for wall screens. */
    private static final double MAX_DISTANCE = 8.0D;

    private BannerDesigns() {
    }

    /**
     * A design id from text, or null when it is not one: not a resource
     * location, too long, or outside {@link #FOLDER}.
     */
    @Nullable
    public static ResourceLocation parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_ID_LENGTH) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null || !id.getPath().startsWith(FOLDER) || id.getPath().length() == FOLDER.length()) {
            return null;
        }
        return id;
    }

    /** Opens the design picker of a clicked banner, or says in the action bar why not. */
    public static void open(ServerPlayer player, BlockPos pos) {
        Level level = player.level();
        if (!(level.getBlockEntity(pos) instanceof SafariBannerBlockEntity banner)) {
            return;
        }
        if (!WallScreenSettings.canEdit(player, level, pos)) {
            player.displayClientMessage(Component.translatable(CobblemonSafari.MOD_ID + ".message.banner_locked"), true);
            return;
        }
        SafariNetwork.sendBannerOpen(player, new SafariBannerOpenPayload(pos.immutable(), banner.design().toString()));
    }

    /**
     * Applies a design chosen on a client, after checking reach, block,
     * id and rights. Requests no honest client sends are ignored silently.
     */
    public static void handleSet(ServerPlayer player, SafariBannerSetPayload request) {
        BlockPos pos = request.pos();
        // Distance first: it keeps the reads below inside loaded chunks.
        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                > MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }
        Level level = player.level();
        ResourceLocation design = parse(request.design());
        if (design == null || !(level.getBlockEntity(pos) instanceof SafariBannerBlockEntity banner)) {
            return;
        }
        if (!WallScreenSettings.canEdit(player, level, pos)) {
            player.displayClientMessage(Component.translatable(CobblemonSafari.MOD_ID + ".message.banner_locked"), true);
            return;
        }
        banner.setDesign(design);
    }
}
