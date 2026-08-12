package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEFPlatform;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowserSettings;
import net.ccbluex.liquidbounce.mcef.cef.MCEFClient;
import net.minecraft.client.Minecraft;
import org.cef.browser.CefBrowser;
import org.cef.event.CefMouseWheelEvent;
import org.cef.handler.CefScreenInfo;
import org.lwjgl.glfw.GLFW;

import java.awt.Rectangle;

/**
 * {@link MCEFBrowser} plus what the mod needs from a browser: true HiDPI on the software path and
 * touchpad-grade wheel input. Subclassing is the fork's own intended escape hatch ("in case a mod
 * wants to extend MCEFBrowser and override the repaint logic").
 */
final class CybercoreBrowser extends MCEFBrowser {

    CybercoreBrowser(MCEFClient client, String url, boolean transparent, MCEFBrowserSettings settings) {
        super(client, url, transparent, settings);
    }

    // ---- HiDPI ------------------------------------------------------------------------------
    //
    // The mod's callers all speak framebuffer pixels; Chromium, given a device scale factor,
    // expects view sizes and mouse coordinates in logical (DIP) units and rasters them scaled.
    // Converting here, at the boundary, keeps every caller unchanged and replaces the old zoom
    // hack: zoom only enlarged a 1x layout, while a real scale factor makes Chromium lay out at
    // the logical size and raster at full native resolution - which is what a retina display
    // needs to look sharp.
    //
    // Software rendering only. The accelerated path's frame filter accepts a frame after any size
    // change only when its damage covers the whole texture, and with a scale factor in play the
    // sizes it compares stop lining up - every frame is then dropped in silence and the screen
    // stays empty for good. So on accelerated the browser behaves exactly as stock, scale 1 and
    // the zoom fallback, and true HiDPI stays where it is known to work.

    private static boolean hiDpi() {
        return !McefBootstrap.isAcceleratedPaint();
    }

    private static int toDip(int pixels) {
        if (!hiDpi()) {
            return pixels;
        }
        return Math.max(1, Math.round(pixels / CybercoreClientClient.displayScale()));
    }

    @Override
    public void resize(int width, int height) {
        super.resize(toDip(width), toDip(height));
    }

    @Override
    public void sendMouseMove(int mouseX, int mouseY) {
        super.sendMouseMove(toDip(mouseX), toDip(mouseY));
    }

    @Override
    public void sendMousePress(int mouseX, int mouseY, int button) {
        super.sendMousePress(toDip(mouseX), toDip(mouseY), button);
    }

    @Override
    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        super.sendMouseRelease(toDip(mouseX), toDip(mouseY), button);
    }

    @Override
    public void sendMouseWheel(int mouseX, int mouseY, double amount) {
        super.sendMouseWheel(toDip(mouseX), toDip(mouseY), amount);
    }

    /**
     * The wheel with everything MCEF's own overload throws away: the horizontal axis and the
     * keyboard modifiers.
     *
     * <p>Both matter for touchpads. Pinch-to-zoom never reaches an app as a gesture - Windows
     * precision drivers synthesize it as ctrl+wheel, and a map only zooms if the page actually
     * sees the ctrl - while two-finger horizontal panning arrives as scrollX, which the
     * single-axis overload drops on the floor. The event struct has one delta, so the horizontal
     * axis rides the web's own convention of shift+wheel meaning sideways.
     */
    void sendMouseWheel(int mouseX, int mouseY, double scrollX, double scrollY, int glfwModifiers) {
        int x = toDip(mouseX);
        int y = toDip(mouseY);
        if (scrollY != 0) {
            sendWheel(x, y, scrollY, glfwModifiers);
        }
        if (scrollX != 0) {
            sendWheel(x, y, scrollX, glfwModifiers | GLFW.GLFW_MOD_SHIFT);
        }
    }

    /** MCEF's feel adjustments, replicated: mac deltas pass through, mouse wheels get snapped. */
    private void sendWheel(int x, int y, double amount, int glfwModifiers) {
        if (!MCEFPlatform.getPlatform().isMacOS()) {
            amount = amount < 0 ? Math.floor(amount) : Math.ceil(amount);
            amount *= 3;
        }
        sendMouseWheelEvent(new CefMouseWheelEvent(
                CefMouseWheelEvent.WHEEL_UNIT_SCROLL, x, y, amount, glfwModifiers));
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        if (!hiDpi()) {
            return super.getScreenInfo(browser, screenInfo);
        }
        float scale = CybercoreClientClient.displayScale();
        var window = Minecraft.getInstance().getWindow();
        Rectangle rect = new Rectangle(0, 0,
                Math.max(1, Math.round(window.getWidth() / scale)),
                Math.max(1, Math.round(window.getHeight() / scale)));
        screenInfo.Set(scale, 32, 8, false, rect, rect);
        return true;
    }
}
