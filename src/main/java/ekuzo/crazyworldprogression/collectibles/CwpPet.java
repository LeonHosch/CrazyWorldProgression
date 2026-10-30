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
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Base of every collectible pet: follows its owner (walks when more than 3 blocks away, teleports beside them past 16
 * blocks or in another dimension), cannot be hurt, pushed, bumped into or targeted, makes no sounds, and never fights.
 * {@link PetService} spawns it for the owner's selected pet and removes it when they change pets or leave. A content mod
 * extends it for its look; its entity type should not save ({@code noSave()}).
 */
public abstract class CwpPet extends PathfinderMob {
    private UUID owner;

    protected CwpPet(EntityType<? extends CwpPet> type, Level level) {
        super(type, level);
        setSilent(true);
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

    /** True for pets that fly after their owner instead of walking (see {@link #tick}). */
    protected boolean flies() { return false; }

    /** Flying pets glide to a spot beside and a little behind their owner, at about head height. */
    @Override
    public void tick() {
        super.tick();
        if (!flies() || level().isClientSide()) return;
        setNoGravity(true);
        ServerPlayer player = ownerPlayer();
        if (player == null || player.level() != level()) return;
        Vec3 target = player.position().add(Vec3.directionFromRotation(0, player.getYRot() + 130).scale(1.4)).add(0, 1.1, 0);
        Vec3 way = target.subtract(position());
        double distance = way.length();
        Vec3 velocity = distance < 0.25 ? getDeltaMovement().scale(0.5) : way.scale(Math.min(0.9, distance * 0.2) / distance);
        setDeltaMovement(velocity);
        if (velocity.horizontalDistanceSqr() > 0.0009) {
            float yaw = (float) (Mth.atan2(velocity.z, velocity.x) * Mth.RAD_TO_DEG) - 90;
            setYRot(yaw);
            setYBodyRot(yaw);
        }
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

    /** Nothing bumps into it or can hit or aim at it, and mobs never pick it as a target. */
    @Override
    public boolean canBeCollidedWith(net.minecraft.world.entity.Entity entity) { return false; }

    @Override
    public boolean isPickable() { return false; }

    @Override
    public boolean isAttackable() { return false; }

    @Override
    public boolean canBeSeenAsEnemy() { return false; }

    /** Walks after the owner, and teleports beside them when far behind. */
    private final class FollowOwnerGoal extends Goal {
        /** Ticks until the path to the owner is worked out again. */
        private int repath;

        FollowOwnerGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override
        public boolean canUse() {
            ServerPlayer player = ownerPlayer();
            return player != null && player.level() == level() && distanceToSqr(player) > 9;
        }

        /** Keeps following until close again (about 2 blocks), not only while a path is running. */
        @Override
        public boolean canContinueToUse() {
            ServerPlayer player = ownerPlayer();
            return player != null && player.level() == level() && distanceToSqr(player) > 4;
        }

        @Override
        public void start() { repath = 0; }

        @Override
        public void stop() { getNavigation().stop(); }

        @Override
        public void tick() {
            ServerPlayer player = ownerPlayer();
            if (player == null) return;
            getLookControl().setLookAt(player, 10.0F, getMaxHeadXRot());
            if (distanceToSqr(player) > 256) {
                PetService.placeBeside(CwpPet.this, player);
                return;
            }
            // Its own countdown: the pet's age would only line up with this goal's ticks every now and then, so
            // some pets never got a path and only teleported.
            if (!flies() && --repath <= 0) {
                repath = 10;
                getNavigation().moveTo(player, distanceToSqr(player) > 49 ? 1.6 : 1.1);
            }
        }
    }
}
