package com.example.taczmeshloader.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.Function;

import static net.minecraft.Util.memoize;

/** Opt-in lit triangle batches; legacy mesh and FX render types stay unchanged. */
@OnlyIn(Dist.CLIENT)
public final class SmoothMeshRenderTypes extends RenderType {
    private SmoothMeshRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode,
                                  int size, boolean crumbling, boolean sort,
                                  Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sort, setup, clear);
    }

    private static final Function<ResourceLocation, RenderType> CUTOUT = memoize(texture -> create(
            "meshy_smooth_cutout", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES,
            256, true, false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(true)));

    // Vanilla's sort-on-upload assumes groups of four. Triangle batches must not use it.
    private static final Function<ResourceLocation, RenderType> TRANSLUCENT = memoize(texture -> create(
            "meshy_smooth_translucent", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES,
            256, true, false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(true)));

    public static RenderType cutout(ResourceLocation texture) { return CUTOUT.apply(texture); }
    public static RenderType translucent(ResourceLocation texture) { return TRANSLUCENT.apply(texture); }

    /** Call only for models with smooth meshes when the caller has not flushed all batches. */
    public static void endBatch(MultiBufferSource.BufferSource buffers, ResourceLocation texture) {
        buffers.endBatch(cutout(texture));
        buffers.endBatch(translucent(texture));
    }
}
