package com.example.taczmeshloader.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Optional bloom for one explicitly submitted, effect-only source in the current frame.
 * No source means no chain creation or processing. The main scene is only the composite
 * background/output; it is never used as the blur input.
 */
@OnlyIn(Dist.CLIENT)
public final class BloomPostProcessor {
    private BloomPostProcessor() {}
    private static final Logger LOG = LogManager.getLogger("MeshyLoader");
    private static final ResourceLocation CHAIN =
            new ResourceLocation("taczmeshloader", "shaders/post/bloom.json");
    private static final BloomSourceRequest<RenderTarget> REQUESTS = new BloomSourceRequest<>();
    private static final BloomSourceRequest.Target<RenderTarget> SOURCE_INFO = new BloomSourceRequest.Target<>();
    private static final BloomSourceRequest.Target<RenderTarget> MAIN_INFO = new BloomSourceRequest.Target<>();
    private static PostChain chain;
    private static RenderTarget chainSource;
    private static RenderTarget chainMain;
    private static int chainW = -1;
    private static int chainH = -1;
    private static boolean broken;
    private static PostPass passComposite;

    /** GameRenderer.render HEAD: invalidate requests even if this frame skips rendering. */
    public static void beginFrame() { REQUESTS.beginFrame(); }
    /** Zero outside the submission window. Tokens cannot be reused across frames. */
    public static long currentFrameId() { return REQUESTS.currentFrameId(); }

    /**
     * The producer must clear its independent, full-resolution target to black each frame,
     * draw only the effects requiring bloom, then submit before the pre-GUI consumer hook.
     * Keep the source alive and unchanged until consumption; no scene copy is permitted.
     * One aggregate source per frame: first valid submission wins, later submissions return
     * false. Intensity <= 0/nonfinite is a no-op; positive intensity is capped at 0.5.
     * This API does not enable or allocate an effect producer.
     */
    public static boolean submitSource(long frameId, RenderTarget source, float intensity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || broken || !FxDiagnostics.bloomEnabled()) return false;
        return REQUESTS.submit(frameId, describe(source, SOURCE_INFO),
                describe(mc.getMainRenderTarget(), MAIN_INFO), intensity);
    }

    private static BloomSourceRequest.Target<RenderTarget> describe(RenderTarget target,
            BloomSourceRequest.Target<RenderTarget> result) {
        return target == null ? result.set(null,0,0,0,0) : result.set(target, target.width,
                target.height, target.getColorTextureId(), target.frameBufferId);
    }

    /** Existing pre-GUI hook: consume once, even if disabled or the world has disappeared. */
    public static void render(float partialTick) {
        BloomSourceRequest.Request<RenderTarget> request = REQUESTS.consume();
        if (request == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || broken || !FxDiagnostics.bloomEnabled()) return;
        RenderTarget main = mc.getMainRenderTarget();
        RenderTarget source = request.source().target();
        // Reject resized/destroyed/rebound targets, not just the original object reference.
        describe(source, SOURCE_INFO);
        describe(main, MAIN_INFO);
        if (!request.source().matches(SOURCE_INFO) || !request.main().matches(MAIN_INFO)
                || !BloomSourceRequest.legalTargets(SOURCE_INFO, MAIN_INFO)) return;
        if (chain == null || chainW != main.width || chainH != main.height
                || chainSource != source || chainMain != main) {
            rebuild(mc, source, main);
            if (chain == null) return;
        }
        long renderStart = System.nanoTime();
        try {
            pushUniforms(request.intensity());
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            chain.process(partialTick);
        } catch (Throwable error) {
            broken = true;
            destroy();
            LOG.warn("requested bloom failed and is disabled: {}", error.toString());
        } finally {
            main.bindWrite(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            MeshPerf.recordEffect(MeshPerf.BLOOM, System.nanoTime() - renderStart);
        }
    }

    private static void rebuild(Minecraft mc, RenderTarget source, RenderTarget main) {
        destroy();
        PostChain candidate = null;
        try {
            int w = main.width, h = main.height;
            candidate = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, CHAIN);
            candidate.addTempTarget("bloomA", w, h);
            candidate.addTempTarget("bloomB", w, h);
            candidate.addTempTarget("bloomSwap", w, h);
            candidate.addTempTarget("bloomZero", 1, 1);
            RenderTarget a = candidate.getTempTarget("bloomA");
            RenderTarget b = candidate.getTempTarget("bloomB");
            RenderTarget swap = candidate.getTempTarget("bloomSwap");
            RenderTarget zero = candidate.getTempTarget("bloomZero");
            for (RenderTarget target : new RenderTarget[] { a, b, swap, zero }) {
                target.setFilterMode(GL11.GL_NEAREST);
            }
            zero.setClearColor(0f, 0f, 0f, 0f);
            zero.clear(Minecraft.ON_OSX);

            // Equal full resolution: normalized 3x3 tent, applied twice.
            // Effective kernel [1,4,6,4,1] x [1,4,6,4,1] / 256, radius 2 pixels.
            // Black AddTexture removes the old mip-level energy accumulation.
            PostPass blurA = candidate.addPass("taczmeshloader:bloom_up", source, a);
            PostPass blurB = candidate.addPass("taczmeshloader:bloom_up", a, b);
            passComposite = candidate.addPass("taczmeshloader:bloom_composite", b, swap);
            PostPass blit = candidate.addPass("taczmeshloader:blit", swap, main);
            blurA.addAuxAsset("AddTexture", zero::getColorTextureId, 1, 1);
            blurB.addAuxAsset("AddTexture", zero::getColorTextureId, 1, 1);
            passComposite.addAuxAsset("Background", main::getColorTextureId, w, h);
            ortho(blurA, a);
            ortho(blurB, b);
            ortho(passComposite, swap);
            ortho(blit, main);
            chain = candidate;
            chainSource = source;
            chainMain = main;
            chainW = w;
            chainH = h;
            LOG.info("requested effect-only bloom ready: {}x{}, radius={}px, maximum intensity={}",
                    w, h, BloomSourceRequest.RADIUS_PIXELS, BloomSourceRequest.MAX_INTENSITY);
        } catch (Throwable error) {
            if (candidate != null) {
                try { candidate.close(); } catch (Throwable ignored) {}
            }
            LOG.warn("requested bloom chain build failed, bloom disabled: {}", error.toString());
            broken = true;
            destroy();
        } finally {
            main.bindWrite(true);
        }
    }

    private static void ortho(PostPass pass, RenderTarget out) {
        pass.setOrthoMatrix(new Matrix4f().setOrtho(0f, out.width, 0f, out.height, 0.1f, 1000f));
    }

    private static void pushUniforms(float intensity) {
        passComposite.getEffect().safeGetUniform("BloomIntensive").set(intensity);
        passComposite.getEffect().safeGetUniform("BloomBase").set(1f);
        passComposite.getEffect().safeGetUniform("BloomThresholdUp").set(0f);
        passComposite.getEffect().safeGetUniform("BloomThresholdDown").set(0f);
    }

    private static void destroy() {
        if (chain != null) {
            try { chain.close(); } catch (Throwable ignored) {}
        }
        chain = null;
        chainSource = null;
        chainMain = null;
        passComposite = null;
        chainW = -1;
        chainH = -1;
    }
}
