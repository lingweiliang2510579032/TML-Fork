package com.example.taczmeshloader.util;

/** 限频诊断日志（1 秒一条）：瞄具"进镜合成链"排查专用，避免刷屏。 */
public final class ScopeDbg {
    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");
    private static final java.util.Map<String, Long> LAST = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger BUDGET =
            new java.util.concurrent.atomic.AtomicInteger(120);

    private ScopeDbg() {}

    public static void log(String tag, String msg) {
        if (BUDGET.getAndDecrement() <= 0) return;
        long now = System.currentTimeMillis();
        Long last = LAST.get(tag);
        if (last != null && now - last < 1000L) return;
        LAST.put(tag, now);
        LOG.info("[ScopeDbg] {} {}", tag, msg);
    }
}
