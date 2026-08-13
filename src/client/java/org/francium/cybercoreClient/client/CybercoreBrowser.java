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

    /** Fixed at creation, like the rendering path itself: the two may differ per browser. */
    private final boolean acceleratedFrames;

    /** True DIP scaling (device scale factor + coordinate conversion) vs the zoom fallback. */
    private final boolean dipScaling;

    CybercoreBrowser(MCEFClient client, String url, boolean transparent, MCEFBrowserSettings settings,
                     boolean acceleratedFrames, boolean dipScaling) {
        super(client, url, transparent, settings);
        this.acceleratedFrames = acceleratedFrames;
        this.dipScaling = dipScaling;
    }

    boolean isAcceleratedFrames() {
        return acceleratedFrames;
    }

    boolean usesDipScaling() {
        return dipScaling;
    }

    // ---- HiDPI ------------------------------------------------------------------------------
    //
    // The mod's callers all speak framebuffer pixels; Chromium, given a device scale factor,
    // expects view sizes and mouse coordinates in logical (DIP) units and rasters them scaled.
    // Converting here, at the boundary, keeps every caller unchanged and replaces the zoom hack:
    // zoom only enlarges a 1x layout, while a real scale factor makes Chromium lay out at the
    // logical size and raster at full native resolution - what a retina display needs.
    //
    // PER BROWSER, and only where proven: the accelerated path's frame filter chokes on a scale
    // factor (sizes stop lining up, frames drop in silence), and on Windows the software path
    // was seen ignoring the reported factor too - pages came out laid out in raw framebuffer
    // pixels, visibly small. So true DIP stays a macOS-software affair; everything else runs at
    // scale 1 with the OS scale folded into page zoom (see syncBrowserZoom).

    private int toDip(int pixels) {
        if (!dipScaling) {
            return pixels;
        }
        return Math.max(1, Math.round(pixels / CybercoreClientClient.displayScale()));
    }

    /**
     * Framebuffer pixels to the coordinate space this page sees (its client coordinates) - the
     * same conversion its mouse events go through. The toast hit-test compares mouse positions
     * against rectangles the overlay page reported in these coordinates.
     */
    int toClientCoord(int pixels) {
        return toDip(pixels);
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
        if (!dipScaling) {
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
