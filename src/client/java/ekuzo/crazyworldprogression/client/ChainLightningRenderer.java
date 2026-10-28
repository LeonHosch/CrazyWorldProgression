package ekuzo.crazyworldprogression.client;

import com.mojang.blaze3d.vertex.PoseStack;
import ekuzo.crazyworldprogression.progression.equipment.StaticCharge;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stormcaller chain lightning, drawn entirely on the client: jagged, flickering bolts linking the hitbox centers of
 * every entity in the chain, in order. Bolts follow the entities while they last and fade after {@link #LIFE_MS}.
 */
final class ChainLightningRenderer {
    private static final long LIFE_MS = 1000;
    /** Bolts stay at full brightness for this share of their life, then fade. */
    private static final float HOLD = 0.6F;
    private static final long FLICKER_MS = 60;
    private static final int SEGMENTS = 7;
    private static final List<Bolt> BOLTS = new CopyOnWriteArrayList<>();

    private ChainLightningRenderer() { }

    /** Entity IDs in chain order, with the last known center of each (used once an entity is gone). */
    private record Bolt(int[] ids, Vec3[] fallback, long start, long seed) { }

    static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(StaticCharge.ChainPayload.TYPE, (payload, context) -> {
            int count = payload.ids().size();
            if (count < 1 || payload.points().size() < count * 3) return;
            int[] ids = new int[count];
            Vec3[] points = new Vec3[count];
            for (int index = 0; index < count; index++) {
                ids[index] = payload.ids().get(index);
                points[index] = new Vec3(payload.points().get(index * 3), payload.points().get(index * 3 + 1),
                        payload.points().get(index * 3 + 2));
            }
            playSound(points[0]);
            if (count >= 2) BOLTS.add(new Bolt(ids, points, System.currentTimeMillis(), new Random().nextLong()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BOLTS.clear());
        LevelRenderEvents.COLLECT_SUBMITS.register(ChainLightningRenderer::submit);
    }

    private static void submit(LevelRenderContext context) {
        if (BOLTS.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        long now = System.currentTimeMillis();
        BOLTS.removeIf(bolt -> now - bolt.start() > LIFE_MS);
        Vec3 camera = context.levelState().cameraRenderState.pos;
        PoseStack stack = context.poseStack();
        for (Bolt bolt : BOLTS) {
            float age = (now - bolt.start()) / (float) LIFE_MS;
            float fade = age < HOLD ? 1.0F : 1.0F - (age - HOLD) / (1.0F - HOLD);
            // A new zig-zag every FLICKER_MS, so the bolt crackles while it lasts.
            Random random = new Random(bolt.seed() + (now - bolt.start()) / FLICKER_MS);
            List<Vec3> path = new ArrayList<>();
            Vec3 previous = null;
            for (int index = 0; index < bolt.ids().length; index++) {
                Vec3 point = center(minecraft, bolt, index);
                if (previous != null) jag(path, previous, point, random);
                else path.add(point);
                previous = point;
            }
            int glow = argb(fade * 0.35F, 0x5AA0FF), core = argb(fade * 0.9F, 0xE6F4FF);
            stack.pushPose();
            context.submitNodeCollector().submitCustomGeometry(stack, RenderTypes.lightning(), (pose, vertices) -> {
                for (int index = 1; index < path.size(); index++) {
                    Vec3 from = path.get(index - 1).subtract(camera), to = path.get(index).subtract(camera);
                    ribbon(pose, vertices, from, to, 0.14F, glow);
                    ribbon(pose, vertices, from, to, 0.045F, core);
                }
            });
            stack.popPose();
        }
    }

    /** The discharge sound at the first entity of the chain (a mono file, so it is fully positional). */
    private static void playSound(Vec3 at) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        minecraft.level.playLocalSound(at.x, at.y, at.z, StaticCharge.DISCHARGE_SOUND,
                net.minecraft.sounds.SoundSource.PLAYERS, 1.2F, 0.9F + new Random().nextFloat() * 0.2F, false);
    }

    private static Vec3 center(Minecraft minecraft, Bolt bolt, int index) {
        Entity entity = minecraft.level.getEntity(bolt.ids()[index]);
        if (entity == null || entity.isRemoved()) return bolt.fallback()[index];
        Vec3 position = entity.getPosition(minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        Vec3 center = position.add(0, entity.getBbHeight() * 0.5, 0);
        bolt.fallback()[index] = center;
        return center;
    }

    /** Adds the points from {@code from} (exclusive) to {@code to}, pushed sideways at random in between. */
    private static void jag(List<Vec3> path, Vec3 from, Vec3 to, Random random) {
        double length = from.distanceTo(to);
        double spread = Math.min(0.6, 0.12 + length * 0.06);
        for (int step = 1; step < SEGMENTS; step++) {
            Vec3 point = from.lerp(to, step / (double) SEGMENTS);
            path.add(point.add((random.nextDouble() - 0.5) * 2 * spread, (random.nextDouble() - 0.5) * 2 * spread,
                    (random.nextDouble() - 0.5) * 2 * spread));
        }
        path.add(to);
    }

    /** A flat strip from {@code from} to {@code to} (camera-relative), turned to face the camera. */
    private static void ribbon(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer vertices, Vec3 from,
                               Vec3 to, float width, int color) {
        Vec3 direction = to.subtract(from);
        Vec3 side = direction.cross(from.add(to).scale(0.5));
        if (side.lengthSqr() < 1.0E-8) return;
        side = side.normalize().scale(width);
        vertices.addVertex(pose, (float) (from.x + side.x), (float) (from.y + side.y), (float) (from.z + side.z)).setColor(color);
        vertices.addVertex(pose, (float) (from.x - side.x), (float) (from.y - side.y), (float) (from.z - side.z)).setColor(color);
        vertices.addVertex(pose, (float) (to.x - side.x), (float) (to.y - side.y), (float) (to.z - side.z)).setColor(color);
        vertices.addVertex(pose, (float) (to.x + side.x), (float) (to.y + side.y), (float) (to.z + side.z)).setColor(color);
    }

    private static int argb(float alpha, int rgb) {
        return Math.max(0, Math.min(255, Math.round(alpha * 255))) << 24 | rgb;
    }
}
