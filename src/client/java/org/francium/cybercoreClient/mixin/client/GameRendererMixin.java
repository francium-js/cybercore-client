package org.francium.cybercoreClient.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import org.francium.cybercoreClient.client.BrowserOverlay;
import org.francium.cybercoreClient.client.McefBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives CEF's message loop from Minecraft's frame loop - MCEF's JCEF fork leaves
 * {@code doMessageLoopWork} empty, so nothing pumps CEF unless we do.
 *
 * <p>It has to be here rather than on a tick: CEF paints back on whichever thread runs the loop,
 * and MCEF's paint handlers talk to OpenGL with no thread hop.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void cybercore$pumpCef(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo info) {
        McefBootstrap.pumpMessageLoop();
    }

    /**
     * Second pump per frame, at the tail. With V-sync the render thread spends most of its time
     * parked in the swap, and a browser frame born mid-game-frame would otherwise wait for the
     * NEXT frame's head pump - up to a whole display interval of extra latency, and when the
     * game drops a vblank, two browser frames arrive glued together. A delivery point on each
     * side of the frame halves both effects; an empty pump costs microseconds.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void cybercore$pumpCefTail(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo info) {
        McefBootstrap.pumpMessageLoop();
    }

    /**
     * {@code extractGui} is the one funnel every piece of 2D the game shows passes through - HUD,
     * chat, menu screens, the title screen, loading screens and the resource-pack overlay - and
     * its tail is past them all, so the browser's transparent notification layer lands on top of
     * each of them (see BrowserOverlay).
     */
    @Inject(method = "extractGui", at = @At("TAIL"))
    private void cybercore$extractBrowserLayer(DeltaTracker deltaTracker, boolean renderGui,
                                               boolean renderOverlay, CallbackInfo info,
                                               @Local GuiGraphicsExtractor graphics) {
        BrowserOverlay.extract(graphics);
    }
}
