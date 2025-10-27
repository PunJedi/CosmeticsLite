package com.pastlands.cosmeticslite.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * Cosmetic Snow Golem pet.
 *
 * Vanilla-like:
 *  - Walks, animates, throws snowballs, leaves snow trails
 *  - Invulnerable (takes no damage, never dies)
 *  - Cannot be sheared (pumpkin stays on)
 *
 * Forge 47.4.0 / MC 1.20.1
 */
public class CosmeticPetSnowGolem extends SnowGolem {

    public CosmeticPetSnowGolem(EntityType<? extends SnowGolem> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false; // No damage
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true; // Fully invulnerable
    }

    @Override
    public boolean isInvulnerable() {
        return true;
    }

    @Override
    public void kill() {
        // Prevent force-kill
    }

    @Override
    public boolean isDeadOrDying() {
        return false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.is(Items.SHEARS)) {
            return InteractionResult.FAIL; // Block pumpkin removal
        }
        return super.mobInteract(player, hand);
    }
}
