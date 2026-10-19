package ekuzo.crazyworldprogression.portals;

import net.minecraft.world.phys.Vec3;

/**
 * Rules for downed raid players: where their revive point goes, how far they may fly from it as spectators,
 * and when they may revive themselves.
 */
final class RaidDowns {
    /** Spectators stay inside a sphere of this radius around their revive point. */
    static final double LEASH_RADIUS = 10.0;
    /** A downed player may self-revive after this many ticks (15 seconds), where self-revive is allowed. */
    static final long SELF_REVIVE_DELAY_TICKS = 300L;
    /** Player Revive mode: a downed player is sent out after this many ticks (1 minute) without a revive. */
    static final long PLAYER_REVIVE_WINDOW_TICKS = 1_200L;
    /** Reviving someone takes this many ticks (2 seconds) of staying at their marker. */
    static final long REVIVE_DURATION_TICKS = 40L;
    /** Flying within this distance of the revive point counts as touching it. */
    static final double REVIVE_TOUCH_DISTANCE = 1.0;
    /** Dying again this soon after a revive moves the revive point to the checkpoint spawn. */
    static final long QUICK_DEATH_TICKS = 100L;
    /** The same, for falling out of the world or drowning. */
    static final long QUICK_HAZARD_DEATH_TICKS = 400L;

    private RaidDowns() { }

    /**
     * Repeat deaths go to the checkpoint spawn: within 5 seconds of a revive for any cause, or within 20 seconds
     * when the new death is from the void or drowning. ticksSinceRevive is negative when the player was never revived.
     */
    static boolean useCheckpoint(long ticksSinceRevive, boolean voidOrDrowning) {
        if (ticksSinceRevive < 0) return false;
        return ticksSinceRevive <= QUICK_DEATH_TICKS || voidOrDrowning && ticksSinceRevive <= QUICK_HAZARD_DEATH_TICKS;
    }

    /** Self-revive needs: no active encounter, 15 seconds down, and touching the revive point. */
    static boolean canSelfRevive(boolean inEncounter, long ticksDown, double distanceToPoint) {
        return !inEncounter && ticksDown >= SELF_REVIVE_DELAY_TICKS && distanceToPoint <= REVIVE_TOUCH_DISTANCE;
    }

    /** The position clamped onto the leash sphere, or the position itself when it is inside. */
    static Vec3 leash(Vec3 point, Vec3 position, double radius) {
        Vec3 offset = position.subtract(point);
        double distance = offset.length();
        if (distance <= radius || distance == 0.0) return position;
        return point.add(offset.scale(radius / distance));
    }
}
