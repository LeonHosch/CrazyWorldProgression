package ekuzo.crazyworldprogression.collectibles;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Base of every collectible mount: rides like a tamed, saddled horse (speed and jump strength come from its
 * {@link Collectibles.MountDefinition}), belongs to the player who summoned it, cannot be hurt, and has no inventory,
 * breeding or food. It disappears as soon as nobody rides it (see {@link MountService}). A content mod extends it for
 * its look (for example with a GeckoLib model). Its entity type must be saveable (players can only ride those), but the
 * owner is not saved, so a mount loaded from disk removes itself.
 */
public abstract class CwpMount extends AbstractHorse {
    private UUID owner;
    private int unridden;

    protected CwpMount(EntityType<? extends CwpMount> type, Level level) {
        super(type, level);
        setTamed(true);
    }

    public static AttributeSupplier.Builder createMountAttributes() {
        return createBaseHorseAttributes().add(Attributes.MAX_HEALTH, 30.0).add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.JUMP_STRENGTH, 0.7).add(Attributes.STEP_HEIGHT, 1.0);
    }

    /** Sets the owner and the definition's stats. */
    void setUp(UUID owner, Collectibles.MountDefinition definition) {
        this.owner = owner;
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(definition.speed());
        getAttribute(Attributes.JUMP_STRENGTH).setBaseValue(definition.jumpStrength());
    }

    public UUID owner() { return owner; }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override
    public boolean isSaddled() { return true; }

    /**
     * No horse jump bar to charge: the rider's game jumps the mount as soon as jump is pressed, and letting go early
     * makes the jump lower (MountJumpClient).
     */
    @Override
    public boolean canJump() { return false; }

    @Override
    public boolean isFood(ItemStack stack) { return false; }

    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) { return null; }

    @Override
    public void openCustomInventoryScreen(Player player) { }

    /** Only its owner can get back on, by right-clicking it. */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player.getUUID().equals(owner) && !isVehicle()) {
            if (!level().isClientSide()) player.startRiding(this);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }

    @Override
    public boolean isPushable() { return false; }

    /** It cannot be hurt, so the rider keeps seeing their own food bar instead of its hearts. */
    @Override
    public boolean showVehicleHealth() { return false; }

    /** Gone a moment after nobody rides it any more. */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        unridden = isVehicle() ? 0 : unridden + 1;
        if (owner == null || unridden > 20) discard();
    }
}
