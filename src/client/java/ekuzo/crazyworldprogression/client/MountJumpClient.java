package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.collectibles.CwpMount;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Jumping on a collectible mount: the mount jumps the moment jump is pressed (no horse jump bar), as high as its jump
 * strength allows while jump is held; letting go while it still rises cuts the jump short. The rider's game moves a
 * ridden mount, so this happens here.
 */
final class MountJumpClient {
    /** Upward speed kept when jump is let go during the rise. */
    private static final double SHORT_JUMP = 0.25;
    /** Forward push of a jump while walking forward, like a horse's leap. */
    private static final double LEAP = 0.3;
    private static boolean wasDown;
    /** True from the jump until jump is let go or the mount starts falling. */
    private static boolean rising;
    /** Ticks since the jump (the mount may still touch the ground for the first one or two). */
    private static int riseTicks;

    private MountJumpClient() { }

    static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(MountJumpClient::tick);
    }

    private static void tick(Minecraft client) {
        boolean down = client.options.keyJump.isDown();
        boolean pressed = down && !wasDown;
        wasDown = down;
        if (client.player == null || !(client.player.getVehicle() instanceof CwpMount mount)
                || mount.getControllingPassenger() != client.player) {
            rising = false;
            return;
        }
        Vec3 motion = mount.getDeltaMovement();
        if (pressed && mount.onGround() && client.gui.screen() == null) {
            double lift = mount.getAttributeValue(Attributes.JUMP_STRENGTH);
            double forward = client.player.zza > 0 ? LEAP : 0;
            double yaw = Math.toRadians(mount.getYRot());
            mount.setDeltaMovement(motion.x - Math.sin(yaw) * forward, lift, motion.z + Math.cos(yaw) * forward);
            rising = true;
            riseTicks = 0;
            return;
        }
        if (!rising) return;
        riseTicks++;
        if (riseTicks > 2 && (motion.y <= 0 || mount.onGround())) {
            rising = false;
        } else if (!down) {
            mount.setDeltaMovement(motion.x, motion.y * SHORT_JUMP, motion.z);
            rising = false;
        }
    }
}
