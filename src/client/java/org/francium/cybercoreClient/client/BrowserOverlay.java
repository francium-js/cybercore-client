package org.francium.cybercoreClient.client;

import com.mojang.blaze3d.platform.Window;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Draws the overlay browser - the notification layer - on top of whatever the game is currently
 * showing: the HUD, chat, any menu, the title screen, loading screens, and the platform screen
 * itself. Hooked from the tail of {@code GameRenderer.extractGui} (see GameRendererMixin), after
 * everything vanilla has been extracted, in a stratum of its own: a notification is never hidden
 * behind the thing the player happens to be looking at.
 *
 * <p>This browser hosts nothing but layers meant to float over the world, so there is no gate to
 * keep: whatever its texture holds is drawn over transparency, and the worst a stale frame can
 * show is an outdated toast. It shares the platform's frame path, so on the GPU path it shares
 * MCEF's silent frame drops as well - one toggle switches both layers to software.
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
        MCEFBrowser browser = CybercoreClientClient.overlayBrowser;
        if (browser == null) {
            return;
        }

        Window window = Minecraft.getInstance().getWindow();

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
