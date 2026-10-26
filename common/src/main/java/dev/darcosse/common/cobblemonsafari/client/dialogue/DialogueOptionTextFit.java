package dev.darcosse.common.cobblemonsafari.client.dialogue;

import com.cobblemon.mod.common.client.gui.dialogue.widgets.DialogueOptionWidget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Shrinks the text of a Cobblemon dialogue answer that is wider than its
 * button, so it stays inside it.
 *
 * <p>Why: Cobblemon centres the answer's text on its button whatever its
 * width, so a long answer (a mission with its reward, a rest house name in
 * German) spills over both edges. The button keeps its size; only the text
 * is scaled down around its centre.
 *
 * <p>An answer that fits is left entirely to Cobblemon. One that does not
 * is drawn here, background included, the way Cobblemon draws it
 * (DialogueOptionWidget.renderWidget in Cobblemon 1.8.0): its texture holds
 * three states stacked (idle, hovered, not selectable), each as tall as the
 * button.
 *
 * <p>Called by DialogueOptionTextFitMixin. Client only.
 */
public final class DialogueOptionTextFit {

    /** Free space kept on each side of the text, in GUI pixels, so it does not touch the button's border. */
    private static final int SIDE_MARGIN = 6;

    /**
     * Smallest scale the text is shrunk to. Below about half size the font
     * is no longer legible; a text that would need less overflows as before.
     */
    private static final float MIN_SCALE = 0.55F;

    /** Stacked states in the answer texture, as Cobblemon lays it out. */
    private static final int TEXTURE_STATES = 3;

    /**
     * Where Cobblemon puts the text's top relative to the button's middle,
     * in GUI pixels; kept so that a shrunk answer sits like the others.
     */
    private static final int TEXT_TOP_FROM_MIDDLE = -3;

    /** Text colours of Cobblemon's answers, ARGB: selectable, and greyed out. */
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int DISABLED_TEXT_COLOR = 0xFF8D8D8D;

    private DialogueOptionTextFit() {
    }

    /**
     * Draws the answer with its text shrunk to fit, when it needs it.
     * Cost: one text width measure per answer and frame.
     *
     * @return true when drawn here (Cobblemon's own drawing must be skipped), false to let Cobblemon draw it
     */
    public static boolean render(DialogueOptionWidget option, GuiGraphics graphics) {
        if (!option.getDialogueScreen().renderInput()) {
            // Cobblemon hides the answers while the line types out; let it.
            return false;
        }
        Font font = Minecraft.getInstance().font;
        Component text = option.getText();
        int textWidth = font.width(text);
        int room = option.getWidth() - 2 * SIDE_MARGIN;
        if (textWidth <= room || room <= 0) {
            return false;
        }
        int x = option.getX();
        int y = option.getY();
        int width = option.getWidth();
        int height = option.getHeight();
        int state = !option.getSelectable() ? 2 : option.isHovered() ? 1 : 0;
        // Cobblemon's blitk draws with blending on: the answer texture has transparent corners.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(option.getTexture(), x, y, 0.0F, state * height, width, height, width,
                height * TEXTURE_STATES);

        float scale = Math.max(MIN_SCALE, room / (float) textWidth);
        graphics.pose().pushPose();
        graphics.pose().translate(x + width / 2.0F, y + height / 2.0F, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, -textWidth / 2, TEXT_TOP_FROM_MIDDLE,
                option.getSelectable() ? TEXT_COLOR : DISABLED_TEXT_COLOR, true);
        graphics.pose().popPose();
        return true;
    }
}
