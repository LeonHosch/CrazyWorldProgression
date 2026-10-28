package ekuzo.crazyworldprogression.collectibles;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Base of every collectible pet: follows its owner (walks when more than 3 blocks away, teleports beside them past 16
 * blocks or in another dimension), cannot be hurt or pushed, and never fights. {@link PetService} spawns it for the
 * owner's selected pet and removes it when they change pets or leave. A content mod extends it for its look; its entity
 * type should not save ({@code noSave()}).
 */
public abstract class CwpPet extends PathfinderMob {
    private UUID owner;

    protected CwpPet(EntityType<? extends CwpPet> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createPetAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 10.0).add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    void setOwner(UUID owner) { this.owner = owner; }

    public UUID owner() { return owner; }

    ServerPlayer ownerPlayer() {
        return owner == null || !(level() instanceof ServerLevel level) ? null : level.getServer().getPlayerList().getPlayer(owner);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new FollowOwnerGoal());
        goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }

    @Override
    public boolean isPushable() { return false; }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) { }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    /** Walks after the owner, and teleports beside them when far behind. */
    private final class FollowOwnerGoal extends Goal {
        FollowOwnerGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override
        public boolean canUse() {
            ServerPlayer player = ownerPlayer();
            return player != null && player.level() == level() && distanceToSqr(player) > 9;
        }

        @Override
        public boolean canContinueToUse() { return canUse() && !getNavigation().isDone(); }

        @Override
        public void tick() {
            ServerPlayer player = ownerPlayer();
            if (player == null) return;
            getLookControl().setLookAt(player, 10.0F, getMaxHeadXRot());
            if (distanceToSqr(player) > 256) PetService.placeBeside(CwpPet.this, player);
            else if (tickCount % 10 == 0) getNavigation().moveTo(player, distanceToSqr(player) > 49 ? 1.6 : 1.1);
        }
    }
}
