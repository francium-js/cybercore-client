package org.francium.cybercoreClient.client;

import com.mojang.blaze3d.platform.Window;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Draws the browser over the world whenever no screen is open. The page keeps its platform UI
 * collapsed via {@code content-visibility: hidden} for as long as the mod reports the screen
 * closed, so what this paints is the transparent in-world layer - and the collapse is a CSS flip
 * with no intermediate frames, so there is nothing to gate on.
 */
public class BrowserOverlay implements HudElement {

    private int lastWidth = 0;
    private int lastHeight = 0;
    private MCEFBrowser lastBrowser;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!McefBootstrap.isReady()) {
            return;
        }
        MCEFBrowser browser = CybercoreClientClient.browser;
        if (browser == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        if (mc.screen != null) {
            return;
        }

        Window window = mc.getWindow();

        int fbWidth = Math.max(1, window.getWidth());
        int fbHeight = Math.max(1, window.getHeight());
        // Also keyed on the browser itself: a replacement one starts unsized, and the window size
        // alone would not have changed to trigger a resize.
        if (browser != lastBrowser || fbWidth != lastWidth || fbHeight != lastHeight) {
            browser.resize(fbWidth, fbHeight);
            lastBrowser = browser;
            lastWidth = fbWidth;
            lastHeight = fbHeight;
        }

        Identifier texture = BrowserTexture.resolve(browser);
        if (texture != null) {
            BrowserScreen.applyBgraSwizzle(browser);
            int scaledWidth = window.getGuiScaledWidth();
            int scaledHeight = window.getGuiScaledHeight();
            graphics.blit(
                    BrowserScreen.renderPipelineFor(browser),
                    texture,
                    0, 0,
                    0f, 0f,
                    scaledWidth, scaledHeight,
                    scaledWidth, scaledHeight
            );
        }
    }
}
