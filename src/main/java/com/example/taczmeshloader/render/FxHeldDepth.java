package com.example.taczmeshloader.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;

/** Read-only snapshot of actual hand depth, used only by held particles outside inspect. */
public final class FxHeldDepth {
    private static int fbo, texture, width, height, format;
    private static long readyFrame = -1, lastTargetUse;
    private static Object world, owner, emitters;
    private static FxParticleSource source;
    private static float capturedWeight, drawWeight;
    private static final double[] depthRange = new double[2];
    private static final int[] viewport = new int[4];
    private static final Matrix4f handFromWorld = new Matrix4f(), fxToHand = new Matrix4f(), inverseWorld = new Matrix4f();
    private static final Quaternionf inverseCamera = new Quaternionf();
    private static boolean savedSampler;
    private static int oldTexture6, oldSampler6, oldLogical6;
    private FxHeldDepth() {}

    public static void invalidate() {
        readyFrame = -1; drawWeight = 0; capturedWeight = 0;
        world = owner = emitters = null; source = null;
    }

    public static void release() {
        invalidate();
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
        if (texture != 0) GL11.glDeleteTextures(texture);
        fbo = texture = width = height = format = 0;
        lastTargetUse = 0;
    }

    static void trimIdle(long now) {
        if (now - lastTargetUse >= FxTargetCleanup.IDLE_NANOS) release();
    }

    /** Called synchronously after the first-person knife and arms have been flushed. */
    public static void capture(FxParticleSource input, float weight, float fovScale, boolean directVbo) {
        readyFrame = -1;
        if (!FxDiagnostics.particlesEnabled() || weight <= 0 || !Float.isFinite(fovScale) || fovScale <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (!input.validLocalOwner(mc) || ScreenRenderTracker.isRenderingScreen()) return;
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        int oldUnpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            if (oldDraw == 0 || GL11.glGetInteger(GL13.GL_SAMPLES) != 0) return;
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            if (viewport[0] != 0 || viewport[1] != 0 || viewport[2] != mc.getWindow().getWidth()
                    || viewport[3] != mc.getWindow().getHeight()) return;
            int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int object = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (object == 0) return;
            int internal, w, h;
            if (type == GL11.GL_TEXTURE) {
                int level = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, object);
                internal = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
                h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
            } else if (type == GL30.GL_RENDERBUFFER) {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, object);
                internal = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
                w = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH);
                h = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT);
            } else return;
            if (w != viewport[2] || h != viewport[3] || !supported(internal)) return;
            GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, depthRange);
            if (!(depthRange[1] > depthRange[0])) return;
            // Direct VBO uses the captured pose as ModelViewMat itself. Consumer vertices
            // already contain pose but are additionally multiplied by the global ModelViewMat.
            handFromWorld.set(RenderSystem.getProjectionMatrix());
            if (!directVbo) handFromWorld.mul(RenderSystem.getModelViewMatrix());
            handFromWorld.scale(-1f, 1f, -1f/fovScale)
                    .rotate(inverseCamera.set(mc.gameRenderer.getMainCamera().rotation()).conjugate());
            if (!handFromWorld.isFinite()) return;
            ensureTarget(w, h, internal);
            lastTargetUse = System.nanoTime();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldDraw);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) return;
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            if (GL11.glGetError() != GL11.GL_NO_ERROR) return;
            source = input; world = mc.level; owner = input.ownershipToken(); emitters = input.activeFxEmitters();
            capturedWeight = Math.max(0, Math.min(1, weight));
            readyFrame = BloomPostProcessor.currentFrameId();
        } catch (RuntimeException unsupported) {
            readyFrame = -1;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, oldRenderbuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, oldUnpack);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    private static boolean supported(int f) {
        return f == GL14.GL_DEPTH_COMPONENT16 || f == GL14.GL_DEPTH_COMPONENT24 || f == GL14.GL_DEPTH_COMPONENT32
                || f == GL30.GL_DEPTH_COMPONENT32F || f == GL30.GL_DEPTH24_STENCIL8 || f == GL30.GL_DEPTH32F_STENCIL8;
    }
    private static void ensureTarget(int w, int h, int f) {
        if (fbo != 0 && width == w && height == h && format == f) return;
        release(); width = w; height = h; format = f;
        boolean stencil = f == GL30.GL_DEPTH24_STENCIL8 || f == GL30.GL_DEPTH32F_STENCIL8;
        texture = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, f, w, h, 0,
                stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT,
                f == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV
                        : stencil ? GL30.GL_UNSIGNED_INT_24_8 : GL11.GL_FLOAT, (ByteBuffer)null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        fbo = GL30.glGenFramebuffers(); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, stencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE); GL11.glReadBuffer(GL11.GL_NONE);
    }

    public static void prepare(FxParticleSource current, Matrix4f levelModelView, Matrix4f levelPose) {
        drawWeight = 0;
        Minecraft mc = Minecraft.getInstance();
        // Sampler #6 is new to the Cascade shader: preserve the caller's binding explicitly.
        if (!savedSampler) {
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE6);
            oldTexture6 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            oldSampler6 = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 6);
            oldLogical6 = RenderSystem.getShaderTexture(6);
            GL13.glActiveTexture(active); savedSampler = true;
            GL33.glBindSampler(6, 0);
        }
        if (readyFrame != BloomPostProcessor.currentFrameId() || current != source || world != mc.level
                || owner != current.ownershipToken() || emitters != current.activeFxEmitters()
                || width != mc.getWindow().getWidth() || height != mc.getWindow().getHeight()) return;
        inverseWorld.set(levelModelView).mul(levelPose).invert();
        fxToHand.set(handFromWorld).mul(inverseWorld);
        if (fxToHand.isFinite()) drawWeight = capturedWeight;
    }

    static void uniforms(ShaderInstance shader, int fallbackDepth) {
        shader.setSampler("FxHeldDepth", drawWeight > 0 ? texture : fallbackDepth);
        shader.safeGetUniform("FxHeldOcclusion").set(drawWeight);
        shader.safeGetUniform("FxToHandClip").set(fxToHand);
        shader.safeGetUniform("FxHandDepthRange").set((float)depthRange[0], (float)depthRange[1]);
    }
    public static void endDraw() {
        drawWeight = 0;
        if (!savedSampler) return;
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL13.GL_TEXTURE6);
        GlStateManager._bindTexture(oldTexture6);
        GL33.glBindSampler(6, oldSampler6);
        RenderSystem.setShaderTexture(6, oldLogical6);
        GlStateManager._activeTexture(active);
        savedSampler = false;
    }
}
