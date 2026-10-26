package dev.darcosse.common.cobblemonsafari.mixin.client;

import com.cobblemon.mod.common.client.gui.dialogue.widgets.DialogueOptionWidget;
import dev.darcosse.common.cobblemonsafari.client.dialogue.DialogueOptionTextFit;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the text of Cobblemon's dialogue answers inside their buttons
 * ({@link DialogueOptionTextFit}).
 *
 * <p>Why at the head of the method rather than around Cobblemon's text call:
 * that call is to a Kotlin helper (GuiUtilsKt.drawCenteredText$default)
 * whose descriptor names Minecraft classes, which the mixin processor
 * cannot remap for a non-Minecraft owner, so on Fabric the injection would
 * not find its target. The head only needs renderWidget, a Minecraft
 * method the processor maps through the class hierarchy.
 */
@Mixin(DialogueOptionWidget.class)
public abstract class DialogueOptionTextFitMixin {

    /**
     * Draws an overflowing answer here and skips Cobblemon's drawing.
     * {@code require = 0}: if the method cannot be found (another Cobblemon
     * version), long answers simply overflow as before.
     */
    @Inject(method = "renderWidget(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"),
            cancellable = true, require = 0)
    private void cobblemonSafari$fitText(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                         CallbackInfo ci) {
        if (DialogueOptionTextFit.render((DialogueOptionWidget) (Object) this, graphics)) {
            ci.cancel();
        }
    }
}
