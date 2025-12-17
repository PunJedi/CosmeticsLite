package com.pastlands.cosmeticslite;

import com.pastlands.cosmeticslite.CosmeticsRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.stream.Collectors;

public class CosmeticsCategoryScreen extends Screen {

    public CosmeticsCategoryScreen() {
        super(Component.literal("Cosmetic Categories"));
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        List<String> types = CosmeticsRegistry.all().stream()
            .map(def -> def.type().toLowerCase())
            .distinct()
            .collect(Collectors.toList());

        int startX = this.width / 2 - 60;
        int startY = 40;
        int spacing = 25;

        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            int x = startX;
            int y = startY + i * spacing;

            this.addRenderableWidget(
                Button.builder(Component.literal(capitalize(type)), btn -> {
                    mc.setScreen(new CosmeticsChestScreen());
                }).bounds(x, y, 120, 20).build()
            );
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);
        gfx.drawCenteredString(this.font, "Select a Category", this.width / 2, 15, 0xFFFFFF);
    }

    private String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
