package org.francium.cybercoreClient.mixin.client;

import net.minecraft.client.MouseHandler;
import org.francium.cybercoreClient.client.CybercoreClientClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cursor movement straight from GLFW's callback, before Minecraft decides what to do with it.
 *
 * <p>Vanilla forwards movement to the open screen from {@code handleAccumulatedMovement}, behind
 * {@code Minecraft.isWindowActive()} and once per frame. Clicks and keys take other paths, so when
 * that gate stays shut - reported on Linux - everything keeps working except hover, which is the
 * one thing that lives entirely on movement. LiquidBounce hooks the same method for the same
 * reason (MixinMouseHandler#hookCursorPos).
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "onMove", at = @At("HEAD"))
    private void cybercore$onCursorMove(long windowHandle, double x, double y, CallbackInfo info) {
        CybercoreClientClient.onCursorMove(windowHandle, x, y);
    }
}
