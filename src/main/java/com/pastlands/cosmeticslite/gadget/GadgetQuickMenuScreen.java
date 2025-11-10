package com.pastlands.cosmeticslite.gadget;

import com.pastlands.cosmeticslite.CosmeticDef;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Simple mouse-grabbing gadget picker.
 * - Opened by /glist or /gadget list.
 * - On click: equips → uses → closes immediately; auto-reopens ~3s later (driven by GadgetClientCommands).
 * - Rows show LIVE cooldown text (mm:ss) using GadgetClientCommands.remainingMs().
 */
public class GadgetQuickMenuScreen extends Screen {

    /** id -> (pretty name, short desc). Provided by GadgetClientCommands.prettyMap(). */
    private final Map<String, GadgetClientCommands.Pretty> prettyMap;
    /** Kept for signature compatibility; reopen timing is driven by GadgetClientCommands. */
    private final boolean reopenAfterUse;

    private int left, top, widthPx, heightPx;
    private Button closeBtn;
    private final List<Row> rows = new ArrayList<>();

    private static final int PAD = 10;
    private static final int ROW_H = 24;
    private static final int TITLE_H = 18;

    private static final int COL_BG   = 0xCC2B2F36;
    private static final int COL_EDGE = 0xFF111417;
    private static final int COL_TEXT = 0xFFE6E6E6;
    private static final int COL_DESC = 0xFFB8C0C8;
    private static final int COL_ROW  = 0x33222A33;
    private static final int COL_HIL  = 0x553B6EF5;
    private static final int COL_READY    = 0xFFD8F1FF;
    private static final int COL_DISABLED = 0xFFA0A8B0;
    private static final int COL_COOLDOWN = 0xFF9FC178;

    public GadgetQuickMenuScreen(Map<String, GadgetClientCommands.Pretty> prettyMap, boolean reopenAfterUse) {
        super(Component.literal("Gadgets"));
        this.prettyMap = prettyMap;
        this.reopenAfterUse = reopenAfterUse;
    }

    private record Row(ResourceLocation id, String name, String desc, int y) {}

    @Override
    protected void init() {
        super.init();

        // size & anchor
        this.widthPx  = 360;
        this.heightPx = PAD + TITLE_H + 1 + prettyMap.size() * ROW_H + PAD;
        this.left = (this.width - this.widthPx) / 2;
        this.top  = (this.height - this.heightPx) / 2;

        // Close button
        int x = left + widthPx - PAD - 12;
        int y = top + PAD - 4;
        closeBtn = Button.builder(Component.literal("X"), b -> onClose())
                .pos(x, y).size(12, 12).build();
        addRenderableWidget(closeBtn);

        // Build rows
        rows.clear();
        int rowY = top + PAD + TITLE_H + 1;
        for (Map.Entry<String, GadgetClientCommands.Pretty> e : prettyMap.entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            if (id == null) continue;
            GadgetClientCommands.Pretty p = e.getValue();
            rows.add(new Row(id, p.name(), p.desc(), rowY));
            rowY += ROW_H;
        }

        // grab the mouse so drag-to-look doesn't move while menu is open
        if (this.minecraft != null) this.minecraft.mouseHandler.releaseMouse();
    }

    @Override
    public void tick() {
        super.tick();
        // nothing special here; labels re-render each frame from remainingMs()
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        // backdrop
        g.fill(left, top, left + widthPx, top + heightPx, COL_BG);
        g.fill(left, top, left + widthPx, top + 1, COL_EDGE);
        g.fill(left, top + heightPx - 1, left + widthPx, top + heightPx, COL_EDGE);
        g.fill(left, top, left + 1, top + heightPx, COL_EDGE);
        g.fill(left + widthPx - 1, top, left + widthPx, top + heightPx, COL_EDGE);

        // title
        g.drawString(this.font, "Gadgets", left + PAD, top + PAD - 2, COL_TEXT, false);
        g.fill(left + PAD, top + PAD + TITLE_H, left + widthPx - PAD, top + PAD + TITLE_H + 1, 0x44FFFFFF);

        // rows
        int nameX = left + PAD + 6;
        int rightPadX = left + widthPx - PAD - 6;

        for (Row r : rows) {
            boolean over = mouseX >= left + PAD && mouseX <= left + widthPx - PAD
                    && mouseY >= r.y && mouseY <= r.y + ROW_H;

            long remMs = GadgetClientCommands.remainingMs(r.id());
            boolean onCooldown = remMs > 0;

            g.fill(left + PAD, r.y, left + widthPx - PAD, r.y + ROW_H, over ? COL_HIL : COL_ROW);

            // name & description (muted if disabled)
            int nameCol = onCooldown ? COL_DISABLED : COL_TEXT;
            int descCol = onCooldown ? (COL_DISABLED & 0xCCFFFFFF) : COL_DESC;
            g.drawString(this.font, r.name(), nameX, r.y + 6, nameCol, false);
            g.drawString(this.font, r.desc(), nameX, r.y + 6 + 10, descCol, false);

            // RIGHT LABEL: countdown or "Click to use"
            String label;
            int color;
            if (onCooldown) {
                label = GadgetClientCommands.prettyClock(remMs);
                color = COL_COOLDOWN;
            } else {
                label = "Click to use";
                color = over ? 0xA2C3FF : COL_READY;
            }
            int lw = this.font.width(label);
            g.drawString(this.font, label, rightPadX - lw, r.y + 6, color, false);
        }

        super.render(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // normal buttons first
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        // find row
        for (Row r : rows) {
            if (mouseX >= left + PAD && mouseX <= left + widthPx - PAD
                    && mouseY >= r.y && mouseY <= r.y + ROW_H) {

                long rem = GadgetClientCommands.remainingMs(r.id());
                if (rem > 0L) {
                    // Soft deny sound when on cooldown; keep menu open
                    if (minecraft != null && minecraft.player != null) {
                        minecraft.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.3f, 0.6f);
                    }
                    return true;
                }

                // Equip locally before send (mirrors server-authoritative equip)
                GadgetClientCommands.ensureEquippedBeforeUse(r.id());

                if (tryUse(r.id())) {
                    // start local cooldown immediately
                    GadgetClientCommands.noteJustUsed(r.id());
                    // vanish menu now and arm a ~3s reopen (handled by GadgetClientCommands' client tick)
                    GadgetClientCommands.armReopenAfterUse(r.id(), true);
                    onClose();
                    return true;
                }
                return true;
            }
        }
        return false;
    }

    private boolean tryUse(ResourceLocation id) {
        Minecraft mc = minecraft;
        if (mc == null || mc.player == null || id == null) return false;

        // Validate it’s a gadget
        CosmeticDef def = CosmeticsRegistry.get(id);
        if (def == null || !"gadgets".equals(def.type())) return false;

        // Fire the gadget through your existing network channel
        var ch = GadgetNet.channel();
        if (ch == null) { GadgetNet.init(); ch = GadgetNet.channel(); }
        if (ch == null) return false;

        ch.sendToServer(new GadgetNet.UseGadgetC2S(id));
        return true;
    }

    @Override
    public void onClose() {
        super.onClose();
        if (this.minecraft != null) this.minecraft.mouseHandler.grabMouse();
    }
}
