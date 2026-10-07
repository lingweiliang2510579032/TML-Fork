package com.example.taczmeshloader.render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Optional Oculus bridge: its regular depth already contains the compressed hand pass. */
public final class FxPreHandDepth {
    private static FxParticleSource requestedSource;
    private static long requestedFrame = -1;
    private static boolean requested;
    private static Method managerMethod, pipelineMethod, textureMethod, idMethod, widthMethod, heightMethod;
    private static Field targetsField;
    private static boolean discovered, available;
    private static int readFbo, drawFbo;
    private static long copies, failures;
    private FxPreHandDepth() {}

    /** Only this pack's explicitly enabled normal-pose projection requests the bridge. */
    public static void request(FxParticleSource source, boolean enabled) {
        requestedSource = source;
        requestedFrame = BloomPostProcessor.currentFrameId();
        requested = enabled;
    }

    private static void discover() throws ReflectiveOperationException {
        ClassLoader loader = FxPreHandDepth.class.getClassLoader();
        Class<?> iris;
        try { iris = Class.forName("net.irisshaders.iris.Iris", false, loader); }
        catch (ClassNotFoundException absent) { discovered = true; return; }
        managerMethod = iris.getMethod("getPipelineManager");
        pipelineMethod = Class.forName("net.irisshaders.iris.pipeline.PipelineManager", false, loader).getMethod("getPipelineNullable");
        Class<?> pipeline = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline", false, loader);
        targetsField = pipeline.getDeclaredField("renderTargets");
        targetsField.setAccessible(true);
        Class<?> targets = Class.forName("net.irisshaders.iris.targets.RenderTargets", false, loader);
        textureMethod = targets.getMethod("getDepthTextureNoHand");
        widthMethod = targets.getMethod("getCurrentWidth");
        heightMethod = targets.getMethod("getCurrentHeight");
        idMethod = Class.forName("net.irisshaders.iris.targets.DepthTexture", false, loader).getMethod("getTextureId");
        available = discovered = true;
    }

    /** Copy depth only into our private FX texture; never write an Oculus attachment. */
    public static boolean repair(FxParticleSource source, int destination, int width, int height) {
        if (!requested || requestedSource != source || requestedFrame != BloomPostProcessor.currentFrameId()) return true;
        try {
            if (!discovered) discover();
            if (!available) return true;
            Object pipeline = pipelineMethod.invoke(managerMethod.invoke(null));
            // Vanilla pipeline / shaders disabled: the existing world snapshot is already correct.
            if (pipeline == null || !targetsField.getDeclaringClass().isInstance(pipeline)) return true;
            Object targets = targetsField.get(pipeline);
            if ((Integer)widthMethod.invoke(targets) != width || (Integer)heightMethod.invoke(targets) != height)
                return failure("pre-hand dimensions differ");
            int texture = (Integer)idMethod.invoke(textureMethod.invoke(targets));
            if (texture <= 0 || destination <= 0 || texture == destination) return failure("invalid depth texture");
            int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != width
                        || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) != height)
                    return failure("pre-hand texture size differs");
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, destination);
                if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) != format)
                    return failure("pre-hand depth format differs");
                if (readFbo == 0) readFbo = GL30.glGenFramebuffers();
                if (drawFbo == 0) drawFbo = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, readFbo);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
                GL11.glReadBuffer(GL11.GL_NONE); GL11.glDrawBuffer(GL11.GL_NONE);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                    return failure("pre-hand read framebuffer incomplete");
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, drawFbo);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, destination, 0);
                GL11.glReadBuffer(GL11.GL_NONE); GL11.glDrawBuffer(GL11.GL_NONE);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                    return failure("FX depth framebuffer incomplete");
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
                int error = GL11.glGetError();
                if (error != GL11.GL_NO_ERROR) return failure("depth copy GL " + error);
                if (++copies == 1) com.mojang.logging.LogUtils.getLogger().info("[HeldWorldDepth] using Oculus noHand depth; hand/knife depth excluded from world FX test");
                return true;
            } finally {
                // Detach borrowed resources immediately; keep only two empty private FBO names.
                if (readFbo != 0) {GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo); GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);}
                if (drawFbo != 0) {GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo); GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);}
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, boundTexture);
                if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        } catch (ReflectiveOperationException | RuntimeException error) { return failure(error.toString()); }
    }

    private static boolean failure(String reason) {
        if (++failures <= 3) com.mojang.logging.LogUtils.getLogger().warn("[HeldWorldDepth] skip incompatible normal held FX: {}", reason);
        return false;
    }
}
