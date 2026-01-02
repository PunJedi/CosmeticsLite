package com.pastlands.cosmeticslite.entity;

import com.pastlands.cosmeticslite.entity.goal.PetFollowOwnerGoal;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Cosmetic Snow Golem pet.
 *
 * Vanilla-like behavior:
 *  - Walks, animates
 *  - Invulnerable (takes no damage, never dies)
 *  - Cannot be sheared (pumpkin stays on)
 *  - Does NOT leave snow trails (snow placement disabled)
 *  - Does NOT throw snowballs (attack behavior disabled for multiplayer safety)
 *
 * Forge 47.4.0 / MC 1.20.1
 */
public class CosmeticPetSnowGolem extends SnowGolem {

    public CosmeticPetSnowGolem(EntityType<? extends SnowGolem> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        // Clear all existing goals (including ranged attack that throws snowballs)
        this.goalSelector.removeAllGoals(goal -> true);
        this.targetSelector.removeAllGoals(goal -> true);

        // Add cosmetic-safe goals with follow behavior
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new PetFollowOwnerGoal(this)); // Follow owner (speed ~1.1-1.2)
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.8D)); // Lower priority, slower speed
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));

        // No attack or target goals - cosmetic pets don't fight
    }

    /**
     * Prevent setting any target - Snow Golem should never attack.
     */
    @Override
    public void setTarget(@Nullable LivingEntity target) {
        // Ignore all target setting attempts
    }

    /**
     * Prevent attacking any living entity.
     */
    @Override
    public boolean canAttack(LivingEntity target) {
        return false;
    }

    /**
     * aiStep() is called normally - snow placement prevention happens via EntityMobGriefingEvent.
     * 
     * The PetMobGriefingInterceptor event handler listens for EntityMobGriefingEvent and
     * sets the result to DENY for cosmetic Snow Golems. This makes ForgeHooks.canEntityGrief()
     * return false, which prevents SnowGolem.aiStep() from placing snow layers.
     * 
     * This is the proper Forge way - it prevents the griefing action at the source,
     * so no placement packets are sent and there's no flicker.
     */
    // No override needed - super.aiStep() will check mob griefing, and our event handler denies it

    /**
     * Hard safety layer: prevent snowball throwing even if a ranged goal gets re-added.
     * This ensures no snowballs are thrown, making it safe for multiplayer servers.
     */
    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        // No-op: do nothing, don't throw snowballs
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
