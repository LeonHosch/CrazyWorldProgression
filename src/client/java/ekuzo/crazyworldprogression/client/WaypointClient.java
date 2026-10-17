package ekuzo.crazyworldprogression.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.mixin.client.RenderPipelinesAccessor;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A local waypoint with a camera-anchored 3D arrow above the XP bar. */
public final class WaypointClient {
    public record Waypoint(Identifier dimension, double x, double y, double z) { }

    private static Waypoint active;
    private static final int SIDES = 12;
    private static final double HUD_DEPTH = 2.0;
    private static final double ARRIVAL_DISTANCE_SQUARED = 10.0 * 10.0;
    private static final Matrix3x2f GUI_POSE = new Matrix3x2f();
    private static final RenderPipeline ARROW_PIPELINE = RenderPipelinesAccessor.cwp$register(RenderPipeline.builder()
            .withLocation(CrazyWorldProgression.id("pipeline/waypoint_gui"))
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withVertexShader("core/gui")
            .withFragmentShader("core/gui")
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .build());

    private WaypointClient() { }

    public static void initialize() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommands.literal("cwpnavigate")
                        .then(ClientCommands.argument("dimension", StringArgumentType.word())
                                .then(ClientCommands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(ClientCommands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(ClientCommands.argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(context -> {
                                                            Identifier dimension = Identifier.tryParse(
                                                                    StringArgumentType.getString(context, "dimension"));
                                                            if (dimension == null) return 0;
                                                            set(dimension, DoubleArgumentType.getDouble(context, "x"),
                                                                    DoubleArgumentType.getDouble(context, "y"),
                                                                    DoubleArgumentType.getDouble(context, "z"));
                                                            context.getSource().sendFeedback(Component.literal(
                                                                    "Guiding to " + coordinates(active)));
                                                            return 1;
                                                        })))))));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> active = null);
        ClientTickEvents.END_CLIENT_TICK.register(WaypointClient::tick);
        HudElementRegistry.attachElementAfter(VanillaHudElements.INFO_BAR,
                CrazyWorldProgression.id("waypoint_arrow"), WaypointClient::render);
    }

    public static Waypoint active() { return active; }

    public static void clear() { active = null; }

    public static void set(Identifier dimension, double x, double y, double z) {
        if (dimension == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Waypoint coordinates must be finite");
        active = new Waypoint(dimension, x, y, z);
    }

    public static String coordinates(Waypoint waypoint) {
        return waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z()
                + " (" + waypoint.dimension() + ")";
    }

    private static void tick(Minecraft client) {
        Waypoint waypoint = active;
        if (waypoint == null || client.level == null || client.player == null
                || !client.level.dimension().identifier().equals(waypoint.dimension())) return;
        if (client.player.position().distanceToSqr(waypoint.x(), waypoint.y(), waypoint.z())
                > ARRIVAL_DISTANCE_SQUARED) return;
        active = null;
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.15F, 0.8F));
        client.gui.hud.setOverlayMessage(Component.translatable("screen.crazy-world-progression.waypoint.arrived"), false);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft client = Minecraft.getInstance();
        Waypoint waypoint = active;
        if (waypoint == null || client.level == null || client.player == null
                || !client.level.dimension().identifier().equals(waypoint.dimension())) return;
        int guiHeight = graphics.guiHeight();
        var cameraState = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        float projectionY = cameraState.projectionMatrix.m11();
        if (guiHeight <= 0 || projectionY <= 0.0F) return;
        Vec3 camera = cameraState.pos;
        var view = client.gameRenderer.mainCamera();
        Vec3 forward = vector(view.forwardVector());
        Vec3 up = vector(view.upVector());
        Vec3 right = vector(view.leftVector()).scale(-1.0);
        double worldPerPixel = 2.0 * HUD_DEPTH / (projectionY * guiHeight);
        double screenY = guiHeight - 54.0;
        double heightFromCenter = (guiHeight / 2.0 - screenY) * worldPerPixel;
        Vec3 anchor = camera.add(forward.scale(HUD_DEPTH)).add(up.scale(heightFromCenter));

        // Aim from the arrow's actual rendered position, including toward or away from the camera.
        Vec3 destination = new Vec3(waypoint.x(), waypoint.y(), waypoint.z()).subtract(anchor);
        Vec3 axis = destination.lengthSqr() < 0.0001 ? forward : destination.normalize();
        Vec3 reference = Math.abs(axis.dot(right)) < 0.95 ? right : up;
        Vec3 cross = reference.subtract(axis.scale(axis.dot(reference))).normalize();
        Vec3 radialDepth = axis.cross(cross).normalize();
        List<Face> faces = arrowFaces(camera, anchor, axis, cross, radialDepth, forward, worldPerPixel);
        List<GuiVertex> vertices = new ArrayList<>(faces.size() * 4);
        for (Face face : faces) {
            vertices.add(project(face.a(), face.color(), camera, right, up, forward,
                    graphics.guiWidth(), guiHeight, cameraState.projectionMatrix.m00(), projectionY));
            vertices.add(project(face.b(), face.color(), camera, right, up, forward,
                    graphics.guiWidth(), guiHeight, cameraState.projectionMatrix.m00(), projectionY));
            vertices.add(project(face.c(), face.color(), camera, right, up, forward,
                    graphics.guiWidth(), guiHeight, cameraState.projectionMatrix.m00(), projectionY));
            vertices.add(project(face.d(), face.color(), camera, right, up, forward,
                    graphics.guiWidth(), guiHeight, cameraState.projectionMatrix.m00(), projectionY));
        }
        client.gameRenderer.gameRenderState().guiRenderState.addGuiElement(new ArrowRenderState(
                vertices, new ScreenRectangle(0, 0, graphics.guiWidth(), guiHeight)));
    }

    private static Vec3 vector(org.joml.Vector3fc value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static GuiVertex project(Vec3 position, int color, Vec3 camera, Vec3 right, Vec3 up, Vec3 forward,
                                     int width, int height, float projectionX, float projectionY) {
        Vec3 relative = position.subtract(camera);
        double depth = Math.max(0.05, relative.dot(forward));
        return new GuiVertex((float) (width * 0.5 + relative.dot(right) * projectionX * width * 0.5 / depth),
                (float) (height * 0.5 - relative.dot(up) * projectionY * height * 0.5 / depth), color);
    }

    private record GuiVertex(float x, float y, int color) { }

    private record ArrowRenderState(List<GuiVertex> vertices, ScreenRectangle bounds)
            implements GuiElementRenderState {
        @Override public RenderPipeline pipeline() { return ARROW_PIPELINE; }
        @Override public TextureSetup textureSetup() { return TextureSetup.noTexture(); }
        @Override public ScreenRectangle scissorArea() { return null; }
        @Override public void buildVertices(VertexConsumer consumer) {
            for (GuiVertex vertex : vertices)
                consumer.addVertexWith2DPose(GUI_POSE, vertex.x(), vertex.y()).setColor(vertex.color());
        }
    }

    private static List<Face> arrowFaces(Vec3 camera, Vec3 center, Vec3 axis, Vec3 cross,
                                         Vec3 depth, Vec3 cameraForward, double pixel) {
        List<Face> faces = new ArrayList<>(SIDES * 4);
        Vec3 rear = center.add(axis.scale(-11.0 * pixel));
        Vec3 shoulder = center.add(axis.scale(2.0 * pixel));
        Vec3 tip = center.add(axis.scale(12.0 * pixel));
        for (int side = 0; side < SIDES; side++) {
            double a = Math.PI * 2.0 * side / SIDES;
            double b = Math.PI * 2.0 * (side + 1) / SIDES;
            Vec3 r0 = radial(cross, depth, a, 2.5 * pixel);
            Vec3 r1 = radial(cross, depth, b, 2.5 * pixel);
            Vec3 h0 = radial(cross, depth, a, 5.0 * pixel);
            Vec3 h1 = radial(cross, depth, b, 5.0 * pixel);
            int shaftColor = shade(0xE5A525, a);
            int headColor = shade(0xFFD34D, a);
            faces.add(new Face(rear.add(r0), rear.add(r1), shoulder.add(r1), shoulder.add(r0), shaftColor));
            faces.add(new Face(rear, rear.add(r1), rear.add(r0), rear, 0xFF805018));
            faces.add(new Face(shoulder.add(h0), shoulder.add(h1), tip, tip, headColor));
            faces.add(new Face(shoulder, shoulder.add(h0), shoulder.add(h1), shoulder, 0xFF9C6D24));
            Vec3 glint0 = radial(cross, depth, a, 1.2 * pixel);
            Vec3 glint1 = radial(cross, depth, b, 1.2 * pixel);
            Vec3 glint = tip.add(axis.scale(0.15 * pixel));
            faces.add(new Face(glint, glint.add(glint0), glint.add(glint1), glint, 0xFFFFF3B0));
        }
        // Draw far faces first because the GUI pass does not use world depth.
        faces.sort(Comparator.comparingDouble((Face face) -> face.depth(camera, cameraForward)).reversed());
        return faces;
    }

    private record Face(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
        double depth(Vec3 camera, Vec3 forward) {
            return a.add(b).add(c).add(d).scale(0.25).subtract(camera).dot(forward);
        }
    }

    private static Vec3 radial(Vec3 cross, Vec3 depth, double angle, double radius) {
        return cross.scale(Math.cos(angle) * radius).add(depth.scale(Math.sin(angle) * radius));
    }

    private static int shade(int rgb, double angle) {
        double light = 0.58 + 0.42 * Math.max(0.0, Math.sin(angle) * 0.7 + Math.cos(angle) * 0.3);
        return 0xFF000000 | ((int) (((rgb >> 16) & 255) * light) << 16)
                | ((int) (((rgb >> 8) & 255) * light) << 8) | (int) ((rgb & 255) * light);
    }

}
