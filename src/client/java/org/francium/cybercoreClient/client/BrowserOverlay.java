package org.francium.cybercoreClient.client;

import com.mojang.blaze3d.platform.Window;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Draws the transparent in-world layer (toasts, glitch effects) on top of whatever the game is
 * currently showing: the HUD, chat, any menu, the title screen, world-loading screens and the
 * resource-pack overlay alike. Hooked from the tail of {@code GameRenderer.extractGui} (see
 * GameRendererMixin), after everything vanilla has been extracted, in a stratum of its own - a
 * notification is never hidden behind the thing the player happens to be looking at.
 *
 * <p>The one exception is {@link BrowserScreen}: there the browser texture IS the screen, and
 * painting it a second time would double every toast.
 */
public final class BrowserOverlay {

    private static int lastWidth = 0;
    private static int lastHeight = 0;
    private static MCEFBrowser lastBrowser;

    private BrowserOverlay() {
    }

    public static void extract(GuiGraphicsExtractor graphics) {
        if (!McefBootstrap.isReady()) {
            return;
        }
        MCEFBrowser browser = CybercoreClientClient.browser;
        if (browser == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        if (mc.screen instanceof BrowserScreen) {
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

        // Until a frame painted AFTER the page confirmed the collapse lands in the texture, it
        // may still show the platform itself - a frame that is merely newer than the close can
        // honestly carry the old picture. Whatever fails upstream (hung renderer, lost shared
        // frame), the failure mode is a clean world, never a frozen platform.
        if (!CybercoreClientClient.mayDrawOverlay()) {
            return;
        }

        Identifier texture = BrowserTexture.resolve(browser);
        if (texture != null) {
            BrowserScreen.applyBgraSwizzle(browser);
            graphics.nextStratum();
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
