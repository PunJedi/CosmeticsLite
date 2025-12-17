package com.pastlands.cosmeticslite.preview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MannequinPane
 * Renders the local player as a "mannequin" inside a rectangular well.
 *
 * - Drag-to-rotate support (internal state) OR set yaw directly.
 * - Optional PreviewScope begin/end hook to isolate mannequin-only overrides.
 * - Optional overrides supplier -> Map<String, ResourceLocation> for layers.
 *
 * This file is standalone; wiring to your existing PreviewResolver will happen
 * in CosmeticsChestScreen by passing a PreviewScope + overrides supplier.
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
public final class MannequinPane {

    // ---- Bounds ----
    private int l, t, r, b;

    // ---- Mannequin ----
    private LocalPlayer mannequin;

    // ---- Drag state (you can ignore these and drive yaw directly if you prefer) ----
    private boolean dragging = false;
    private double lastDragX = 0.0;
    private float  yawAccumDeg = -30.0f; // slight turn so capes/shoulders show nicely
    private static final float DRAG_SENS = 1.8f;

    // ---- External hooks ----
    private PreviewScope previewScope = PreviewScope.NOOP;
    private Supplier<Map<String, ResourceLocation>> overridesSupplier = () -> Collections.emptyMap();

    public MannequinPane() {}

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /** Set the preview rectangle bounds. */
    public void setBounds(int left, int top, int right, int bottom) {
        this.l = left;
        this.t = top;
        this.r = right;
        this.b = bottom;
    }

    /** Inject a scope that will wrap mannequin rendering (begin/end). */
    public void setPreviewScope(PreviewScope scope) {
        this.previewScope = (scope != null) ? scope : PreviewScope.NOOP;
    }

    /** Provide cosmetic overrides for the mannequin render (called each frame). */
    public void setOverridesSupplier(Supplier<Map<String, ResourceLocation>> supplier) {
        this.overridesSupplier = (supplier != null) ? supplier : () -> Collections.emptyMap();
    }

    /** Directly set yaw, if you prefer to drive rotation from outside. */
    public void setYawDegrees(float yawDeg) {
        this.yawAccumDeg = yawDeg;
    }

    /** Current yaw (degrees). */
    public float getYawDegrees() {
        return yawAccumDeg;
    }

    // -------------------------------------------------------------------------
    // Input (forward mouse events to the pane if you want built-in dragging)
    // -------------------------------------------------------------------------

    /** Returns true if a press inside the pane started a drag. */
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (!isInside(mouseX, mouseY)) return false;
        dragging = true;
        lastDragX = mouseX;
        return true;
    }

    /** Returns true if a drag event was consumed. */
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dX, double dY) {
        if (button != 0 || !dragging) return false;
        double deltaX = mouseX - lastDragX;
        lastDragX = mouseX;
        yawAccumDeg += (float)(deltaX * DRAG_SENS);
        if (yawAccumDeg > 400f) yawAccumDeg = 400f;
        if (yawAccumDeg < -400f) yawAccumDeg = -400f;
        return true;
    }

    /** Returns true if a release ended a drag. */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (!dragging) return false;
        dragging = false;
        return true;
    }

    // -------------------------------------------------------------------------
    // Lifetime
    // -------------------------------------------------------------------------

    /** Called each tick to ensure mannequin is valid (idempotent). */
    public void tick() {
        ensureMannequin();
    }

    /** Render the mannequin centered in the pane. */
    public void render(GuiGraphics g) {
        ensureMannequin();
        if (mannequin == null) return;

        // Layout: center X, align feet ~6px above bottom like your current screen
        final int centerX = (l + r) / 2;
        final int baseY   = b - 6;

        // Scale tuned for ~140px well = ~42px scale; clamp for sanity.
        final int wellH   = Math.max(0, b - t);
        final int scale   = clamp((int)(wellH * 0.31f), 32, 64);

        // Supply overrides and enter preview scope (no-op if not provided).
        Map<String, ResourceLocation> overrides = safeOverrides();
        previewScope.begin(mannequin, overrides);

        // Full, non-mirrored rotation driven by yawAccumDeg; keep head with body.
        final float yawDeg   = -yawAccumDeg;
        final float pitchDeg = 0.0F;

        Quaternionf poseQ   = new Quaternionf().rotationZ((float)Math.PI); // face camera
        Quaternionf cameraQ = new Quaternionf().rotationX(0.0F);

        // Save/patch/restore
        float prevBody  = mannequin.yBodyRot;
        float prevBodyO = mannequin.yBodyRotO;
        float prevYaw   = mannequin.getYRot();
        float prevXRot  = mannequin.getXRot();
        float prevHead  = mannequin.yHeadRot;
        float prevHeadO = mannequin.yHeadRotO;

        mannequin.yBodyRot  = 180.0F + yawDeg;
        mannequin.yBodyRotO = mannequin.yBodyRot;
        mannequin.setYRot(180.0F + yawDeg);
        mannequin.setXRot(pitchDeg);
        mannequin.yHeadRot  = mannequin.getYRot();
        mannequin.yHeadRotO = mannequin.getYRot();

        InventoryScreen.renderEntityInInventory(g, centerX, baseY, scale, poseQ, cameraQ, mannequin);

        mannequin.yBodyRot  = prevBody;
        mannequin.yBodyRotO = prevBodyO;
        mannequin.setYRot(prevYaw);
        mannequin.setXRot(prevXRot);
        mannequin.yHeadRot  = prevHead;
        mannequin.yHeadRotO = prevHeadO;

        previewScope.end();
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void ensureMannequin() {
        if (mannequin != null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (!(mc.level instanceof ClientLevel)) return;

        LocalPlayer me = mc.player;
        if (me == null) return;

        mannequin = me; // Use the real local player for correct skin/model/cape.
    }

    private boolean isInside(double x, double y) {
        return x >= l && x <= r && y >= t && y <= b;
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

    private Map<String, ResourceLocation> safeOverrides() {
        try {
            Map<String, ResourceLocation> m = (overridesSupplier != null) ? overridesSupplier.get() : null;
            return (m != null) ? m : Collections.emptyMap();
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }

    // -------------------------------------------------------------------------
    // Hook interface (no dependency on your existing PreviewResolver)
    // -------------------------------------------------------------------------

    public interface PreviewScope {
        void begin(LocalPlayer mannequin, Map<String, ResourceLocation> overrides);
        void end();

        PreviewScope NOOP = new PreviewScope() {
            @Override public void begin(LocalPlayer mannequin, Map<String, ResourceLocation> overrides) {}
            @Override public void end() {}
        };
    }
}
