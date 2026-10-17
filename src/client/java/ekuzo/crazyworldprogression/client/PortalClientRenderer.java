package ekuzo.crazyworldprogression.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.mixin.client.RenderPipelinesAccessor;
import ekuzo.crazyworldprogression.mixin.client.RenderTypeAccessor;
import ekuzo.crazyworldprogression.portals.PortalDefinition;
import ekuzo.crazyworldprogression.portals.PortalContactGeometry;
import ekuzo.crazyworldprogression.portals.PortalNetworking;
import ekuzo.crazyworldprogression.portals.PortalPreviewNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Receives portal snapshots and renders each open portal as a solid, flat-colored two-sided surface. */
public final class PortalClientRenderer {
    private static final double RENDER_DISTANCE_SQUARED = 256.0 * 256.0;
    private static final double PARTICLE_DISTANCE_SQUARED = 96.0 * 96.0;
    private static final int ELLIPSE_SEGMENTS = 48;
    private static final int SURFACE_ALPHA = 64;
    private static final Map<String, CachedMesh> CACHE = new HashMap<>();
    private static volatile List<PortalNetworking.PortalSnapshot> portals = List.of();
    private static volatile List<PortalNetworking.PortalSnapshot> previews = List.of();
    private static int particleTick;
    private static String contact;
    private static final RenderPipeline PORTAL_PIPELINE = RenderPipelinesAccessor.cwp$register(
            RenderPipeline.builder()
                    .withLocation(CrazyWorldProgression.id("pipeline/translucent_portal"))
                    .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                    .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                    .withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                    .withCull(false)
                    .build());
    private static final RenderType PORTAL_RENDER_TYPE = RenderTypeAccessor.cwp$create(
            "cwp_translucent_portal", RenderSetup.builder(PORTAL_PIPELINE).createRenderSetup());
    private static final RenderPipeline PREVIEW_PIPELINE = RenderPipelinesAccessor.cwp$register(
            RenderPipeline.builder()
                    .withLocation(CrazyWorldProgression.id("pipeline/portal_preview"))
                    .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                    .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                    .withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .withCull(false)
                    .build());
    private static final RenderType PREVIEW_RENDER_TYPE = RenderTypeAccessor.cwp$create(
            "cwp_portal_preview", RenderSetup.builder(PREVIEW_PIPELINE).createRenderSetup());

    // Prevent construction of the static client portal renderer.
    private PortalClientRenderer() {
    }

    // Register portal snapshot lifecycle handling and world geometry submission.
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(PortalNetworking.PortalSnapshotPayload.TYPE,
                (payload, context) -> replaceSnapshot(PortalNetworking.decode(payload.json())));
        ClientPlayNetworking.registerGlobalReceiver(PortalPreviewNetworking.Payload.TYPE,
                (payload, context) -> {
                    previews = PortalNetworking.decode(payload.json());
                    pruneCache();
                });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            replaceSnapshot(List.of());
            previews = List.of();
            contact = null;
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(PortalClientRenderer::submit);
        ClientTickEvents.END_CLIENT_TICK.register(PortalClientRenderer::spawnBorderParticles);
        ClientTickEvents.END_CLIENT_TICK.register(PortalClientRenderer::detectContact);
    }

    // Atomically replace render state and discard cached meshes for removed or resized portals.
    private static void replaceSnapshot(List<PortalNetworking.PortalSnapshot> replacement) {
        portals = replacement;
        contact = null;
        pruneCache();
    }

    // Render snapshots are enough to find contact locally; the server validates the requested entrance.
    private static void detectContact(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            contact = null;
            return;
        }
        String dimension = minecraft.level.dimension().identifier().toString();
        Vec3 center = minecraft.player.position().add(0.0, 0.9, 0.0);
        PortalNetworking.PortalSnapshot touching = null;
        for (PortalNetworking.PortalSnapshot portal : portals) {
            if (portal.open() && portal.dimension().equals(dimension) && contains(portal, center)) {
                touching = portal;
                break;
            }
        }
        if (touching == null) {
            contact = null;
            return;
        }
        String key = placementKey(touching);
        if (key.equals(contact)) return;
        if (!ClientPlayNetworking.canSend(PortalNetworking.PortalContactPayload.TYPE)) return;
        contact = key;
        ClientPlayNetworking.send(new PortalNetworking.PortalContactPayload(touching.id(),
                touching.centerX(), touching.centerY(), touching.centerZ()));
    }

    private static boolean contains(PortalNetworking.PortalSnapshot portal, Vec3 point) {
        return PortalContactGeometry.contains(PortalDefinition.Shape.parse(portal.shape()),
                PortalDefinition.Plane.parse(portal.plane()), portal.centerX(), portal.centerY(),
                portal.centerZ(), portal.width(), portal.height(), point, 0.85);
    }

    private static void pruneCache() {
        synchronized (CACHE) {
            var live = new java.util.HashSet<String>();
            portals.forEach(portal -> live.add(placementKey(portal)));
            previews.forEach(preview -> live.add(placementKey(preview)));
            CACHE.keySet().retainAll(live);
        }
    }

    // Submit nearby open portals in the current client dimension.
    private static void submit(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        String dimension = minecraft.level.dimension().identifier().toString();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        for (PortalNetworking.PortalSnapshot portal : portals) {
            if (!portal.open() || !portal.dimension().equals(dimension)
                    || camera.distanceToSqr(portal.centerX(), portal.centerY(), portal.centerZ())
                    > RENDER_DISTANCE_SQUARED) continue;
            Mesh mesh = mesh(portal);
            submitMesh(context, camera, portal, mesh);
        }
        if (!EchelonClientSettings.editorPlacementPreviews) return;
        for (PortalNetworking.PortalSnapshot preview : previews) {
            if (!preview.dimension().equals(dimension)
                    || camera.distanceToSqr(preview.centerX(), preview.centerY(), preview.centerZ())
                    > RENDER_DISTANCE_SQUARED) continue;
            submitMesh(context, camera, preview, mesh(preview), PREVIEW_RENDER_TYPE,
                    BuildingPreviewRenderer.PURPLE);
        }
    }

    // Resolve or rebuild the local-space mesh for one portal geometry revision.
    private static Mesh mesh(PortalNetworking.PortalSnapshot portal) {
        int geometryHash = java.util.Objects.hash(portal.shape(), portal.plane(), portal.width(), portal.height());
        synchronized (CACHE) {
            String key = placementKey(portal);
            CachedMesh cached = CACHE.get(key);
            if (cached == null || cached.geometryHash() != geometryHash) {
                cached = new CachedMesh(geometryHash, buildMesh(portal));
                CACHE.put(key, cached);
            }
            return cached.mesh();
        }
    }

    private static String placementKey(PortalNetworking.PortalSnapshot portal) {
        return portal.id() + "|" + portal.dimension() + "|" + portal.centerX() + "|"
                + portal.centerY() + "|" + portal.centerZ();
    }

    // Translate local geometry to camera space and submit one uniformly colored render node.
    private static void submitMesh(LevelRenderContext context, Vec3 camera,
                                   PortalNetworking.PortalSnapshot portal, Mesh mesh) {
        submitMesh(context, camera, portal, mesh, PORTAL_RENDER_TYPE,
                SURFACE_ALPHA << 24 | portal.colorRgb());
    }

    private static void submitMesh(LevelRenderContext context, Vec3 camera,
                                   PortalNetworking.PortalSnapshot portal, Mesh mesh,
                                   RenderType renderType, int color) {
        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(portal.centerX() - camera.x, portal.centerY() - camera.y, portal.centerZ() - camera.z);
        context.submitNodeCollector().submitCustomGeometry(poseStack, renderType,
                (pose, vertices) -> emit(pose, vertices, mesh.vertices(), color));
        poseStack.popPose();
    }

    // Spawn a dense client-only ring of white vanilla particles around nearby open portal boundaries.
    private static void spawnBorderParticles(Minecraft minecraft) {
        if (!EchelonClientSettings.portalParticles) return;
        if (minecraft.level == null || minecraft.player == null || ++particleTick % 2 != 0) return;
        String dimension = minecraft.level.dimension().identifier().toString();
        RandomSource random = minecraft.level.getRandom();
        for (PortalNetworking.PortalSnapshot portal : portals) {
            if (!portal.open() || !portal.dimension().equals(dimension)
                    || minecraft.player.distanceToSqr(portal.centerX(), portal.centerY(), portal.centerZ())
                    > PARTICLE_DISTANCE_SQUARED) continue;
            for (int sample = 0; sample < 5; sample++) {
                Coordinate edge = randomBoundaryPoint(portal, random);
                Vec3 point = worldPoint(portal, edge.u(), edge.v());
                minecraft.level.addParticle(ParticleTypes.END_ROD, point.x, point.y, point.z,
                        (random.nextDouble() - 0.5) * 0.012, (random.nextDouble() - 0.5) * 0.012,
                        (random.nextDouble() - 0.5) * 0.012);
            }
        }
    }

    // Sample one point directly on the configured shape perimeter for a vanilla portal particle.
    private static Coordinate randomBoundaryPoint(PortalNetworking.PortalSnapshot portal, RandomSource random) {
        double halfWidth = portal.width() / 2.0;
        double halfHeight = portal.height() / 2.0;
        double progress = random.nextDouble();
        return switch (PortalDefinition.Shape.parse(portal.shape())) {
            case ELLIPSE -> coordinate(Math.cos(progress * Math.PI * 2.0) * halfWidth,
                    Math.sin(progress * Math.PI * 2.0) * halfHeight);
            case RECTANGLE -> interpolateBoundary(List.of(coordinate(-halfWidth, -halfHeight),
                    coordinate(halfWidth, -halfHeight), coordinate(halfWidth, halfHeight),
                    coordinate(-halfWidth, halfHeight)), progress);
            case DIAMOND -> interpolateBoundary(List.of(coordinate(0.0, halfHeight),
                    coordinate(halfWidth, 0.0), coordinate(0.0, -halfHeight),
                    coordinate(-halfWidth, 0.0)), progress);
            case TRIANGLE -> interpolateBoundary(List.of(coordinate(-halfWidth, -halfHeight),
                    coordinate(halfWidth, -halfHeight), coordinate(0.0, halfHeight)), progress);
        };
    }

    // Interpolate around one closed polygon edge loop using an evenly selected side.
    private static Coordinate interpolateBoundary(List<Coordinate> vertices, double progress) {
        double scaled = progress * vertices.size();
        int index = Math.min(vertices.size() - 1, (int) scaled);
        Coordinate first = vertices.get(index);
        Coordinate second = vertices.get((index + 1) % vertices.size());
        double local = scaled - index;
        return coordinate(first.u() + (second.u() - first.u()) * local,
                first.v() + (second.v() - first.v()) * local);
    }

    // Convert portal-local boundary coordinates into the portal's world-space plane.
    private static Vec3 worldPoint(PortalNetworking.PortalSnapshot portal, double u, double v) {
        return switch (PortalDefinition.Plane.parse(portal.plane())) {
            case XY -> new Vec3(portal.centerX() + u, portal.centerY() + v, portal.centerZ());
            case XZ -> new Vec3(portal.centerX() + u, portal.centerY(), portal.centerZ() + v);
            case YZ -> new Vec3(portal.centerX(), portal.centerY() + v, portal.centerZ() + u);
        };
    }

    // Emit every face in both winding directions so either approach side is fully visible.
    private static void emit(PoseStack.Pose pose, VertexConsumer consumer, List<Vertex> mesh, int color) {
        for (int index = 0; index < mesh.size(); index += 4) {
            Vertex a = mesh.get(index);
            Vertex b = mesh.get(index + 1);
            Vertex c = mesh.get(index + 2);
            Vertex d = mesh.get(index + 3);
            vertex(consumer, pose, a, color);
            vertex(consumer, pose, b, color);
            vertex(consumer, pose, c, color);
            vertex(consumer, pose, d, color);
            vertex(consumer, pose, d, color);
            vertex(consumer, pose, c, color);
            vertex(consumer, pose, b, color);
            vertex(consumer, pose, a, color);
        }
    }

    // Write one local mesh position in the portal's selected two-dimensional plane.
    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, Vertex vertex, int color) {
        consumer.addVertex(pose, vertex.x(), vertex.y(), vertex.z()).setColor(color);
    }

    // Build a filled quad mesh matching the portal's configured two-dimensional shape.
    private static Mesh buildMesh(PortalNetworking.PortalSnapshot portal) {
        List<Vertex> fill = new ArrayList<>();
        double halfWidth = portal.width() / 2.0;
        double halfHeight = portal.height() / 2.0;
        PortalDefinition.Plane plane = PortalDefinition.Plane.parse(portal.plane());
        switch (PortalDefinition.Shape.parse(portal.shape())) {
            case RECTANGLE -> buildPolygon(fill, plane, List.of(coordinate(-halfWidth, -halfHeight),
                    coordinate(halfWidth, -halfHeight), coordinate(halfWidth, halfHeight),
                    coordinate(-halfWidth, halfHeight)));
            case ELLIPSE -> buildEllipse(fill, plane, halfWidth, halfHeight);
            case DIAMOND -> buildPolygon(fill, plane, List.of(coordinate(0.0, halfHeight),
                    coordinate(halfWidth, 0.0), coordinate(0.0, -halfHeight),
                    coordinate(-halfWidth, 0.0)));
            case TRIANGLE -> buildPolygon(fill, plane, List.of(coordinate(-halfWidth, -halfHeight),
                    coordinate(halfWidth, -halfHeight), coordinate(0.0, halfHeight)));
        }
        return new Mesh(List.copyOf(fill));
    }

    // Tessellate a filled ellipse from a smooth perimeter loop.
    private static void buildEllipse(List<Vertex> fill, PortalDefinition.Plane plane,
                                     double halfWidth, double halfHeight) {
        List<Coordinate> outer = new ArrayList<>();
        for (int segment = 0; segment < ELLIPSE_SEGMENTS; segment++) {
            double angle = Math.PI * 2.0 * segment / ELLIPSE_SEGMENTS;
            outer.add(coordinate(Math.cos(angle) * halfWidth, Math.sin(angle) * halfHeight));
        }
        buildPolygon(fill, plane, outer);
    }

    // Fill one convex edge loop as a center-based fan.
    private static void buildPolygon(List<Vertex> fill, PortalDefinition.Plane plane,
                                     List<Coordinate> outerCoordinates) {
        List<Vertex> outer = outerCoordinates.stream().map(value -> point(plane, value.u(), value.v())).toList();
        polygon(fill, point(plane, 0.0, 0.0), outer);
    }

    // Fill a convex polygon as a center-based fan using degenerate quads.
    private static void polygon(List<Vertex> out, Vertex center, List<Vertex> edge) {
        for (int index = 0; index < edge.size(); index++) {
            triangle(out, center, edge.get(index), edge.get((index + 1) % edge.size()));
        }
    }

    // Append a triangle in the quad topology by repeating its final vertex.
    private static void triangle(List<Vertex> out, Vertex a, Vertex b, Vertex c) {
        quad(out, a, b, c, c);
    }

    // Append one four-position face to the mesh under construction.
    private static void quad(List<Vertex> out, Vertex a, Vertex b, Vertex c, Vertex d) {
        out.add(a);
        out.add(b);
        out.add(c);
        out.add(d);
    }

    // Map portal-local width and height coordinates onto its configured world plane.
    private static Vertex point(PortalDefinition.Plane plane, double u, double v) {
        return switch (plane) {
            case XY -> new Vertex((float) u, (float) v, 0.0F);
            case XZ -> new Vertex((float) u, 0.0F, (float) v);
            case YZ -> new Vertex(0.0F, (float) v, (float) u);
        };
    }

    // Create one convenient portal-local coordinate before its plane is known.
    private static Coordinate coordinate(double u, double v) {
        return new Coordinate(u, v);
    }

    private record Vertex(float x, float y, float z) {
    }

    private record Coordinate(double u, double v) {
    }

    private record Mesh(List<Vertex> vertices) {
    }

    private record CachedMesh(int geometryHash, Mesh mesh) {
    }
}
