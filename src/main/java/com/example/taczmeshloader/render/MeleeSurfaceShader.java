package com.example.taczmeshloader.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.IOException;

@Mod.EventBusSubscriber(modid="taczmeshloader",value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class MeleeSurfaceShader {
    static ShaderInstance surface, composite;
    @SubscribeEvent public static void register(RegisterShadersEvent event) throws IOException {
        event.registerShader(new FxShaderInstance(event.getResourceProvider(),new ResourceLocation("taczmeshloader","melee_surface"),
                DefaultVertexFormat.NEW_ENTITY,FxShaderInstance.Blend.ALPHA),s->surface=s);
        event.registerShader(new FxShaderInstance(event.getResourceProvider(),new ResourceLocation("taczmeshloader","melee_surface_composite"),
                DefaultVertexFormat.POSITION,FxShaderInstance.Blend.ALPHA),s->composite=s);
        MeleeSurfaceRenderer.invalidate();
    }
    static void prepare(MeleeSurfaceMaterial m,float time,float light) {
        surface.safeGetUniform("ColorB").set(m.colorB[0],m.colorB[1],m.colorB[2]);
        surface.safeGetUniform("ColorR").set(m.colorR[0],m.colorR[1],m.colorR[2]);
        surface.safeGetUniform("ColorFlash").set(m.colorFlash[0],m.colorFlash[1],m.colorFlash[2]);
        surface.safeGetUniform("Emission").set(m.global,m.interval,m.pulse,time);
        surface.safeGetUniform("Specular").set(m.specular);
        surface.safeGetUniform("ToneMap").set(m.toneMap);
        surface.safeGetUniform("Pbr").set(m.roughMultiply,m.roughPower,m.metallic,m.exposure);
        surface.safeGetUniform("Lighting").set(m.ambientMin+(m.ambientMax-m.ambientMin)*light,
                m.directMin+(m.directMax-m.directMin)*light);
    }
}
