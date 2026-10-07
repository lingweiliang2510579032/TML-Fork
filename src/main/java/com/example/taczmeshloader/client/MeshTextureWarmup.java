package com.example.taczmeshloader.client;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL11;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit pack texture lists are warmed behind the resource loading screen, never on a slot change. */
@Mod.EventBusSubscriber(modid = "taczmeshloader", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MeshTextureWarmup extends SimplePreparableReloadListener<Map<ResourceLocation, Boolean>> {
    @SubscribeEvent
    public static void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new MeshTextureWarmup());
    }

    @Override
    protected Map<ResourceLocation, Boolean> prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, Boolean> textures = new LinkedHashMap<>();
        resources.listResources("tml_texture_preload", id -> id.getPath().endsWith(".json"))
                .forEach((id, resource) -> {
                    try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                        for (var element : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("textures")) {
                            var entry = element.getAsJsonObject();
                            ResourceLocation texture = ResourceLocation.tryParse(entry.get("texture").getAsString());
                            if (texture == null || !texture.getPath().startsWith("textures/")
                                    || !texture.getPath().endsWith(".png")) continue;
                            boolean pbr = entry.has("pbr") && entry.get("pbr").getAsBoolean();
                            textures.merge(texture, pbr, (a, b) -> a || b);
                        }
                    } catch (Exception error) {
                        LogManager.getLogger("MeshyLoader").warn("[TextureWarmup] Invalid list {}", id);
                    }
                });
        return textures;
    }

    @Override
    protected void apply(Map<ResourceLocation, Boolean> textures, ResourceManager resources, ProfilerFiller profiler) {
        warmTextures(textures, resources, Minecraft.getInstance().getTextureManager());
    }

    static void warmTextures(Map<ResourceLocation, Boolean> textures, ResourceManager resources,
                             net.minecraft.client.renderer.texture.TextureManager manager) {
        long start = System.nanoTime();
        int loaded = 0, missing = 0;
        // SimpleTexture and the optional PBR loader must create GL objects on this reload/render executor.
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            for (var entry : textures.entrySet()) {
                if (resources.getResource(entry.getKey()).isEmpty()) { missing++; continue; }
                var texture = manager.getTexture(entry.getKey());
                if (entry.getValue()) warmPbr(texture.getId());
                loaded++;
            }
        } finally {
            GlStateManager._bindTexture(binding);
        }
        LogManager.getLogger("MeshyLoader").info("[TextureWarmup] loaded={} missing={} ms={}",
                loaded, missing, (System.nanoTime() - start) / 1e6);
    }

    private static void warmPbr(int texture) {
        // Optional Oculus API: retain its own SimpleTexture loader, formats and cache ownership.
        // No custom texture subclass: Oculus dispatches loaders by the texture's exact class.
        try {
            Class<?> type = Class.forName("net.irisshaders.iris.texture.pbr.PBRTextureManager");
            Object manager = type.getField("INSTANCE").get(null);
            type.getMethod("getOrLoadHolder", int.class).invoke(manager, texture);
        } catch (ClassNotFoundException ignored) {
            // Vanilla rendering has no companion PBR texture cache.
        } catch (ReflectiveOperationException | LinkageError error) {
            LogManager.getLogger("MeshyLoader").warn("[TextureWarmup] Optional PBR warmup unavailable: {}",
                    error.getClass().getSimpleName());
        }
    }
}
