package com.example.taczmeshloader.render;

/**
 * 极简"分档计时"（诊断用，正式版常驻）：累计每帧各 pass 的纳秒，每 200 帧打一行 {@code [Perf]} 日志，
 * 同时给出游戏 FPS 与这段时间内的 GC 次数/耗时（GC 暴涨 = 每帧在造垃圾，不是画得慢）。
 *
 * <p>为什么要有它：玩家反馈"流动材质掉帧"时，光看代码只能猜（哪一段、是 CPU 还是 GPU 还是 GC）。
 * 有了这行数字就能直接定位，不必来回猜。开销 = 每次渲染几次 {@code System.nanoTime()} 与 long 累加。</p>
 *
 * <p>只统计**第一人称**（手持）那一遍渲染；第三人称/掉落/GUI 的那几遍不混进来。</p>
 */
public final class MeshPerf {

    private MeshPerf() {}

    /** 分档：本体（= 总耗时减去后面三档）、逐骨贴图、流动纹理叠加、自发光、总。 */
    public static final int BODY = 0, BONE = 1, STAR = 2, EMIS = 3, TOTAL = 4;
    public static final int FX = 0, BLOOM = 1;

    private static final long[] ACC = new long[5];
    private static final long[] EFFECT_ACC = new long[2];
    private static final long[] EFFECT_MAX = new long[2];
    private static final int[] EFFECT_FRAMES = new int[2];
    private static int frames = 0;
    private static long gcN = 0, gcMs = 0;
    /** 打日志的次数上限（免得刷屏）；想多看就调大。 */
    private static int logged = 0;
    private static final int LOG_LIMIT = 200;

    private static boolean inFp = false;

    /** 由 GunModel.render() 每帧设置：只有第一人称那一遍才计数（否则第三人称/GUI 会混进来，算出的"本体"变负）。 */
    public static void setFirstPerson(boolean v) {
        inFp = v;
    }

    public static void add(int slot, long ns) {
        if (inFp && ns > 0) ACC[slot] += ns;
    }

    /** 独立统计粒子与泛光；这两段不在枪模 render() 的计时范围内。 */
    public static void recordEffect(int slot, long ns) {
        if (ns <= 0 || slot < FX || slot > BLOOM) return;
        EFFECT_ACC[slot] += ns;
        EFFECT_MAX[slot] = Math.max(EFFECT_MAX[slot], ns);
        if (++EFFECT_FRAMES[slot] < 200) return;
        org.apache.logging.log4j.LogManager.getLogger("MeshyLoader").info(
                "[PerfFX] {} 近 200 帧均 {}ms / 最大 {}ms",
                slot == FX ? "粒子" : "泛光",
                String.format(java.util.Locale.ROOT, "%.3f", EFFECT_ACC[slot] / 200e6),
                String.format(java.util.Locale.ROOT, "%.3f", EFFECT_MAX[slot] / 1e6));
        EFFECT_ACC[slot] = EFFECT_MAX[slot] = 0;
        EFFECT_FRAMES[slot] = 0;
    }

    /** 第一人称每渲染一次手持枪 = 一帧。{@code drawNs} = 本体与各叠加层的总耗时。 */
    public static void frameDone(long drawNs, int fps, boolean firstPerson, String skinId) {
        if (!inFp) return;
        add(TOTAL, drawNs);
        if (++frames < 200) return;
        double n = frames;
        long[] gc = gcTotals();
        long body = ACC[TOTAL] - ACC[BONE] - ACC[STAR] - ACC[EMIS];
        String msg = String.format(java.util.Locale.ROOT,
                "[Perf] FPS=%d ｜ 持枪渲染均 %.2fms（本体 %.2f / 逐骨贴图 %.2f / 流动纹理 %.2f / 自发光 %.2f）"
                        + " ｜ 近 %d 帧 GC +%d 次 +%dms ｜ 皮肤=%s",
                fps, ACC[TOTAL] / n / 1e6, body / n / 1e6, ACC[BONE] / n / 1e6,
                ACC[STAR] / n / 1e6, ACC[EMIS] / n / 1e6, frames, gc[0] - gcN, gc[1] - gcMs, skinId);
        if (logged++ < LOG_LIMIT) {
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader").info(msg);
        }
        java.util.Arrays.fill(ACC, 0L);
        frames = 0;
        gcN = gc[0];
        gcMs = gc[1];
    }

    private static long[] gcTotals() {
        long c = 0, t = 0;
        try {
            for (java.lang.management.GarbageCollectorMXBean b
                    : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                if (b.getCollectionCount() > 0) c += b.getCollectionCount();
                if (b.getCollectionTime() > 0) t += b.getCollectionTime();
            }
        } catch (Throwable ignored) {
        }
        return new long[]{c, t};
    }
}
