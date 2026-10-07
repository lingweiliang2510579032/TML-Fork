package com.example.taczmeshloader.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.IOException;

/** Source Cascade materials. Scene depth must be a separate, completed snapshot. */
@Mod.EventBusSubscriber(modid = "taczmeshloader", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FxCascadeShader {
    private static ShaderInstance additiveShader, alphaShader, distortionShader;
    private static int sceneDepth, width = 1, height = 1, pass;
    private static float time;
    private FxCascadeShader() {}

    @SubscribeEvent public static void register(RegisterShadersEvent event) throws IOException {
        event.registerShader(new FxShaderInstance(event.getResourceProvider(),
                new ResourceLocation("taczmeshloader", "fx_cascade"), DefaultVertexFormat.NEW_ENTITY,
                FxShaderInstance.Blend.ADDITIVE), s -> additiveShader = s);
        event.registerShader(new FxShaderInstance(event.getResourceProvider(),
                new ResourceLocation("taczmeshloader", "fx_cascade_alpha"), DefaultVertexFormat.NEW_ENTITY,
                FxShaderInstance.Blend.ALPHA), s -> alphaShader = s);
        event.registerShader(new FxShaderInstance(event.getResourceProvider(),
                new ResourceLocation("taczmeshloader", "fx_cascade_distortion"), DefaultVertexFormat.NEW_ENTITY,
                FxShaderInstance.Blend.DISTORTION), s -> distortionShader = s);
    }

    /** Unknown materials fail closed instead of silently using an unrelated graph. */
    public static int operation(String material) {
        if (material == null) return -1;
        // SOURCE_OPERATIONS_BEGIN
        if (material.startsWith("source_")) {
            try {
                int value = Integer.parseInt(material.substring(7));
                return value >= 100 && value <= 132 ? value : -1;
            } catch (NumberFormatException invalid) { return -1; }
        }
        // SOURCE_OPERATIONS_END
        return switch (material) {
            case "effect_spark_center", "spark_center" -> 0;
            case "effect_smoke", "red_smoke" -> 1;
            case "effect_spark_streak", "spark_streak" -> 2;
            case "effect_directional", "directional_spark" -> 3;
            case "effect_magnetic", "magnetic_field" -> 4;
            case "effect_glow", "radial_glow" -> 5;
            case "effect_boost_out", "boost_out" -> 6;
            case "effect_boost_core", "boost_core" -> 7;
            case "effect_boost_center", "boost_center" -> 8;
            default -> -1;
        };
    }

    public static RenderType type(ResourceLocation texture, ResourceLocation texture2, String material, boolean additive, float sourceUnit) {
        int operation = operation(material);
        if (operation < 0) throw new IllegalArgumentException("Unsupported source particle material: " + material);
        if (!(sourceUnit > 0) || !Float.isFinite(sourceUnit)) throw new IllegalArgumentException("Invalid source model unit");
        return MeshyRenderTypes.fxCascade(texture, texture2 == null ? texture : texture2, operation, additive, sourceUnit);
    }

    public static RenderType type(ResourceLocation texture, ResourceLocation texture2, ResourceLocation texture3,
            ResourceLocation texture4, ResourceLocation texture5, String material, boolean additive, float sourceUnit) {
        int operation = operation(material);
        if (operation < 0) throw new IllegalArgumentException("Unsupported source particle material: " + material);
        if (!(sourceUnit > 0) || !Float.isFinite(sourceUnit)) throw new IllegalArgumentException("Invalid source model unit");
        return MeshyRenderTypes.fxCascade(texture, texture2 == null ? texture : texture2,
                texture3 == null ? texture : texture3, texture4 == null ? texture : texture4,
                texture5 == null ? texture : texture5, operation, additive, sourceUnit);
    }

    // SOURCE_DISTORTION_BEGIN
    public static boolean isDistortionOperation(int operation) {
        return operation == 4 || (operation >= 6 && operation <= 8) || operation == 100 || operation == 101;
    }
    // SOURCE_DISTORTION_END
    public static boolean isReady() {
        return additiveShader != null && alphaShader != null && distortionShader != null;
    }

    public static void prepare(int depthTexture, int targetWidth, int targetHeight, float timeSeconds) {
        if (depthTexture <= 0 || targetWidth <= 0 || targetHeight <= 0 || !Float.isFinite(timeSeconds))
            throw new IllegalArgumentException("Source particle materials require a valid scene depth snapshot");
        sceneDepth = depthTexture;
        width = targetWidth;
        height = targetHeight;
        time = timeSeconds;
    }

    /** Flush all source buffers before changing pass. Pass 1 accumulates (+x,+y,-x,-y). */
    public static void setPass(int value) {
        if (value != 0 && value != 1) throw new IllegalArgumentException("Unknown source material pass");
        pass = value;
    }

    static boolean isDistortionPass() {
        return pass == 1;
    }

    public static ShaderInstance get(boolean additive, int operation, float sourceUnit) {
        ShaderInstance shader = pass == 1 ? distortionShader : additive ? additiveShader : alphaShader;
        if (shader != null) {
            shader.setSampler("FxSceneDepth", sceneDepth);
            FxHeldDepth.uniforms(shader, sceneDepth);
            shader.safeGetUniform("FxViewport").set((float) width, (float) height);
            shader.safeGetUniform("FxTime").set(time);
            shader.safeGetUniform("MaterialOperation").set(operation);
            shader.safeGetUniform("MaterialPass").set(pass);
            shader.safeGetUniform("SourceUnit").set(sourceUnit);
        }
        return shader;
    }
}
