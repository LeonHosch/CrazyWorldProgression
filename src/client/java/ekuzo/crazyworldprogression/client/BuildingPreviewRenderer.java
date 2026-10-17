package ekuzo.crazyworldprogression.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.buildings.BuildingPreviewNetworking;
import ekuzo.crazyworldprogression.mixin.client.RenderPipelinesAccessor;
import ekuzo.crazyworldprogression.mixin.client.RenderTypeAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;

/** Transparent purple voxels showing the exact blocks saved in each scripted building. */
public final class BuildingPreviewRenderer {
    public static final int PURPLE = 0x88B455F5;
    private static final RenderPipeline PIPELINE = RenderPipelinesAccessor.cwp$register(RenderPipeline.builder()
            .withLocation(CrazyWorldProgression.id("pipeline/building_preview"))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(false).build());
    private static final RenderType RENDER_TYPE = RenderTypeAccessor.cwp$create("cwp_building_preview",
            RenderSetup.builder(PIPELINE).createRenderSetup());
    private static volatile List<Mesh> meshes = List.of();

    private BuildingPreviewRenderer() { }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(BuildingPreviewNetworking.Payload.TYPE,
                (payload, context) -> replace(BuildingPreviewNetworking.decode(payload.json())));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> meshes = List.of());
        LevelRenderEvents.COLLECT_SUBMITS.register(BuildingPreviewRenderer::submit);
    }

    private static void replace(List<BuildingPreviewNetworking.Preview> previews) {
        List<Mesh> updated = new ArrayList<>();
        for (var preview : previews) updated.add(new Mesh(preview.dimension(), preview.x(), preview.y(),
                preview.z(), preview.sizeX(), preview.sizeY(), preview.sizeZ(), build(preview)));
        meshes = List.copyOf(updated);
    }

    private static void submit(LevelRenderContext context) {
        if (!EchelonClientSettings.editorPlacementPreviews) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        String dimension = minecraft.level.dimension().identifier().toString();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        for (Mesh mesh : meshes) {
            if (!mesh.dimension().equals(dimension) || mesh.vertices().isEmpty()
                    || distanceToBoundsSquared(camera, mesh) > 192.0 * 192.0) continue;
            PoseStack stack = context.poseStack();
            stack.pushPose();
            stack.translate(mesh.x() - camera.x, mesh.y() - camera.y, mesh.z() - camera.z);
            context.submitNodeCollector().submitCustomGeometry(stack, RENDER_TYPE, (pose, vertices) -> {
                for (Vertex vertex : mesh.vertices())
                    vertices.addVertex(pose, vertex.x(), vertex.y(), vertex.z()).setColor(PURPLE);
            });
            stack.popPose();
        }
    }

    private static double distanceToBoundsSquared(Vec3 camera, Mesh mesh) {
        double dx = Math.max(Math.max(mesh.x() - camera.x, 0), camera.x - mesh.x() - mesh.sizeX());
        double dy = Math.max(Math.max(mesh.y() - camera.y, 0), camera.y - mesh.y() - mesh.sizeY());
        double dz = Math.max(Math.max(mesh.z() - camera.z, 0), camera.z - mesh.z() - mesh.sizeZ());
        return dx * dx + dy * dy + dz * dz;
    }

    private static List<Vertex> build(BuildingPreviewNetworking.Preview preview) {
        int sx = preview.sizeX(), sy = preview.sizeY(), sz = preview.sizeZ();
        if (sx < 1 || sy < 1 || sz < 1 || (long) sx * sy * sz > 131_072) return List.of();
        BitSet blocks = BitSet.valueOf(Base64.getDecoder().decode(preview.blocks()));
        List<Vertex> result = new ArrayList<>();
        for (int index = blocks.nextSetBit(0); index >= 0 && index < sx * sy * sz;
             index = blocks.nextSetBit(index + 1)) {
            int x = index % sx, z = index / sx % sz, y = index / (sx * sz);
            if (x == 0 || !blocks.get(index - 1)) face(result, x, y, z, 0);
            if (x == sx - 1 || !blocks.get(index + 1)) face(result, x, y, z, 1);
            if (y == 0 || !blocks.get(index - sx * sz)) face(result, x, y, z, 2);
            if (y == sy - 1 || !blocks.get(index + sx * sz)) face(result, x, y, z, 3);
            if (z == 0 || !blocks.get(index - sx)) face(result, x, y, z, 4);
            if (z == sz - 1 || !blocks.get(index + sx)) face(result, x, y, z, 5);
        }
        return List.copyOf(result);
    }

    private static void face(List<Vertex> out, int x, int y, int z, int side) {
        float a = x, b = y, c = z;
        switch (side) {
            case 0 -> quad(out, a,b,c, a,b+1,c, a,b+1,c+1, a,b,c+1);
            case 1 -> quad(out, a+1,b,c, a+1,b,c+1, a+1,b+1,c+1, a+1,b+1,c);
            case 2 -> quad(out, a,b,c, a,b,c+1, a+1,b,c+1, a+1,b,c);
            case 3 -> quad(out, a,b+1,c, a+1,b+1,c, a+1,b+1,c+1, a,b+1,c+1);
            case 4 -> quad(out, a,b,c, a+1,b,c, a+1,b+1,c, a,b+1,c);
            case 5 -> quad(out, a,b,c+1, a,b+1,c+1, a+1,b+1,c+1, a+1,b,c+1);
            default -> { }
        }
    }

    private static void quad(List<Vertex> out, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        out.add(new Vertex(ax,ay,az)); out.add(new Vertex(bx,by,bz));
        out.add(new Vertex(cx,cy,cz)); out.add(new Vertex(dx,dy,dz));
    }

    private record Vertex(float x, float y, float z) { }
    private record Mesh(String dimension, int x, int y, int z, int sizeX, int sizeY, int sizeZ,
                        List<Vertex> vertices) { }
}
