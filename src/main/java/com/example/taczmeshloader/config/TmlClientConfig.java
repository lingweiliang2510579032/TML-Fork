package com.example.taczmeshloader.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * TML 客户端配置：生成 {@code config/taczmeshloader-client.toml}（改完重启生效）。
 *
 * <p>目前只有一项：**流动纹理叠加层开关**（皮肤里"星空流动"那层）。它要把整把枪每帧再叠画一遍，
 * 在弱显存机器（如 GTX 1650 4G）上是可观开支；关掉后皮肤变成"静态星空"，帧数回到与原皮相当。</p>
 *
 * <p>通用项，不含任何资源配置专属内容。</p>
 */
public final class TmlClientConfig {

    private TmlClientConfig() {}

    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue STARFLOW_ENABLED;
    private static final ForgeConfigSpec.BooleanValue AIM_CYCLE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("流动纹理叠加层（皮肤里的“星空流动”）开关。",
                        "关掉可以省掉“整把枪每帧再叠画一遍”的开销，弱显存机器建议 false。",
                        "Star-flow overlay (the animated starfield on some skins).",
                        "Set false to skip one extra full-model pass per frame on low-end GPUs.")
                .push("starflow");
        STARFLOW_ENABLED = b.define("enabled", true);
        b.pop();

        b.comment("【同一个键：开镜 → 切倍率 → 关镜】（默认关）",
                        "只在 TaCZ 客户端配置里「长按瞄准」= false（切换式开镜）时生效；长按用户完全不受影响。",
                        "One-key cycle: aim -> next magnification -> close, all on the aim key.",
                        "Only active when TaCZ's hold_to_aim is false (toggle aiming).")
                .push("aim");
        AIM_CYCLE = b.define("cycle_zoom_on_aim_key", false);
        b.pop();

        SPEC = b.build();
    }


    /**
     * 流动纹理叠加层是否启用。配置尚未加载（或取值失败）时返回 true = 保持默认行为，
     * 避免"配置没读到就把特效弄没了"。
     */
    /** 是否启用「开镜键循环：开镜 → 切倍率 → 关镜」（需 TaCZ 长按瞄准 = false）。 */
    public static boolean aimCycleZoomEnabled() {
        try {
            return AIM_CYCLE.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean starflowEnabled() {
        try {
            return STARFLOW_ENABLED.get();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
