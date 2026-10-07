package com.example.taczmeshloader.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/** Only registered for our color-only particle passes; never used by scene/entity shaders. */
final class FxShaderInstance extends ShaderInstance {
    enum Blend { ALPHA, ADDITIVE, DISTORTION }
    private final Blend fxBlend;
    private final String fxIdentifier;
    private boolean reportedFirstDraw;
    private static final DepthColorBridge DEPTH_COLOR = DepthColorBridge.discover();

    FxShaderInstance(ResourceProvider resources, ResourceLocation name, VertexFormat format, Blend blend) throws IOException {
        super(resources, name, format);
        fxBlend = blend;
        fxIdentifier = name.toString();
    }

    @Override public void apply() {
        // Oculus 1.8 injects a color/depth lock at super.apply TAIL for unknown shaders.
        // Release only a lock created by this invocation; an outer owner's lock stays intact.
        boolean wasLocked = DEPTH_COLOR != null && DEPTH_COLOR.locked();
        super.apply();
        boolean firstDraw = !reportedFirstDraw;
        boolean lockedAfterSuper = firstDraw && DEPTH_COLOR != null && DEPTH_COLOR.locked();
        if (DEPTH_COLOR != null && !wasLocked && DEPTH_COLOR.unlockNewLock()) {
            // unlock restores the saved color mask verbatim. Our COLOR_WRITE pass keeps depth read-only.
            RenderSystem.depthMask(false);
        }
        // BlendMode caches its last JSON object, while RenderType setup changes GL blend state.
        // Reassert this FX instance's contract even when BlendMode.apply skipped its cached object.
        applyBlend(fxBlend);
        if (firstDraw) {
            reportedFirstDraw = true;
            boolean afterOwnedUnlock = DEPTH_COLOR != null && DEPTH_COLOR.locked();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                LogUtils.getLogger().info("[TML FX shader state] shader={} bridge={} wasLocked={} lockedAfterSuper={} afterOwnedUnlock={} colorMask=[{},{},{},{}] depthMask={} drawFbo={}",
                        fxIdentifier, DEPTH_COLOR != null, wasLocked, lockedAfterSuper, afterOwnedUnlock,
                        mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0,
                        GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
            }
        }
    }

    static void applyBlend(Blend blend) {
        RenderSystem.enableBlend();
        GlStateManager.SourceFactor source = blend == Blend.DISTORTION
                ? GlStateManager.SourceFactor.ONE : GlStateManager.SourceFactor.SRC_ALPHA;
        GlStateManager.DestFactor destination = blend == Blend.ALPHA
                ? GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA : GlStateManager.DestFactor.ONE;
        RenderSystem.blendFuncSeparate(source, destination, source, destination);
    }

    private static final class DepthColorBridge {
        private final MethodHandle isLocked, unlock;
        private boolean failed;
        private boolean reportedUnlock;
        private DepthColorBridge(MethodHandle isLocked, MethodHandle unlock) {
            this.isLocked = isLocked;
            this.unlock = unlock;
        }

        static DepthColorBridge discover() {
            try {
                Class<?> storage = Class.forName("net.irisshaders.iris.gl.blending.DepthColorStorage", false,
                        FxShaderInstance.class.getClassLoader());
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new DepthColorBridge(lookup.unreflect(storage.getMethod("isDepthColorLocked")),
                        lookup.unreflect(storage.getMethod("unlockDepthColor")));
            } catch (ClassNotFoundException absent) {
                return null; // Oculus/Iris is optional.
            } catch (ReflectiveOperationException | LinkageError | SecurityException unavailable) {
                LogUtils.getLogger().warn("[TML FX] Optional shader depth/color bridge unavailable: {}",
                        unavailable.getClass().getSimpleName());
                return null;
            }
        }

        boolean locked() {
            if (failed) return true; // Unknown ownership must never be unlocked.
            try {
                return (boolean) isLocked.invokeExact();
            } catch (Throwable unavailable) {
                fail(unavailable);
                return true;
            }
        }

        boolean unlockNewLock() {
            if (failed) return false;
            try {
                if (!(boolean) isLocked.invokeExact()) return false;
                unlock.invokeExact();
                if (!reportedUnlock) {
                    reportedUnlock = true;
                    LogUtils.getLogger().info("[TML FX] Restored Oculus depth/color mask captured by this FX shader apply; external locks remain owned by their caller.");
                }
                return true;
            } catch (Throwable unavailable) {
                fail(unavailable);
                return false;
            }
        }

        private void fail(Throwable error) {
            if (!failed) LogUtils.getLogger().warn("[TML FX] Optional shader depth/color bridge disabled: {}",
                    error.getClass().getSimpleName());
            failed = true;
        }
    }
}
