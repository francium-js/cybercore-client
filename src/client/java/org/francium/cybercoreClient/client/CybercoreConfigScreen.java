package org.francium.cybercoreClient.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class CybercoreConfigScreen extends Screen {

    private final Screen parent;
    private EditBox baseUrlBox;

    public CybercoreConfigScreen(Screen parent) {
        super(Component.translatable("gui.cybercore.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.baseUrlBox = new EditBox(
                this.font, centerX - 100, centerY - 40, 200, 20,
                Component.translatable("gui.cybercore.config.base_url")
        );
        this.baseUrlBox.setMaxLength(256);
        this.baseUrlBox.setValue(CybercoreConfig.getBaseUrl());
        this.addRenderableWidget(this.baseUrlBox);

        this.addRenderableWidget(
                Button.builder(
                                Component.translatable("gui.cybercore.config.update_cache"),
                                button -> CybercoreClientClient.hardReloadBrowser()
                        )
                        .bounds(centerX - 100, centerY - 12, 200, 20)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                        .bounds(centerX - 100, centerY + 12, 200, 20)
                        .build()
        );
    }

    @Override
    public void onClose() {
        saveBaseUrl();
        this.minecraft.setScreen(this.parent);
    }

    private void saveBaseUrl() {
        String value = this.baseUrlBox.getValue().trim();
        if (!value.isEmpty() && !value.equals(CybercoreConfig.getBaseUrl())) {
            CybercoreConfig.setBaseUrl(value);
            CybercoreClientClient.reloadWithNewBaseUrl();
        }
    }
}
