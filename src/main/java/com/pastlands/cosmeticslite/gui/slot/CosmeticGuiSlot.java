package com.pastlands.cosmeticslite.gui.slot;

import com.pastlands.cosmeticslite.CosmeticDef;
import net.minecraft.world.item.Items;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws one cosmetic slot:
 * - Subtle plate background
 * - Item icon for real cosmetics (resolved from CosmeticDef.icon)
 * - Tooltip (name/id for real entries)
 */
public class CosmeticGuiSlot {

    private final CosmeticSlot slot;

    private static final int VIS_W = 18;
    private static final int VIS_H = 16;

    private static final int COL_PLATE_RIM  = 0xFF2A2A2A;
    private static final int COL_PLATE_MID  = 0xFF0F0F0F;
    private static final int COL_PLATE_FILL = 0xFF1A1A1A;

    public CosmeticGuiSlot(CosmeticSlot slot) {
        this.slot = slot;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        int x = resolveInt(slot, "getX", "x");
        int y = resolveInt(slot, "getY", "y");

        // Base plate
        g.fill(x, y, x + VIS_W, y + VIS_H, COL_PLATE_RIM);
        g.fill(x + 1, y + 1, x + VIS_W - 1, y + VIS_H - 1, COL_PLATE_MID);
        g.fill(x + 2, y + 2, x + VIS_W - 2, y + VIS_H - 2, COL_PLATE_FILL);

        CosmeticDef def = resolveDef(slot);
        if (def != null) {
            ResourceLocation iconId = null;
            try { iconId = def.icon(); } catch (Throwable ignored) {}

            if (iconId != null) {
                ItemStack stack = stackFromIcon(iconId);

                // Capes get animated rainbow tint
                if ("capes".equalsIgnoreCase(def.type())) {
                    try {
                        stack = new ItemStack(Items.PAPER);
                        long time = System.currentTimeMillis();
                        int offset = Math.floorMod(def.id().toString().hashCode(), 5000);
                        float hue = ((time + offset) % 5000L) / 5000.0f;
                        int rgb = java.awt.Color.HSBtoRGB(hue, 1.0f, 1.0f);

                        int ix = x + (VIS_W - 16) / 2;
                        int iy = y + (VIS_H - 16) / 2;
                        int base = (0xAA << 24) | (rgb & 0xFFFFFF);
                        g.fill(ix - 2, iy - 2, ix + 18, iy + 18, base);
                        int gloss = (0x66 << 24) | 0xFFFFFF;
                        g.fill(ix - 2, iy - 2, ix + 9, iy + 9, gloss);
                    } catch (Exception ignored) {}
                }

                if (!stack.isEmpty()) {
                    int ix = x + (VIS_W - 16) / 2;
                    int iy = y + (VIS_H - 16) / 2;
                    renderIconTransparent(g, stack, ix, iy, isMouseOver(mouseX, mouseY, x, y), false);
                }
            }
        }

        // Tooltip
        if (isMouseOver(mouseX, mouseY, x, y)) {
            Font font = Minecraft.getInstance().font;
            if (def != null) {
                List<Component> lines = new ArrayList<>();
                String title = def.name() != null && !def.name().isEmpty()
                        ? def.name() : def.id().toString();
                lines.add(Component.literal(title));
                g.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
        }
    }

    private static boolean isMouseOver(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + VIS_W
            && mouseY >= y && mouseY < y + VIS_H;
    }

    private static ItemStack stackFromIcon(ResourceLocation iconId) {
        try {
            Item item = ForgeRegistries.ITEMS.getValue(iconId);
            if (item != null) return new ItemStack(item);
        } catch (Exception ignored) {}
        return ItemStack.EMPTY;
    }

    private static int resolveInt(Object obj, String getterName, String fallbackName) {
        try {
            Method m = obj.getClass().getMethod(getterName);
            Object v = m.invoke(obj);
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}

        try {
            Method m = obj.getClass().getMethod(fallbackName);
            Object v = m.invoke(obj);
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}

        try {
            Field f = obj.getClass().getDeclaredField(fallbackName);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}
        return 0;
    }

    private static CosmeticDef resolveDef(Object obj) {
        String[] methodNames = {"getDef", "def", "getDefinition", "getCosmetic", "getCosmeticDef"};
        for (String name : methodNames) {
            try {
                Method m = obj.getClass().getMethod(name);
                Object v = m.invoke(obj);
                if (v instanceof CosmeticDef cd) return cd;
            } catch (Throwable ignored) {}
        }

        String[] fieldNames = {"def", "definition", "cosmetic", "cosmeticDef"};
        for (String name : fieldNames) {
            try {
                Field f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof CosmeticDef cd) return cd;
            } catch (Throwable ignored) {}
        }
        return null;
    }
    
    /**
     * Render an item icon with full transparency (no white background).
     * Uses lightweight GUI item renderer with optional hover highlight.
     */
    private static void renderIconTransparent(GuiGraphics g, ItemStack stack, int x, int y, boolean isHovered, boolean isSelected) {
        if (stack.isEmpty()) return;
        
        com.mojang.blaze3d.vertex.PoseStack pose = g.pose();
        pose.pushPose();
        
        // Render item with transparency (no white background)
        // Use renderItem instead of renderFakeItem to avoid the baked background quad
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        
        g.renderItem(stack, x, y);
        g.renderItemDecorations(Minecraft.getInstance().font, stack, x, y);
        
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        
        // Optional: Hover highlight (subtle transparent overlay)
        if (isHovered) {
            g.fill(x, y, x + 16, y + 16, 0x40FFFFFF);
        }
        
        // Optional: Selection ring (thin 1-pixel border)
        if (isSelected) {
            g.fill(x - 1, y - 1, x + 17, y, 0xFFFFFFFF);
            g.fill(x - 1, y + 16, x + 17, y + 17, 0xFFFFFFFF);
            g.fill(x - 1, y, x, y + 16, 0xFFFFFFFF);
            g.fill(x + 16, y, x + 17, y + 16, 0xFFFFFFFF);
        }
        
        pose.popPose();
    }
}
