package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

public final class PlayerPositionBridge {

    private static final double POSITION_EPSILON = 0.001;
    private static final float YAW_EPSILON = 0.01f;

    private static double lastX = Double.NaN;
    private static double lastZ = Double.NaN;
    private static float lastYaw = Float.NaN;

    private PlayerPositionBridge() {
    }

    static void register() {
        LevelRenderEvents.END_EXTRACTION.register(context -> {
            if (!CybercoreServer.isConnected()) return;

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return;

            float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(true);
            Vec3 position = player.getPosition(partialTick);

            push(position.x, position.z, player.getViewYRot(partialTick));
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    private static void push(double x, double z, float yaw) {
        double roundedX = round(x, 1000d);
        double roundedZ = round(z, 1000d);
        float roundedYaw = (float) round(yaw, 100d);

        if (Math.abs(roundedX - lastX) < POSITION_EPSILON
                && Math.abs(roundedZ - lastZ) < POSITION_EPSILON
                && Math.abs(roundedYaw - lastYaw) < YAW_EPSILON) {
            return;
        }

        if (CybercoreClientClient.uiBrowser == null) {
            return;
        }

        lastX = roundedX;
        lastZ = roundedZ;
        lastYaw = roundedYaw;

        send("if(window.__ccPlayerPosition)window.__ccPlayerPosition("
                + roundedX + "," + roundedZ + "," + roundedYaw + ");");
    }

    private static void send(String script) {
        MCEFBrowser browser = CybercoreClientClient.uiBrowser;
        if (browser != null) {
            browser.executeJavaScript(script, browser.getURL(), 0);
        }
    }

    private static void clear() {
        lastX = Double.NaN;
        lastZ = Double.NaN;
        lastYaw = Float.NaN;

        send("if(window.__ccPlayerPositionClear)window.__ccPlayerPositionClear();");
    }

    private static double round(double value, double scale) {
        return Math.round(value * scale) / scale;
    }
}
