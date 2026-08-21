package ekuzo.crazyworldprogression.veil;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

public final class VeilRenderer {
    private static final int SEGMENTS = 512;
    private static final float VERTICAL_SEGMENT_HEIGHT = 16.0F;
    private static final double VEIL_RENDER_DISTANCE = 150.0;
    private static final double VEIL_FADE_START = 100.0;
    private static final double VEIL_RENDER_DISTANCE_SQUARED =
        VEIL_RENDER_DISTANCE * VEIL_RENDER_DISTANCE;
    private static final long TRANSITION_ATTACK_MILLIS = 90L;
    private static final long TRANSITION_DURATION_MILLIS = 650L;
    private static final float VEIL_FOG_END = 100.0F;
    private static final double INNER_VEIL_RADIUS = VeilManager.WORLD_RADIUS_ONE - 0.5;
    private static final double OUTER_VEIL_RADIUS = VeilManager.WORLD_RADIUS_TWO - 0.5;
    private static final int INNER_COLOR = rgba(24, 5, 36, 150);
    private static final int OUTER_COLOR = rgba(5, 0, 12, 210);
    private static final int INNER_AMBIENCE = rgba(62, 8, 88, 92);
    private static final int OUTER_AMBIENCE = rgba(14, 0, 28, 165);
    private static final Identifier AMBIENCE_ID =
        Identifier.fromNamespaceAndPath("crazy-world-progression", "veil_ambience");

    private static int ambienceColor;
    private static ClientLevel activeLevel;
    private static VeilZone currentZone;
    private static long transitionStartMillis = Long.MIN_VALUE;
    private static int transitionPeakAlpha;

    private static final RenderStateDataKey<VeilRenderState> VEIL_STATE =
        RenderStateDataKey.create(() -> "crazy-world-progression:veil");

    private VeilRenderer() {
    }

    public static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(context -> {
            BlockPos spawn = context.level().getRespawnData().pos();
            VeilRenderState veil = new VeilRenderState(
                spawn.getX() + 0.5,
                spawn.getZ() + 0.5,
                context.level().getMinY(),
                context.level().getMaxY()
            );

            context.levelState().setData(VEIL_STATE, veil);
            applyAmbience(context.level(), context.levelState(), veil);
        });

        LevelRenderEvents.COLLECT_SUBMITS.register(VeilRenderer::submitVeil);
        HudElementRegistry.addFirst(AMBIENCE_ID, VeilRenderer::renderAmbience);
    }

    private static void applyAmbience(ClientLevel level, LevelRenderState levelState, VeilRenderState veil) {
        CameraRenderState camera = levelState.cameraRenderState;
        SkyRenderState sky = levelState.skyRenderState;
        double x = camera.pos.x - veil.centerX();
        double z = camera.pos.z - veil.centerZ();
        double distanceSquared = x * x + z * z;
        VeilZone newZone;

        if (distanceSquared > OUTER_VEIL_RADIUS * OUTER_VEIL_RADIUS) {
            newZone = VeilZone.OUTER;
            ambienceColor = OUTER_AMBIENCE;
            sky.skyColor = darkenAndTint(sky.skyColor, 0.012F, 0.002F, 0.035F);
            sky.sunriseAndSunsetColor = darkenAndTint(sky.sunriseAndSunsetColor, 0.015F, 0.002F, 0.040F);
            levelState.cloudColor = darkenAndTint(levelState.cloudColor, 0.018F, 0.003F, 0.045F);
            sky.starBrightness *= 0.08F;
        } else if (distanceSquared > INNER_VEIL_RADIUS * INNER_VEIL_RADIUS) {
            newZone = VeilZone.INNER;
            ambienceColor = INNER_AMBIENCE;
            sky.skyColor = darkenAndTint(sky.skyColor, 0.075F, 0.018F, 0.130F);
            sky.sunriseAndSunsetColor = darkenAndTint(sky.sunriseAndSunsetColor, 0.085F, 0.015F, 0.145F);
            levelState.cloudColor = darkenAndTint(levelState.cloudColor, 0.090F, 0.020F, 0.155F);
            sky.starBrightness *= 0.25F;
        } else {
            newZone = VeilZone.SAFE;
            ambienceColor = 0;
        }

        applyVeilFog(camera, newZone);
        updateTransition(level, newZone);
    }

    private static void applyVeilFog(CameraRenderState camera, VeilZone zone) {
        if (zone == VeilZone.SAFE
            || camera.fogType == FogType.WATER
            || camera.fogType == FogType.LAVA
            || camera.fogType == FogType.POWDER_SNOW) {
            return;
        }

        FogData fog = camera.fogData;
        if (fog == null) {
            return;
        }

        boolean outerZone = zone == VeilZone.OUTER;
        float fogStart = outerZone ? 35.0F : 55.0F;
        float tintStrength = outerZone ? 0.92F : 0.80F;
        float targetRed = outerZone ? 0.004F : 0.018F;
        float targetGreen = outerZone ? 0.0F : 0.003F;
        float targetBlue = outerZone ? 0.012F : 0.035F;
        float targetOpacity = outerZone ? 0.96F : 0.90F;

        fog.environmentalStart = Math.min(fog.environmentalStart, fogStart);
        fog.environmentalEnd = Math.min(fog.environmentalEnd, VEIL_FOG_END);
        fog.skyEnd = Math.min(fog.skyEnd, VEIL_FOG_END);
        fog.cloudEnd = Math.min(fog.cloudEnd, VEIL_FOG_END);
        fog.color.set(
            lerp(fog.color.x, targetRed, tintStrength),
            lerp(fog.color.y, targetGreen, tintStrength),
            lerp(fog.color.z, targetBlue, tintStrength),
            Math.max(fog.color.w, targetOpacity)
        );
    }

    private static void updateTransition(ClientLevel level, VeilZone newZone) {
        if (activeLevel != level || currentZone == null) {
            activeLevel = level;
            currentZone = newZone;
            transitionStartMillis = Long.MIN_VALUE;
            return;
        }

        if (currentZone == newZone) {
            return;
        }

        boolean enteringDeeper = newZone.ordinal() > currentZone.ordinal();
        transitionPeakAlpha = enteringDeeper
            ? (newZone == VeilZone.OUTER ? 235 : 205)
            : 145;
        transitionStartMillis = Util.getMillis();

        SoundEvent sound = newZone == VeilZone.OUTER
            ? SoundEvents.RESPAWN_ANCHOR_DEPLETE.value()
            : SoundEvents.PORTAL_TRIGGER;
        float pitch = enteringDeeper ? (newZone == VeilZone.OUTER ? 0.62F : 0.78F) : 1.08F;
        float volume = enteringDeeper ? 0.65F : 0.42F;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));

        currentZone = newZone;
    }

    private static void renderAmbience(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (Minecraft.getInstance().level == null) {
            return;
        }

        if (ambienceColor != 0) {
            graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ambienceColor);
        }

        int transitionAlpha = transitionAlpha();
        if (transitionAlpha > 0) {
            graphics.fill(
                0,
                0,
                graphics.guiWidth(),
                graphics.guiHeight(),
                rgba(4, 0, 10, transitionAlpha)
            );
        }
    }

    private static int transitionAlpha() {
        if (transitionStartMillis == Long.MIN_VALUE) {
            return 0;
        }

        long elapsed = Util.getMillis() - transitionStartMillis;
        if (elapsed < 0L || elapsed >= TRANSITION_DURATION_MILLIS) {
            transitionStartMillis = Long.MIN_VALUE;
            return 0;
        }

        double strength;
        if (elapsed < TRANSITION_ATTACK_MILLIS) {
            strength = elapsed / (double) TRANSITION_ATTACK_MILLIS;
        } else {
            strength = 1.0 - (elapsed - TRANSITION_ATTACK_MILLIS)
                / (double) (TRANSITION_DURATION_MILLIS - TRANSITION_ATTACK_MILLIS);
        }

        strength = strength * strength * (3.0 - 2.0 * strength);
        return clampColor((int) Math.round(transitionPeakAlpha * strength));
    }

    private static void submitVeil(LevelRenderContext context) {
        VeilRenderState veil = context.levelState().getData(VEIL_STATE);
        if (veil == null) {
            return;
        }

        CameraRenderState cameraState = context.levelState().cameraRenderState;
        Vec3 camera = cameraState.pos;
        PoseStack poseStack = context.poseStack();
        double cameraX = camera.x - veil.centerX();
        double cameraZ = camera.z - veil.centerZ();

        poseStack.pushPose();
        poseStack.translate(veil.centerX() - camera.x, -camera.y, veil.centerZ() - camera.z);

        submitVeilLayer(
            context,
            poseStack,
            INNER_VEIL_RADIUS,
            veil,
            cameraX,
            cameraZ,
            camera.y,
            context.levelState().gameTime,
            INNER_COLOR
        );
        submitVeilLayer(
            context,
            poseStack,
            OUTER_VEIL_RADIUS,
            veil,
            cameraX,
            cameraZ,
            camera.y,
            context.levelState().gameTime,
            OUTER_COLOR
        );

        poseStack.popPose();
    }

    private static void submitVeilLayer(
        LevelRenderContext context,
        PoseStack poseStack,
        double radius,
        VeilRenderState veil,
        double cameraX,
        double cameraZ,
        double cameraY,
        long gameTime,
        int color
    ) {
        double cameraRadius = Math.sqrt(cameraX * cameraX + cameraZ * cameraZ);
        double horizontalDistance = Math.abs(cameraRadius - radius);
        double verticalDistance = cameraY < veil.minY()
            ? veil.minY() - cameraY
            : Math.max(0.0, cameraY - veil.maxY());
        if (horizontalDistance * horizontalDistance + verticalDistance * verticalDistance
            > VEIL_RENDER_DISTANCE_SQUARED) {
            return;
        }

        context.submitNodeCollector().submitCustomGeometry(
            poseStack,
            RenderTypes.debugQuads(),
            (pose, vertices) -> renderCylinder(
                pose,
                vertices,
                radius,
                veil.minY(),
                veil.maxY(),
                cameraX,
                cameraZ,
                cameraY,
                gameTime,
                color
            )
        );
    }

    private static void renderCylinder(
        PoseStack.Pose pose,
        VertexConsumer vertices,
        double radius,
        float minY,
        float maxY,
        double cameraX,
        double cameraZ,
        double cameraY,
        long gameTime,
        int color
    ) {
        float visibleMinY = Math.max(minY, (float) (cameraY - VEIL_RENDER_DISTANCE));
        float visibleMaxY = Math.min(maxY, (float) (cameraY + VEIL_RENDER_DISTANCE));

        for (int segment = 0; segment < SEGMENTS; segment++) {
            double angleOne = Math.PI * 2.0 * segment / SEGMENTS;
            double angleTwo = Math.PI * 2.0 * (segment + 1) / SEGMENTS;

            float xOne = (float) (Math.cos(angleOne) * radius);
            float zOne = (float) (Math.sin(angleOne) * radius);
            float xTwo = (float) (Math.cos(angleTwo) * radius);
            float zTwo = (float) (Math.sin(angleTwo) * radius);

            if (distanceSquaredToSegment(cameraX, cameraZ, xOne, zOne, xTwo, zTwo)
                > VEIL_RENDER_DISTANCE_SQUARED) {
                continue;
            }

            for (float yOne = visibleMinY; yOne < visibleMaxY; yOne += VERTICAL_SEGMENT_HEIGHT) {
                float yTwo = Math.min(yOne + VERTICAL_SEGMENT_HEIGHT, visibleMaxY);
                int colorBottomOne = veilVertexColor(color, xOne, yOne, zOne, cameraX, cameraY, cameraZ, gameTime);
                int colorBottomTwo = veilVertexColor(color, xTwo, yOne, zTwo, cameraX, cameraY, cameraZ, gameTime);
                int colorTopTwo = veilVertexColor(color, xTwo, yTwo, zTwo, cameraX, cameraY, cameraZ, gameTime);
                int colorTopOne = veilVertexColor(color, xOne, yTwo, zOne, cameraX, cameraY, cameraZ, gameTime);

                if ((colorBottomOne | colorBottomTwo | colorTopTwo | colorTopOne) >>> 24 == 0) {
                    continue;
                }

                vertices.addVertex(pose, xOne, yOne, zOne).setColor(colorBottomOne);
                vertices.addVertex(pose, xTwo, yOne, zTwo).setColor(colorBottomTwo);
                vertices.addVertex(pose, xTwo, yTwo, zTwo).setColor(colorTopTwo);
                vertices.addVertex(pose, xOne, yTwo, zOne).setColor(colorTopOne);
            }
        }
    }

    private static int veilVertexColor(
        int baseColor,
        double x,
        double y,
        double z,
        double cameraX,
        double cameraY,
        double cameraZ,
        long gameTime
    ) {
        double distanceX = x - cameraX;
        double distanceY = y - cameraY;
        double distanceZ = z - cameraZ;
        double distance = Math.sqrt(
            distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ
        );

        double fade = 1.0 - smoothStep(VEIL_FADE_START, VEIL_RENDER_DISTANCE, distance);
        double angle = Math.atan2(z, x);
        double ripple = 0.78 + 0.22 * Math.sin(angle * 18.0 + y * 0.075 + gameTime * 0.08);
        double glow = 0.88 + 0.18 * Math.sin(angle * 9.0 - y * 0.045 + gameTime * 0.055);

        int alpha = clampColor((int) Math.round((baseColor >>> 24) * fade * ripple));
        int red = clampColor((int) Math.round((baseColor >> 16 & 0xFF) * glow));
        int green = clampColor((int) Math.round((baseColor >> 8 & 0xFF) * glow));
        int blue = clampColor((int) Math.round((baseColor & 0xFF) * (glow + 0.08)));
        return rgba(red, green, blue, alpha);
    }

    private static double smoothStep(double edgeStart, double edgeEnd, double value) {
        double progress = Math.max(0.0, Math.min(1.0, (value - edgeStart) / (edgeEnd - edgeStart)));
        return progress * progress * (3.0 - 2.0 * progress);
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private static int clampColor(int color) {
        return Math.max(0, Math.min(255, color));
    }

    private static double distanceSquaredToSegment(
        double pointX,
        double pointZ,
        double startX,
        double startZ,
        double endX,
        double endZ
    ) {
        double segmentX = endX - startX;
        double segmentZ = endZ - startZ;
        double segmentLengthSquared = segmentX * segmentX + segmentZ * segmentZ;
        double progress = ((pointX - startX) * segmentX + (pointZ - startZ) * segmentZ)
            / segmentLengthSquared;
        progress = Math.max(0.0, Math.min(1.0, progress));

        double closestX = startX + segmentX * progress;
        double closestZ = startZ + segmentZ * progress;
        double distanceX = pointX - closestX;
        double distanceZ = pointZ - closestZ;
        return distanceX * distanceX + distanceZ * distanceZ;
    }

    private static int rgba(int red, int green, int blue, int alpha) {
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int darkenAndTint(int color, float redScale, float greenScale, float blueScale) {
        int alpha = color >>> 24;
        int red = Math.round((color >> 16 & 0xFF) * redScale);
        int green = Math.round((color >> 8 & 0xFF) * greenScale);
        int blue = Math.round((color & 0xFF) * blueScale);
        return rgba(red, green, blue, alpha);
    }

    private record VeilRenderState(double centerX, double centerZ, float minY, float maxY) {
    }

    private enum VeilZone {
        SAFE,
        INNER,
        OUTER
    }
}
