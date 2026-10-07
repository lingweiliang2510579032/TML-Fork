package com.example.taczmeshloader.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.IOException;

/** Unlit source material operations; HDR color and SubUV mixing happen before blending. */
@Mod.EventBusSubscriber(modid = "taczmeshloader", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FxSourceShader {
    public static final int RELEASE_OF_MAGIC = 4;
    private static ShaderInstance additiveShader, alphaShader;
    private FxSourceShader() {}

    @SubscribeEvent public static void register(RegisterShadersEvent event) throws IOException {
        event.registerShader(new FxShaderInstance(event.getResourceProvider(),
                new ResourceLocation("taczmeshloader", "fx_source"), DefaultVertexFormat.NEW_ENTITY,
                FxShaderInstance.Blend.ADDITIVE), s -> additiveShader = s);
        event.registerShader(new FxShaderInstance(event.getResourceProvider(),
                new ResourceLocation("taczmeshloader", "fx_source_alpha"), DefaultVertexFormat.NEW_ENTITY,
                FxShaderInstance.Blend.ALPHA), s -> alphaShader = s);
    }

    public static ShaderInstance get(boolean additive, int operation, float scale, float power, boolean srgb) {
        ShaderInstance shader = additive ? additiveShader : alphaShader;
        if (shader != null) {
            shader.safeGetUniform("MaterialOperation").set(operation);
            shader.safeGetUniform("TextureScale").set(scale);
            shader.safeGetUniform("TexturePower").set(power);
            shader.safeGetUniform("TextureSrgb").set(srgb ? 1 : 0);
        }
        return shader;
    }
}
