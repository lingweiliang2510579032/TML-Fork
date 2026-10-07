package com.example.taczmeshloader.render;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.client.RenderedHand;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;

/** Opt-in first-person knife surface. Private color, borrowed read-only hand depth, no scene capture. */
public final class MeleeSurfaceRenderer {
    private static int fbo, color, width, height, vao;
    private static long readyFrame=-1, consumedFrame=-1, calls, presented, lastError;
    private static Object readyWorld, readyModel;
    private static MeleeSurfaceMaterial readyMaterial;
    private static ItemStack readyStack=ItemStack.EMPTY;
    private static String readySkin="", readySkinKey="MeshSkin";
    private static final org.apache.logging.log4j.Logger LOG=org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");
    private MeleeSurfaceRenderer() {}
    public static void invalidate() {
        readyFrame=-1;
        if(fbo!=0) GL30.glDeleteFramebuffers(fbo);
        if(color!=0) GL11.glDeleteTextures(color);
        if(vao!=0) GL30.glDeleteVertexArrays(vao);
        fbo=color=vao=0;
    }
    private static boolean owner(Minecraft mc,ItemStack stack) {
        return mc.level!=null && mc.player!=null && !mc.player.isDeadOrDying()
                && mc.getCameraEntity()==mc.player && mc.options.getCameraType().isFirstPerson()
                && stack!=null && !stack.isEmpty()
                && ItemStack.matches(stack,RenderedHand.stack());
    }
    public static void capture(PolyMeshModel model,PoseStack pose,MeleeSurfaceMaterial material,ItemStack stack,
                               String skinKey,int light) {
        Minecraft mc=Minecraft.getInstance();
        if(material==null || MeleeSurfaceShader.surface==null || MeleeSurfaceShader.composite==null || !owner(mc,stack)) return;
        // Never render surfaces from Oculus shadow passes or an arbitrary GUI framebuffer.
        if(ScreenRenderTracker.isRenderingScreen()) return;
        long renderFrame=BloomPostProcessor.currentFrameId();
        readyFrame=-1;
        try(State saved=new State()) {
            if(saved.drawFbo==0 || saved.viewport[0]!=0 || saved.viewport[1]!=0
                    || saved.viewport[2]!=mc.getWindow().getWidth() || saved.viewport[3]!=mc.getWindow().getHeight()
                    || GL11.glGetInteger(GL13.GL_SAMPLES)!=0) return;
            int depthType=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int depth=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int depthLevel=depthType==GL11.GL_TEXTURE?GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL):0;
            if(depth==0 || (depthType!=GL11.GL_TEXTURE && depthType!=GL30.GL_RENDERBUFFER)) return;
            ensureTarget(saved.viewport[2],saved.viewport[3]);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,fbo);
            // Same actual hand depth and current glDepthRange/projection; neither copied nor cleared.
            if(depthType==GL11.GL_TEXTURE) GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,depthLevel);
            else GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_RENDERBUFFER,depth);
            if(GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE) return;
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            RenderSystem.disableScissor(); RenderSystem.disableCull();
            GL11.glDisable(GL11.GL_STENCIL_TEST); GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            RenderSystem.colorMask(true,true,true,true);
            GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});
            RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL11.GL_LEQUAL); RenderSystem.depthMask(false);
            ShaderInstance shader=MeleeSurfaceShader.surface;
            for(int i=0;i<4;i++) RenderSystem.setShaderTexture(i,material.textures[i]);
            float brightness=Math.max((light & 0xffff)/240f,((light>>>16)&0xffff)/240f);
            brightness=Math.max(0,Math.min(1,brightness));
            float seconds=(mc.level.getGameTime()+mc.getFrameTime())/20f;
            MeleeSurfaceShader.prepare(material,seconds,brightness);
            RenderSystem.setShader(()->shader);
            model.renderSurfaceVbo(pose);
            shader.clear();
            int error=GL11.glGetError();
            if(error!=GL11.GL_NO_ERROR) { failure("capture GL "+error); return; }
            readyFrame=renderFrame; readyWorld=mc.level; readyModel=model; readyMaterial=material;
            readyStack=RenderedHand.stack(); readySkinKey=skinKey;
            readySkin=readyStack.hasTag()?readyStack.getTag().getString(skinKey):"";
            if(++calls%240==0) LOG.info("[MeleeSurface] captures={} presents={} size={}x{} depthType={} depth={} sourceFbo={}",calls,presented,width,height,depthType,depth,saved.drawFbo);
        } catch(RuntimeException error) { failure(error.toString()); }
        finally {
            // Do not retain another renderer's depth ownership beyond this synchronous capture.
            if(fbo!=0) {
                int old=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,fbo);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,0,0);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,old);
            }
        }
    }
    /** Existing after-hand hook, before particles: no surface frame means no work. */
    public static void present() {
        long frame=BloomPostProcessor.currentFrameId();
        if(readyFrame!=frame || consumedFrame==frame) return;
        consumedFrame=frame;
        Minecraft mc=Minecraft.getInstance();
        if(readyWorld!=mc.level || !owner(mc,readyStack) || RenderedHand.stack()!=readyStack
                || !readySkin.equals(readyStack.hasTag()?readyStack.getTag().getString(readySkinKey):"")) return;
        if(width!=mc.getWindow().getWidth() || height!=mc.getWindow().getHeight()) return;
        try(State saved=new State()) {
            if(saved.drawFbo==0) return;
            RenderSystem.disableDepthTest(); RenderSystem.depthMask(false); RenderSystem.disableCull(); RenderSystem.disableScissor();
            GL11.glDisable(GL11.GL_STENCIL_TEST); GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            RenderSystem.colorMask(true,true,true,true);
            GL11.glDrawBuffer(saved.drawBuffers[0]); // Keep unrelated Oculus MRT outputs untouched.
            RenderSystem.setShaderTexture(0,color);
            ShaderInstance shader=MeleeSurfaceShader.composite;
            RenderSystem.setShader(()->shader);
            shader.setSampler("Sampler0",color);
            shader.apply();
            if(vao==0) vao=GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
            shader.clear();
            int error=GL11.glGetError();
            if(error!=GL11.GL_NO_ERROR) failure("present GL "+error); else presented++;
        } catch(RuntimeException error) { failure(error.toString()); }
    }
    private static void ensureTarget(int w,int h) {
        if(fbo!=0 && w==width && h==height) return;
        invalidate(); width=w; height=h;
        color=GL11.glGenTextures(); GlStateManager._bindTexture(color);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA16F,w,h,0,GL11.GL_RGBA,GL11.GL_FLOAT,(ByteBuffer)null);
        fbo=GL30.glGenFramebuffers(); GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,fbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);
    }
    private static void failure(String reason) {
        long ns=System.nanoTime();
        if(ns-lastError>5_000_000_000L) { lastError=ns; LOG.warn("[MeleeSurface] skipped; original surface preserved: {}",reason); }
    }
    /** Snapshot every state modified by our own draw and ShaderInstance.apply/clear. */
    private static final class State implements AutoCloseable {
        final int drawFbo=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),readFbo=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int[] viewport=new int[4],drawBuffers=new int[GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS)];
        final int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),vao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        final int array=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),unpack=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        final int[] textures=new int[12],samplers=new int[12],logicalTextures=new int[12];
        final boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),stencil=GL11.glIsEnabled(GL11.GL_STENCIL_TEST),srgb=GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
        final boolean depthWrite=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),blend=GL11.glIsEnabled(GL11.GL_BLEND);
        final int depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC),srcRGB=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRGB=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),srcA=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstA=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),eqRGB=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),eqA=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        final boolean[] colorMask=new boolean[4];
        final boolean[][] masks=new boolean[drawBuffers.length][4];
        final boolean[] indexedBlend=new boolean[drawBuffers.length];
        final int[][] indexedBlendValues=new int[drawBuffers.length][6];
        final ShaderInstance shader=RenderSystem.getShader();
        State() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
            for(int i=0;i<drawBuffers.length;i++) drawBuffers[i]=GL11.glGetInteger(GL20.GL_DRAW_BUFFER0+i);
            try(MemoryStack stack=MemoryStack.stackPush()) {
                ByteBuffer mask=stack.malloc(4); GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,mask);
                for(int i=0;i<4;i++) colorMask[i]=mask.get(i)!=0;
                int[] names={GL14.GL_BLEND_SRC_RGB,GL14.GL_BLEND_DST_RGB,GL14.GL_BLEND_SRC_ALPHA,GL14.GL_BLEND_DST_ALPHA,GL20.GL_BLEND_EQUATION_RGB,GL20.GL_BLEND_EQUATION_ALPHA};
                for(int i=0;i<drawBuffers.length;i++) {
                    GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK,i,mask);
                    for(int k=0;k<4;k++) masks[i][k]=mask.get(k)!=0;
                    indexedBlend[i]=GL30.glIsEnabledi(GL11.GL_BLEND,i);
                    for(int k=0;k<6;k++) indexedBlendValues[i][k]=GL30.glGetIntegeri(names[k],i);
                }
            }
            for(int i=0;i<12;i++) {
                logicalTextures[i]=RenderSystem.getShaderTexture(i);
                GL13.glActiveTexture(GL13.GL_TEXTURE0+i);
                textures[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D); samplers[i]=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,i);
            }
            GL13.glActiveTexture(active);
        }
        public void close() {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,drawFbo); GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,readFbo);
            GL20.glDrawBuffers(drawBuffers); GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            if(depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(depthFunc); RenderSystem.depthMask(depthWrite);
            if(cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if(scissor) GlStateManager._enableScissorTest(); else GlStateManager._disableScissorTest();
            set(GL11.GL_STENCIL_TEST,stencil); set(GL30.GL_FRAMEBUFFER_SRGB,srgb);
            RenderSystem.colorMask(colorMask[0],colorMask[1],colorMask[2],colorMask[3]);
            if(blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            GlStateManager._blendFuncSeparate(srcRGB,dstRGB,srcA,dstA); GL20.glBlendEquationSeparate(eqRGB,eqA);
            for(int i=0;i<drawBuffers.length;i++) {
                GL30.glColorMaski(i,masks[i][0],masks[i][1],masks[i][2],masks[i][3]);
                if(indexedBlend[i]) GL30.glEnablei(GL11.GL_BLEND,i);else GL30.glDisablei(GL11.GL_BLEND,i);
                int[] v=indexedBlendValues[i];
                GL40.glBlendFuncSeparatei(i,v[0],v[1],v[2],v[3]); GL40.glBlendEquationSeparatei(i,v[4],v[5]);
            }
            for(int i=0;i<12;i++) {
                RenderSystem.setShaderTexture(i,logicalTextures[i]);
                GlStateManager._activeTexture(GL13.GL_TEXTURE0+i); GlStateManager._bindTexture(textures[i]); GL33.glBindSampler(i,samplers[i]);
            }
            GlStateManager._activeTexture(active);
            GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,array); GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,unpack);
            RenderSystem.setShader(()->shader); GlStateManager._glUseProgram(program);
        }
        private static void set(int cap,boolean value) { if(value) GL11.glEnable(cap);else GL11.glDisable(cap); }
    }
}
