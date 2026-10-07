package com.example.taczmeshloader.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lazy, effect-only scene capture. Call only when a live source effect needs scene depth.
 * Flush Minecraft/Oculus buffers before beginFrame and before compositeDistortion.
 * The copied depth is immutable: distortion and color particles must disable depth writes.
 * Unsupported framebuffer layouts fail closed; callers skip depth-dependent effects.
 */
@Mod.EventBusSubscriber(modid = "taczmeshloader", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FxSceneCapture {
    private static final Logger LOG = LogManager.getLogger("MeshyLoader");
    private static int sceneFbo, distortionFbo, sceneColor, sceneDepth, distortionColor;
    private static long lastTargetUse;
    private static int width, height, depthFormat, program, vao;
    private static int sceneUniform, distortionUniform, sizeUniform;
    private static int sourceDrawFbo, sourceReadFbo;
    private static final int[] sourceViewport = new int[4];
    private static final int[] textures = new int[2], samplers = new int[2];
    private static int[] drawBuffers;
    private static final int[] COMPOSITE_CAPABILITIES = { GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE,
            GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST };
    private static final boolean[] enabled = new boolean[COMPOSITE_CAPABILITIES.length];
    private static final float[] ZERO_COLOR = { 0f, 0f, 0f, 0f };
    private static boolean active, distortionActive, distortionStencil, described;
    private static boolean afterHandTarget, afterHandStencil, colorReady;
    private static int afterHandColorAttachment;
    private static final Map<String, Long> failures = new LinkedHashMap<>();
    // Oculus can redefine storage on the same texture name. Read the current layout.
    private static int capturedDepthObject, capturedDepthType, capturedDepthFormat;
    private static long captureCalls, captureNanos, metadataNanos, preparationNanos, blitNanos, captureMaxNanos;

    private FxSceneCapture() {}

    @SubscribeEvent public static void registerReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new PreparableReloadListener() {
            @Override public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager,
                    ProfilerFiller preparation, ProfilerFiller reload, Executor prepareExecutor, Executor applyExecutor) {
                return barrier.wait(Unit.INSTANCE).thenRunAsync(() -> {
                    if (RenderSystem.isOnRenderThread()) reloadProgram();
                    else RenderSystem.recordRenderCall(FxSceneCapture::reloadProgram);
                }, applyExecutor);
            }
        });
    }

    /** Captures the CURRENT draw framebuffer, including an Oculus-owned framebuffer. */
    public static boolean beginFrame(int requestedWidth, int requestedHeight) {
        RenderSystem.assertOnRenderThread();
        long started = System.nanoTime(), metadataFinished = started, preparationFinished = started, blitFinished = started;
        if (active) endFrame();
        sourceDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        sourceReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, sourceViewport);
        int textureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int renderbufferBinding = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int oldSourceReadBuffer = -1;
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            int priorError = GL11.glGetError();
            if (priorError != GL11.GL_NO_ERROR) return unsupported("pre-existing GL error " + priorError);
            if (sourceDrawFbo == 0 || requestedWidth <= 0 || requestedHeight <= 0
                    || sourceViewport[0] != 0 || sourceViewport[1] != 0
                    || sourceViewport[2] != requestedWidth || sourceViewport[3] != requestedHeight
                    || GL11.glGetInteger(GL13.GL_SAMPLES) != 0) {
                return unsupported("default, multisampled, or partial-viewport framebuffer");
            }
            int colorBuffer = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);
            if (colorBuffer < GL30.GL_COLOR_ATTACHMENT0 || colorBuffer > GL30.GL_COLOR_ATTACHMENT0 + 31)
                return unsupported("missing readable color attachment");
            int format = inspectDepth(requestedWidth, requestedHeight);
            if (format == 0) return unsupported("unsupported or mismatched depth attachment");
            metadataFinished = System.nanoTime();
            // Build/resize only while an actual particle needs these buffers.
            if (sceneFbo == 0 || distortionFbo == 0 || width != requestedWidth || height != requestedHeight || depthFormat != format) {
                destroyTargets();
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                createTargets(requestedWidth, requestedHeight, format);
            }
            lastTargetUse = System.nanoTime();
            ensureCompositeProgram();
            preparationFinished = System.nanoTime();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceDrawFbo);
            oldSourceReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            GL11.glReadBuffer(colorBuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneFbo);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            int error = GL11.glGetError();
            blitFinished = System.nanoTime();
            if (error != GL11.GL_NO_ERROR) {
                if (shouldDescribe("scene blit GL error " + error)) describeSource(colorBuffer, requestedWidth, requestedHeight, error);
                return unsupported("scene blit GL error " + error);
            }
            if (!described) {
                describeSource(colorBuffer, requestedWidth, requestedHeight, 0);
                described = true;
            }
            active = true;
            return true;
        } catch (IOException | RuntimeException error) {
            return unsupported(error.toString());
        } finally {
            if (oldSourceReadBuffer >= 0) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceDrawFbo);
                GL11.glReadBuffer(oldSourceReadBuffer);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sourceDrawFbo);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBinding);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbufferBinding);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            setEnabled(GL11.GL_SCISSOR_TEST, scissor);
            GL11.glViewport(sourceViewport[0], sourceViewport[1], sourceViewport[2], sourceViewport[3]);
            long elapsed = System.nanoTime() - started;
            captureCalls++;
            captureNanos += elapsed;
            captureMaxNanos = Math.max(captureMaxNanos, elapsed);
            if (metadataFinished > started) metadataNanos += metadataFinished - started;
            if (preparationFinished > metadataFinished) preparationNanos += preparationFinished - metadataFinished;
            if (blitFinished > preparationFinished) blitNanos += blitFinished - preparationFinished;
            if (captureCalls % 200 == 0) {
                LOG.info("[PerfFXScene] calls={} avgMs={} metadataMs={} prepareMs={} blitMs={} maxMs={}",
                        captureCalls, captureNanos / 200000000.0, metadataNanos / 200000000.0,
                        preparationNanos / 200000000.0, blitNanos / 200000000.0, captureMaxNanos / 1000000.0);
                captureNanos = metadataNanos = preparationNanos = blitNanos = captureMaxNanos = 0;
            }
        }
    }

    /** Zero means unavailable; never substitute an invented depth value. */
    public static int depthTexture() { return active ? sceneDepth : 0; }

    /**
     * Refresh color after the hand, retaining the immutable world depth captured before it.
     * Color particles blend into the private RGBA16F scene target with copied WORLD depth.
     * HDR emissive values must reach blending before the final normalized-target clamp.
     * This explicitly draws first-person effects over the hand, still occluded by world geometry.
     * No hand depth or third-party framebuffer attachment is modified.
     */
    public static boolean beginAfterHand() {
        if (!active || afterHandTarget) return false;
        sourceDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        sourceReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, sourceViewport);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int readBuffer = -1;
        boolean ready = false;
        try {
            if (GL11.glGetError() != GL11.GL_NO_ERROR)
                return unsupported("pre-existing after-hand GL error");
            if (sourceDrawFbo == 0 || sourceViewport[0] != 0 || sourceViewport[1] != 0
                    || sourceViewport[2] != width || sourceViewport[3] != height
                    || GL11.glGetInteger(GL13.GL_SAMPLES) != 0)
                return unsupported("after-hand framebuffer size/layout changed");
            int attachment = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);
            if (attachment < GL30.GL_COLOR_ATTACHMENT0 || attachment > GL30.GL_COLOR_ATTACHMENT0 + 31)
                return unsupported("after-hand color attachment unavailable");
            int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int object = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (object == 0 || (type != GL11.GL_TEXTURE && type != GL30.GL_RENDERBUFFER))
                return unsupported("unsupported after-hand color object");
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceDrawFbo);
            readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            GL11.glReadBuffer(attachment);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneFbo);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            if (GL11.glGetError() != GL11.GL_NO_ERROR)
                return unsupported("after-hand HDR color copy failed");
            afterHandColorAttachment = attachment;
            afterHandStencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST); // The world snapshot contains depth, not stencil.
            afterHandTarget = colorReady = ready = true;
            return true;
        } finally {
            if (readBuffer >= 0) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceDrawFbo);
                GL11.glReadBuffer(readBuffer);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, ready ? sceneFbo : sourceDrawFbo);
            setEnabled(GL11.GL_SCISSOR_TEST, scissor);
        }
    }

    /** Resume color geometry after distortion composite, using the same copied world depth. */
    public static void bindParticleTarget() {
        if (!active || !afterHandTarget) return;
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneFbo);
        GL11.glViewport(0, 0, width, height);
    }

    /** Bind cleared RGBA16F offsets and the copied scene depth. Caller uses additive ONE/ONE. */
    public static boolean beginDistortion() {
        if (!active || distortionActive) return false;
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, distortionFbo);
        GL11.glViewport(0, 0, width, height);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, 0, mask);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glColorMaski(0, true, true, true, true);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, ZERO_COLOR);
            GL30.glColorMaski(0, mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
        } finally {
            setEnabled(GL11.GL_SCISSOR_TEST, scissor);
        }
        distortionStencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST); // Copied depth is valid; stencil was deliberately not copied.
        distortionActive = true;
        return true;
    }

    /**
     * Flush pass-1 geometry first. RGBA is (+X,+Y,-X,-Y), measured in framebuffer pixels;
     * positive Y points up. Scalar source distortion is broadcast into X and Y by its material.
     * This framebuffer-pixel adapter is explicit; the original UE projection scale is unverified.
     */
    public static void compositeDistortion() {
        if (!active || !distortionActive) return;
        setEnabled(GL11.GL_STENCIL_TEST, distortionStencil);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sourceDrawFbo);
        GL11.glViewport(sourceViewport[0], sourceViewport[1], sourceViewport[2], sourceViewport[3]);
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for (int i = 0; i < 2; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            samplers[i] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, i);
        }
        if (drawBuffers == null) drawBuffers = new int[GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS)];
        for (int i = 0; i < drawBuffers.length; i++) drawBuffers[i] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + i);
        for (int i = 0; i < COMPOSITE_CAPABILITIES.length; i++) enabled[i] = GL11.glIsEnabled(COMPOSITE_CAPABILITIES[i]);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean blend = GL30.glIsEnabledi(GL11.GL_BLEND, 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, 0, mask);
            try {
                for (int capability : COMPOSITE_CAPABILITIES) GL11.glDisable(capability);
                GL11.glDepthMask(false);
                GL30.glColorMaski(0, true, true, true, true);
                GL30.glDisablei(GL11.GL_BLEND, 0);
                // Preserve other Oculus MRT attachments: only color output zero is written.
                GL11.glDrawBuffer(drawBuffers[0]);
                GL20.glUseProgram(program);
                GL30.glBindVertexArray(vao);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneColor);
                GL33.glBindSampler(0, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, distortionColor);
                GL33.glBindSampler(1, 0);
                GL20.glUniform1i(sceneUniform, 0);
                GL20.glUniform1i(distortionUniform, 1);
                GL20.glUniform2f(sizeUniform, width, height);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            } finally {
                GL20.glDrawBuffers(drawBuffers);
                GL30.glColorMaski(0, mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
                if (blend) GL30.glEnablei(GL11.GL_BLEND, 0); else GL30.glDisablei(GL11.GL_BLEND, 0);
                GL11.glDepthMask(depthWrite);
                for (int i = 0; i < COMPOSITE_CAPABILITIES.length; i++) setEnabled(COMPOSITE_CAPABILITIES[i], enabled[i]);
                for (int i = 0; i < 2; i++) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
                    GL33.glBindSampler(i, samplers[i]);
                }
                GL13.glActiveTexture(oldActiveTexture);
                GL30.glBindVertexArray(oldVao);
                GL20.glUseProgram(oldProgram);
                distortionActive = false;
            }
        }
        if (afterHandTarget) copyWarpedColorToScene();
    }

    /** Only distortion frames need this extra color copy. Never replace the saved world depth. */
    private static void copyWarpedColorToScene() {
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceDrawFbo);
        int oldReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        try {
            GL11.glReadBuffer(afterHandColorAttachment);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneFbo);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                colorReady = false;
                if (shouldDescribe("warped HDR color copy " + error))
                    LOG.warn("FX warped HDR color copy failed: {}", error);
                failures.merge("warped HDR color copy " + error, 1L, Long::sum);
            }
        } finally {
            GL11.glReadBuffer(oldReadBuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
            setEnabled(GL11.GL_SCISSOR_TEST, scissor);
        }
    }

    /** Commit only after a successful, flushed color pass. Cleanup/reload never presents partial work. */
    public static void presentColor() {
        if (!active || !afterHandTarget || !colorReady || distortionActive) return;
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sceneFbo);
        int oldReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sourceDrawFbo);
        if (drawBuffers == null) drawBuffers = new int[GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS)];
        for (int i = 0; i < drawBuffers.length; i++) drawBuffers[i] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + i);
        try {
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glDrawBuffer(afterHandColorAttachment); // Never overwrite unrelated MRT outputs.
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        } finally {
            GL20.glDrawBuffers(drawBuffers);
            GL11.glReadBuffer(oldReadBuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
            setEnabled(GL11.GL_SCISSOR_TEST, scissor);
        }
    }

    /** Safe in a finally block even when no distortion pass was submitted. */
    public static void endFrame() {
        if (!active) return;
        if (distortionActive) setEnabled(GL11.GL_STENCIL_TEST, distortionStencil);
        if (afterHandTarget) setEnabled(GL11.GL_STENCIL_TEST, afterHandStencil);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceReadFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sourceDrawFbo);
        GL11.glViewport(sourceViewport[0], sourceViewport[1], sourceViewport[2], sourceViewport[3]);
        active = distortionActive = afterHandTarget = colorReady = false;
    }

    /** The native texture/renderbuffer must have exactly the requested size and known depth format. */
    private static int inspectDepth(int w, int h) {
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int object = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        capturedDepthObject = object;
        capturedDepthType = type;
        capturedDepthFormat = 0;
        int level = type == GL11.GL_TEXTURE ? GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) : 0;
        if (object == 0) return 0;
        int format, actualW, actualH;
        if (type == GL11.GL_TEXTURE) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, object);
            if (GL11.glGetError() != GL11.GL_NO_ERROR) return 0; // Array/cube/multisample texture is unsupported.
            format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            actualW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
            actualH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
        } else if (type == GL30.GL_RENDERBUFFER) {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, object);
            format = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
            actualW = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH);
            actualH = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT);
        } else return 0;
        if (actualW != w || actualH != h) return 0;
        capturedDepthFormat = format;
        int supported = switch (format) {
            // Minecraft uses unsized DEPTH_COMPONENT; NVIDIA reports that enum verbatim.
            // Preserve it exactly: local GPU blit/readback validated this alongside the sized formats.
            case GL11.GL_DEPTH_COMPONENT, GL14.GL_DEPTH_COMPONENT16, GL14.GL_DEPTH_COMPONENT24, GL14.GL_DEPTH_COMPONENT32,
                    GL30.GL_DEPTH_COMPONENT32F, GL30.GL_DEPTH24_STENCIL8, GL30.GL_DEPTH32F_STENCIL8 -> format;
            default -> 0;
        };
        return supported;
    }

    /** Diagnostics only on first success and rate-limited failures; no per-frame color metadata queries. */
    private static void describeSource(int colorAttachment, int w, int h, int error) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sourceDrawFbo);
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, colorAttachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int object = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, colorAttachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int component = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, colorAttachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
        int format = 0;
        if (type == GL11.GL_TEXTURE) {
            int level = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, colorAttachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, object);
            if (GL11.glGetError() == GL11.GL_NO_ERROR)
                format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        } else if (type == GL30.GL_RENDERBUFFER) {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, object);
            format = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
        }
        LOG.info("[FXScene] sourceFbo={} viewport={}x{} colorAttachment={} colorObject={} colorType={} colorFormat={} componentType={} depthObject={} depthType={} depthFormat={} copiedDepthFormat={} targetColorFormat={} error={}",
                sourceDrawFbo, w, h, colorAttachment, object, type, format, component,
                capturedDepthObject, capturedDepthType, capturedDepthFormat, depthFormat, GL30.GL_RGBA16F, error);
    }

    private static void reloadProgram() {
        release();
        FxHeldDepth.release();
        try {
            ensureCompositeProgram(); // Tiny program only; full-resolution targets remain lazy.
        } catch (IOException | RuntimeException error) {
            unsupported("composite program reload " + error);
        }
    }

    private static void createTargets(int w, int h, int format) {
        width = w; height = h; depthFormat = format;
        sceneColor = texture(GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT, GL11.GL_LINEAR);
        distortionColor = texture(GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT, GL11.GL_NEAREST);
        boolean stencil = format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8;
        int dataType = format == GL30.GL_DEPTH24_STENCIL8 ? GL30.GL_UNSIGNED_INT_24_8
                : format == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL11.GL_FLOAT;
        sceneDepth = texture(format, stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT,
                dataType, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        sceneFbo = framebuffer(sceneColor, stencil);
        distortionFbo = framebuffer(distortionColor, stencil);
    }

    private static int texture(int internal, int format, int type, int filter) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, width, height, 0, format, type, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return id;
    }

    private static int framebuffer(int color, boolean stencil) {
        int id = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, id);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,
                stencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, sceneDepth, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(id);
            throw new IllegalStateException("incomplete source effect framebuffer");
        }
        return id;
    }

    private static void ensureCompositeProgram() throws IOException {
        if (program != 0) return;
        int vertex = 0, fragment = 0, candidate = 0;
        try {
            vertex = compile(GL20.GL_VERTEX_SHADER, "vsh");
            fragment = compile(GL20.GL_FRAGMENT_SHADER, "fsh");
            candidate = GL20.glCreateProgram();
            GL20.glAttachShader(candidate, vertex);
            GL20.glAttachShader(candidate, fragment);
            GL30.glBindFragDataLocation(candidate, 0, "fragColor");
            GL20.glLinkProgram(candidate);
            if (GL20.glGetProgrami(candidate, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException("FX distortion link: " + GL20.glGetProgramInfoLog(candidate));
            vao = GL30.glGenVertexArrays();
            sceneUniform = GL20.glGetUniformLocation(candidate, "SceneColor");
            distortionUniform = GL20.glGetUniformLocation(candidate, "Distortion");
            sizeUniform = GL20.glGetUniformLocation(candidate, "TargetSize");
            program = candidate;
            candidate = 0;
        } finally {
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
            if (candidate != 0) GL20.glDeleteProgram(candidate);
        }
    }

    private static int compile(int type, String extension) throws IOException {
        String source;
        ResourceLocation location = new ResourceLocation("taczmeshloader", "shaders/core/fx_distortion_composite." + extension);
        try (var input = Minecraft.getInstance().getResourceManager().getResourceOrThrow(location).open()) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("FX distortion " + extension + ": " + log);
        }
        return shader;
    }

    /** Resource reload or client shutdown; reusable targets otherwise stay cached between bursts. */
    public static void release() {
        if (!active && sceneFbo == 0 && distortionFbo == 0 && sceneColor == 0 && sceneDepth == 0
                && distortionColor == 0 && program == 0 && vao == 0) return;
        RenderSystem.assertOnRenderThread();
        endFrame();
        destroyTargets();
        if (program != 0) GL20.glDeleteProgram(program);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        program = vao = 0;
        failures.clear();
        described = false;
    }

    /** Cancel this frame immediately; keep storage briefly for another shot or weapon switch. */
    public static void clear() {
        if (!active && sceneFbo == 0 && distortionFbo == 0 && sceneColor == 0
                && sceneDepth == 0 && distortionColor == 0) return;
        RenderSystem.assertOnRenderThread();
        endFrame();
    }

    static void trimIdle(long now) {
        if (!active && now - lastTargetUse >= FxTargetCleanup.IDLE_NANOS) destroyTargets();
    }

    private static void destroyTargets() {
        if (sceneFbo != 0) GL30.glDeleteFramebuffers(sceneFbo);
        if (distortionFbo != 0) GL30.glDeleteFramebuffers(distortionFbo);
        if (sceneColor != 0) GL11.glDeleteTextures(sceneColor);
        if (sceneDepth != 0) GL11.glDeleteTextures(sceneDepth);
        if (distortionColor != 0) GL11.glDeleteTextures(distortionColor);
        sceneFbo = distortionFbo = sceneColor = sceneDepth = distortionColor = 0;
        width = height = depthFormat = 0;
        lastTargetUse = 0;
    }

    private static boolean unsupported(String reason) {
        long count = failures.getOrDefault(reason, 0L) + 1L;
        failures.put(reason, count);
        if (count == 1 || count % 200 == 0) LOG.warn("Source effect scene capture skipped: {} (occurrences={})", reason, count);
        active = distortionActive = false;
        return false;
    }

    private static boolean shouldDescribe(String reason) {
        long count = failures.getOrDefault(reason, 0L) + 1L;
        return count == 1 || count % 200 == 0;
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability);
    }
}
