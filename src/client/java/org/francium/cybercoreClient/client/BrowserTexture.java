package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.ccbluex.liquidbounce.mcef.cef.MCEFRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the Identifier to blit for a browser's current frame.
 *
 * <p>MCEF registers only its software texture with the TextureManager; accelerated frames live in a
 * separate one it never registers, and are meant to be consumed as a raw view/sampler pair. Wrapping
 * that pair in an {@link AbstractTexture} of our own keeps the normal Identifier blit - and with it
 * the choice of render pipeline, which the premultiplied-alpha overlay needs. One wrapper per
 * browser, keyed off the renderer's own unique identifier, since the mod runs two browsers at once.
 */
final class BrowserTexture extends AbstractTexture {

    private static final Map<MCEFRenderer, BrowserTexture> INSTANCES = new HashMap<>();

    private final Identifier identifier;

    private BrowserTexture(Identifier identifier) {
        this.identifier = identifier;
    }

    /** Null while nothing has been painted yet. */
    static Identifier resolve(MCEFBrowser browser) {
        if (!browser.isTextureReady()) {
            return null;
        }

        MCEFRenderer renderer = browser.getRenderer();
        if (!renderer.isAccelerated()) {
            // The unpainted flag is only ever cleared by the software paint path (checked against
            // MCEF 3.3.0 bytecode), so it must not gate accelerated frames - there it would stay
            // true forever and blank the browser.
            return renderer.isUnpainted() ? null : browser.getTextureLocation();
        }

        BrowserTexture instance = INSTANCES.computeIfAbsent(renderer, r -> {
            Identifier id = Identifier.fromNamespaceAndPath("cybercore-client",
                    "accelerated_" + r.getIdentifier().getPath());
            BrowserTexture created = new BrowserTexture(id);
            Minecraft.getInstance().getTextureManager().register(id, created);
            return created;
        });

        // Re-read every frame: MCEF may swap the texture underneath us at any paint.
        instance.texture = renderer.getTexture();
        instance.textureView = renderer.getTextureView();
        instance.sampler = renderer.getSampler();

        return instance.textureView == null ? null : instance.identifier;
    }

    /** Drops the registrations and the references they hold into MCEF's renderers. */
    static void release() {
        for (BrowserTexture instance : INSTANCES.values()) {
            Minecraft.getInstance().getTextureManager().release(instance.identifier);
            instance.texture = null;
            instance.textureView = null;
            instance.sampler = null;
        }
        INSTANCES.clear();
    }

    /** All three objects belong to MCEF; closing them here would double free. */
    @Override
    public void close() {
    }
}
