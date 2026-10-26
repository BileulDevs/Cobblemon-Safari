package dev.darcosse.common.cobblemonsafari.mixin.client;

import dev.darcosse.common.cobblemonsafari.client.dialogue.DialogueOptionPager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Lets the mod scroll the answers of Cobblemon's NPC dialogues when they run
 * off the screen ({@link DialogueOptionPager}), right after the screen has
 * laid its widgets out.
 *
 * <p>Why Minecraft's Screen and not Cobblemon's DialogueScreen: the methods
 * hooked are Minecraft's, which the loaders' mappings rename in a production
 * game; a mixin into Cobblemon's class would target the same methods under
 * names the mod's mappings may not resolve there. Every other screen is
 * left alone by the pager's first check.
 *
 * <p>Why the screen's widget lists rather than a shadow of
 * {@code Screen.addRenderableWidget}: that method is generic, and the
 * remapper that builds the Fabric jar leaves the name of a generic shadow
 * method unmapped, so the shadow was not found in production and the game
 * crashed at launch (1.2.0 on Fabric). Shadowed fields are remapped
 * reliably; the widget is added to the three lists exactly as
 * addRenderableWidget does.
 */
@Mixin(Screen.class)
public abstract class ScreenDialogueOptionsMixin {

    @Shadow
    @Final
    private List<GuiEventListener> children;

    @Shadow
    @Final
    private List<NarratableEntry> narratables;

    @Shadow
    @Final
    private List<Renderable> renderables;

    /**
     * After the first layout and after each resize. {@code require = 0}: if
     * another mod rewrote this method, long dialogues simply stay unpaged.
     */
    @Inject(method = "init(Lnet/minecraft/client/Minecraft;II)V", at = @At("TAIL"), require = 0)
    private void cobblemonSafari$pageDialogueOptionsOnInit(Minecraft minecraft, int width, int height,
                                                           CallbackInfo ci) {
        DialogueOptionPager.layout((Screen) (Object) this, this::cobblemonSafari$addWidget);
    }

    /** After a rebuild: Cobblemon rebuilds the widgets when the dialogue moves to another page. */
    @Inject(method = "rebuildWidgets", at = @At("TAIL"), require = 0)
    private void cobblemonSafari$pageDialogueOptionsOnRebuild(CallbackInfo ci) {
        DialogueOptionPager.layout((Screen) (Object) this, this::cobblemonSafari$addWidget);
    }

    /** What Screen.addRenderableWidget does: drawn, receives input, narrated. */
    @Unique
    private void cobblemonSafari$addWidget(AbstractWidget widget) {
        renderables.add(widget);
        children.add(widget);
        narratables.add(widget);
    }
}
