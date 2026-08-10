package org.francium.cybercoreClient.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
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
}
