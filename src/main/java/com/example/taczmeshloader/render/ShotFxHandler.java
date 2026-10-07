package com.example.taczmeshloader.render;

import com.example.taczmeshloader.tacz.TaczPolyMeshGunModel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * 开火特效（数据驱动）：腰射「枪口闪电迸发」+ 两种模式都有的「枪口→命中点闪电链」。
 *
 * <p>所有参数来自每把枪 geo 顶层 extras 的 {@code shot_fx}（GunConfig），代码零枪名零骨名硬编码；
 * 未配置的枪（默认）完全不进入任何分支。枪口位置取渲染瞬间捕获的骨矩阵
 * （{@code PolyMeshModel.getFxCapturedPose}），因此与画面上看到的枪口严格同点。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShotFxHandler {

    private ShotFxHandler() {}

    /** 闪电链：分若干 tick 逐批撒粒，形成"电流推进"观感。 */
    private static final class Chain {
        final Vec3 from, to;
        final double spacing, jitter;
        final ParticleOptions particle;
        int wavesLeft, totalWaves;

        Chain(Vec3 from, Vec3 to, double spacing, double jitter, ParticleOptions particle, int waves) {
            this.from = from;
            this.to = to;
            this.spacing = Math.max(0.05, spacing);
            this.jitter = jitter;
            this.particle = particle;
            this.wavesLeft = Math.max(1, waves);
            this.totalWaves = this.wavesLeft;
        }
    }

    private static final List<Chain> CHAINS = new ArrayList<>();

    /** 诊断用：前 N 次开火各分支打一条日志（定位"特效不出现"卡在哪一步）。 */
    private static final java.util.concurrent.atomic.AtomicInteger DBG_BUDGET =
            new java.util.concurrent.atomic.AtomicInteger(24);

    private static void dbg(String msg) {
        if (DBG_BUDGET.getAndDecrement() > 0) {
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader").info("[ShotFxDbg] {}", msg);
        }
    }

    /** 由 mixin（TaCZ MuzzleFlashRender.onShoot）在每个客户端开火瞬间调用。 */
    public static void onGunShot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            dbg("abort: 无玩家/世界");
            return;
        }
        TaczPolyMeshGunModel model = TaczPolyMeshGunModel.lastLocalFirstPersonModel();
        if (model == null) {
            dbg("abort: 没有登记第一人称 mesh 模型（lastLocalFirstPersonModel=null）");
            return;
        }
        // ★ 换枪保护：静态登记的是"最后渲染过带 shot_fx 的枪"，换到别的枪时它不会自动清空，
        //   不加这道校验就会"别枪也撒闪电"（用户 2026-09-13 反馈）。
        if (!net.minecraft.world.item.ItemStack.isSameItem(model.lastRenderStack(), mc.player.getMainHandItem())) {
            dbg("abort: 登记模型不是当前手持的枪（换枪后不该再撒）");
            return;
        }
        // ★ 第二道：按模型身份（geo）比对，挡"拿别的栈渲染了我们的模型"的场合（见 FxParticles#logOwnerOnce 注释）
        if (!com.example.taczmeshloader.client.GunSkinCatalog
                .modelBelongsTo(model.sourceGeo(), mc.player.getMainHandItem())) {
            dbg("abort: 模型归属不符 模型geo=" + model.sourceGeo());
            return;
        }
        if (!model.isShotFxEnabled()) {
            dbg("abort: shot_fx 未生效（enabled=" + model.rawShotFxEnabled()
                    + " bone='" + model.rawShotFxBone() + "' polyMesh=" + model.rawHasPolyMesh() + "）");
            return;
        }

        Vec3 muzzle = model.getShotFxMuzzleWorld(mc);
        boolean aiming = model.isShooterAiming(mc);
        if (muzzle == null) {
            // 兜底：骨矩阵没捕获到（还没查清原因）时，用"眼位 + 视线前方 + 右手侧"估个枪口，
            // 保证闪电至少出现在枪口附近，而不是完全不出现。
            Vec3 eye = mc.player.getEyePosition(1f);
            Vec3 look = mc.player.getViewVector(1f);
            Vec3 right = look.cross(new Vec3(0, 1, 0));
            if (right.lengthSqr() > 1e-6) right = right.normalize();
            muzzle = eye.add(look.scale(0.95)).add(right.scale(0.22)).add(0, -0.08, 0);
            dbg("fire[兜底枪口]: aiming=" + aiming + " muzzle=" + muzzle + " hipParticle=" + model.getShotFxHipBurstParticle() + " chainParticle=" + model.getShotFxChainParticle() + " count=" + model.getShotFxHipBurstCount());
        } else {
            dbg("fire[骨矩阵枪口]: aiming=" + aiming + " muzzle=" + muzzle);
        }
        ParticleOptions chainParticle = resolve(model.getShotFxChainParticle());

        // 腰射：枪口迸发（开镜不放）
        if (!aiming) {
            ParticleOptions burst = resolve(model.getShotFxHipBurstParticle());
            if (burst != null && muzzle != null) {
                RandomSource rnd = mc.level.random;
                for (int i = 0; i < model.getShotFxHipBurstCount(); i++) {
                    double s = model.getShotFxHipBurstSpeed();
                    mc.level.addParticle(burst,
                            muzzle.x, muzzle.y, muzzle.z,
                            (rnd.nextDouble() - 0.5) * 2 * s,
                            (rnd.nextDouble() - 0.5) * 2 * s,
                            (rnd.nextDouble() - 0.5) * 2 * s);
                }
            }
        }

        // 弹道闪电链（腰射与开镜都有）
        if (chainParticle != null && muzzle != null) {
            Vec3 hit = raycastEnd(mc, model.getShotFxChainRange());
            if (hit != null) {
                CHAINS.add(new Chain(muzzle, hit, model.getShotFxChainSpacing(),
                        model.getShotFxChainJitter(), chainParticle, model.getShotFxChainWaves()));
            }
        }
    }

    /** 沿视线找命中点（方块优先，其次实体）；什么都没有时取视线末端。 */
    private static Vec3 raycastEnd(Minecraft mc, double range) {
        HitResult blockHit = mc.player.pick(range, 0f, false);
        Vec3 start = mc.player.getEyePosition(1f);
        Vec3 end = blockHit.getType() == HitResult.Type.MISS
                ? start.add(mc.player.getViewVector(1f).scale(range))
                : blockHit.getLocation();
        if (blockHit instanceof BlockHitResult) {
            return blockHit.getLocation();
        }
        return end;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CHAINS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            CHAINS.clear();
            return;
        }
        java.util.Iterator<Chain> it = CHAINS.iterator();
        while (it.hasNext()) {
            Chain c = it.next();
            int wave = c.totalWaves - c.wavesLeft;                 // 0,1,2...
            double u0 = (double) wave / c.totalWaves;
            double u1 = (double) (wave + 1) / c.totalWaves;
            RandomSource rnd = mc.level.random;
            double len = c.from.distanceTo(c.to);
            // 垂直方向交错偏移 ⇒ 电链呈锯齿状（像闪电，而不是一条直线喷雾）
            Vec3 dir = c.to.subtract(c.from);
            double lenReal = Math.max(dir.length(), 1e-3);
            dir = dir.scale(1.0 / lenReal);
            Vec3 up = Math.abs(dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 perpA = dir.cross(up).normalize();
            Vec3 perpB = dir.cross(perpA).normalize();
            int seg = 0;
            for (double u = u0; u < u1; u += c.spacing / Math.max(len, 1e-3)) {
                Vec3 p = c.from.lerp(c.to, u);
                double sign = (seg++ % 2 == 0) ? 1.0 : -1.0;
                double j = c.jitter * sign;
                double j2 = (rnd.nextDouble() - 0.5) * c.jitter;
                p = p.add(perpA.scale(j)).add(perpB.scale(j2));
                mc.level.addParticle(c.particle, p.x, p.y, p.z, 0, 0, 0);
            }
            c.wavesLeft--;
            if (c.wavesLeft <= 0) it.remove();
        }
    }

    private static ParticleOptions resolve(String id) {
        if (id == null || id.isEmpty()) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.get(rl);
        return type instanceof ParticleOptions po ? po : null;
    }
}
