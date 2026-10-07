# TML-Fork

Minecraft 1.20.1 / Forge 的网格、动画和特效扩展，基于 [VellEagle/TacZMeshLoader](https://github.com/VellEagle/TacZMeshLoader) 的 1.20.1 分支。原作者为 VellEagle；这是独立维护的修改版本，不代表上游项目。

## 许可

TML 代码采用 **GPL-3.0-only**，完整条款见 [LICENSE.txt](LICENSE.txt)。保留原有版权与贡献者声明，修改说明见 [MODIFICATIONS.md](MODIFICATIONS.md)。不附加禁止商用或禁止再分发条款。第三方依赖保留各自许可，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 功能

- 网格模型、骨骼变换、附件、材质与动画扩展。
- 数据驱动的粒子、网格特效、生命周期及材质混合。
- 场景深度、屏幕扭曲、HDR 中间合成与按需泛光接口。
- 渲染缓存、顶点预算和可选性能诊断。
- 基于资源配置的皮肤与界面扩展。

## 构建

需要 JDK 17；Gradle Wrapper 为 8.8，Forge 构建版本为 47.4.10。首次构建需要网络。

Windows PowerShell：

```powershell
./Get-Dependencies.ps1
./gradlew.bat reobfJar
```

其他平台：按 `dependencies.json` 下载依赖到 `libs/` 并校验 SHA256，然后执行 `sh ./gradlew reobfJar`。

构建输出在 `build/libs/`；应使用经过 `reobfJar` 处理的产物。运行时按模组元数据安装 Forge、TaCZ 1.1.8 兼容版本及相关依赖，不保证兼容更旧的 API。

特效材质使用中性配置键，例如 `spark_center`、`red_smoke`、`spark_streak`、`directional_spark`、`magnetic_field`、`radial_glow`、`boost_out`、`boost_core`、`boost_center`。不承诺非公开配置别名兼容性。

## 版本与源码对应

本次公开的是 2026-10-07 整理的开发源码快照，未发布游戏内验收过的二进制版本。构建成功不代表全部运行场景已经验证，也不承诺特效没有性能成本。

今后每次二进制发布应固定源码 commit/tag，同时提供对应源码、依赖版本及构建说明。不能将当前分支直接当作任意历史二进制的对应源码。
