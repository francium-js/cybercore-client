package org.francium.cybercoreClient.client;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.ccbluex.liquidbounce.mcef.cef.MCEFRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL33;

import java.lang.reflect.Field;

public class BrowserScreen extends Screen {

    private static final int MOUSE_BUTTON_BITS = 16 | 32 | 64;
    private static final int KEYBOARD_MOD_BITS =
            GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER;
    private static final Field BTN_MASK_FIELD = resolveBtnMaskField();

    private static Field resolveBtnMaskField() {
        try {
            Field f = MCEFBrowser.class.getDeclaredField("btnMask");
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    static RenderPipeline renderPipelineFor(MCEFBrowser browser) {
        return browser.getRenderer().isTransparent() && usePremultipliedAlpha
                ? RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA
                : RenderPipelines.GUI_TEXTURED;
    }

    /**
     * Corrects the channel order of accelerated frames, which CEF hands over as BGRA while MCEF
     * imports them as RGBA8. Vanilla's GUI pipeline samples straight through, so the swap is set on
     * the texture rather than in a shader - and re-applied every frame, because nothing guarantees
     * the parameter survives whoever else touches the texture in between.
     */
    static void applyBgraSwizzle(MCEFBrowser browser) {
        MCEFRenderer renderer = browser.getRenderer();
        if (!renderer.isBGRA()) {
            return;
        }
        int textureId = renderer.getTextureId();
        if (textureId == 0) {
            return;
        }
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_R,
                swizzleBgra ? GL11.GL_BLUE : GL11.GL_RED);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_B,
                swizzleBgra ? GL11.GL_RED : GL11.GL_BLUE);
        // Put back whatever was bound: this runs mid-extraction, in the middle of someone else's
        // state. Restoring through GlStateManager keeps its cache honest about the real binding.
        GlStateManager._bindTexture(previous);
    }

    /** Which way round the channels come out has differed by driver; the config file decides. */
    private static final boolean swizzleBgra = CybercoreConfig.isSwapRedBlue();

    private static final boolean usePremultipliedAlpha = true;

    private final CybercoreBrowser browser;

    private boolean swallowNextChar = false;

    private boolean forwardedBrowserKey = false;

    public BrowserScreen(CybercoreBrowser browser) {
        super(Component.translatable("gui.cybercore.browser.title"));
        this.browser = browser;
    }

    @Override
    protected void init() {
        var window = minecraft.getWindow();
        browser.resize(Math.max(1, window.getWidth()), Math.max(1, window.getHeight()));

        BrowserTextInputTracker.install(browser);

        browser.setFocus(true);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Identifier texture = BrowserTexture.resolve(browser);
        if (texture != null) {
            applyBgraSwizzle(browser);
            g.blit(renderPipelineFor(browser), texture, 0, 0, 0f, 0f, width, height, width, height);
        }
    }

    private int toBrowserX(double guiX) {
        return (int) (guiX * minecraft.getWindow().getGuiScale());
    }

    private int toBrowserY(double guiY) {
        return (int) (guiY * minecraft.getWindow().getGuiScale());
    }

    private void setKeyboardModifiers(int glfwModifiers) {
        if (BTN_MASK_FIELD == null) {
            return;
        }
        try {
            int current = BTN_MASK_FIELD.getInt(browser);
            int updated = (current & MOUSE_BUTTON_BITS) | (glfwModifiers & KEYBOARD_MOD_BITS);
            BTN_MASK_FIELD.setInt(browser, updated);
        } catch (IllegalAccessException ignored) {
        }
    }

    @Override
    public void mouseMoved(double x, double y) {
        browser.sendMouseMove(toBrowserX(x), toBrowserY(y));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isFocused) {
        setKeyboardModifiers(event.modifiers());
        browser.sendMousePress(toBrowserX(event.x()), toBrowserY(event.y()), event.button());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        setKeyboardModifiers(event.modifiers());
        browser.sendMouseRelease(toBrowserX(event.x()), toBrowserY(event.y()), event.button());
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        setKeyboardModifiers(event.modifiers());
        browser.sendMouseMove(toBrowserX(event.x()), toBrowserY(event.y()));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Both axes and the live modifiers: two-finger horizontal panning arrives as scrollX, and
        // touchpad pinch-zoom arrives as ctrl+wheel - dropping either kills the gesture. Queried
        // from GLFW directly because the scroll callback itself carries no modifier state, and
        // Minecraft's own helpers remap ctrl to cmd on macOS, which the page must not see.
        browser.sendMouseWheel(toBrowserX(mouseX), toBrowserY(mouseY),
                scrollX, scrollY, currentGlfwModifiers());
        return true;
    }

    private int currentGlfwModifiers() {
        long handle = minecraft.getWindow().handle();
        int mods = 0;
        if (keyHeld(handle, GLFW.GLFW_KEY_LEFT_SHIFT) || keyHeld(handle, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            mods |= GLFW.GLFW_MOD_SHIFT;
        }
        if (keyHeld(handle, GLFW.GLFW_KEY_LEFT_CONTROL) || keyHeld(handle, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            mods |= GLFW.GLFW_MOD_CONTROL;
        }
        if (keyHeld(handle, GLFW.GLFW_KEY_LEFT_ALT) || keyHeld(handle, GLFW.GLFW_KEY_RIGHT_ALT)) {
            mods |= GLFW.GLFW_MOD_ALT;
        }
        return mods;
    }

    private static boolean keyHeld(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    private boolean closesBrowser(KeyEvent event) {
        return CybercoreClientClient.matchesBrowserKey(event) && !BrowserTextInputTracker.isTextInputFocused();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (closesBrowser(event)) {
            swallowNextChar = true;
            onClose();
            return true;
        }
        swallowNextChar = false;
        forwardedBrowserKey = CybercoreClientClient.matchesBrowserKey(event);
        // Escape is normally the page's call (see BrowserEscapeBridge); on the blank fallback page
        // there is no app to ask.
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && BrowserLoadGuard.isParked(browser)) {
            onClose();
            return true;
        }
        // Not forwarded: ours also retries the real site when parked on the blank fallback page.
        // Plain F5 is an ordinary reload, so only changed chunks are fetched; ctrl+F5 wipes the
        // browser-side cache first.
        if (event.key() == GLFW.GLFW_KEY_F5) {
            if ((event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
                CybercoreClientClient.hardReloadBrowser();
            } else {
                CybercoreClientClient.reloadBrowser();
            }
            return true;
        }
        browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
        sendEnterAsCharacter(event);
        return true;
    }

    /**
     * Chromium inserts a line break on the character event, and GLFW raises no character callback
     * for Enter - so Minecraft never calls {@code charTyped} for it and CEF only saw the key-down.
     * Single-line inputs drop the carriage return themselves, so this needs no multiline check.
     */
    private void sendEnterAsCharacter(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            browser.sendKeyTyped('\r', event.modifiers());
        }
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (CybercoreClientClient.matchesBrowserKey(event) && !forwardedBrowserKey) {
            return true;
        }
        forwardedBrowserKey = false;
        browser.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (swallowNextChar) {
            swallowNextChar = false;
            return true;
        }
        int cp = event.codepoint();
        if (cp < Character.MIN_SUPPLEMENTARY_CODE_POINT) {
            browser.sendKeyTyped((char) cp, 0);
        }
        return true;
    }

    /**
     * Teardown, not in {@code onClose()}: that only fires when a screen closes itself, while dying,
     * being kicked or a server-opened container all replace the screen through {@code setScreen},
     * which calls this and nothing else.
     */
    @Override
    public void removed() {
        browser.setFocus(false);
        CybercoreClientClient.deactivatePlatform();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
