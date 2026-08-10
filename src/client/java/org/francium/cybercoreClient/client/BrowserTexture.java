package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.ccbluex.liquidbounce.mcef.cef.MCEFRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * Resolves the Identifier to blit for the browser's current frame.
 *
 * <p>MCEF registers only its software texture with the TextureManager; accelerated frames live in a
 * separate one it never registers, and are meant to be consumed as a raw view/sampler pair. Wrapping
 * that pair in an {@link AbstractTexture} of our own keeps the normal Identifier blit - and with it
 * the choice of render pipeline, which the premultiplied-alpha overlay needs.
 */
final class BrowserTexture extends AbstractTexture {

    private static final Identifier IDENTIFIER =
            Identifier.fromNamespaceAndPath("cybercore-client", "accelerated_browser");

    private static BrowserTexture instance;

    private BrowserTexture() {
    }

    /** Null while nothing has been painted yet. */
    static Identifier resolve(MCEFBrowser browser) {
        if (!browser.isTextureReady()) {
            return null;
        }

        MCEFRenderer renderer = browser.getRenderer();
        if (!renderer.isAccelerated()) {
            return browser.getTextureLocation();
        }

        if (instance == null) {
            instance = new BrowserTexture();
            Minecraft.getInstance().getTextureManager().register(IDENTIFIER, instance);
        }

        // Re-read every frame: MCEF may swap the texture underneath us at any paint.
        instance.texture = renderer.getTexture();
        instance.textureView = renderer.getTextureView();
        instance.sampler = renderer.getSampler();

        return instance.textureView == null ? null : IDENTIFIER;
    }

    /** Drops the registration and the references it holds into MCEF's renderer. */
    static void release() {
        if (instance == null) {
            return;
        }
        Minecraft.getInstance().getTextureManager().release(IDENTIFIER);
        instance.texture = null;
        instance.textureView = null;
        instance.sampler = null;
        instance = null;
    }

    /** All three objects belong to MCEF; closing them here would double free. */
    @Override
    public void close() {
    }
}
