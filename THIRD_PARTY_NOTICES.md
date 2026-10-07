# 第三方说明

- **TacZMeshLoader**：VellEagle，GPL-3.0-only。原许可和贡献说明保留在 LICENSE.txt、CREDITS.txt。
- **SimpleBedrockModel 2.2.2**：库元数据标注 LGPL-3.0，作者包括 TartaricAcid、MaydayMemory、MoePus、Hidomatn、xjqsh。项目地址：https://github.com/MCModderAnchor/SimpleBedrockModel 。本仓库不再分发该依赖的 jar；dependencies.json 指向上游固定版本的二进制和源码归档，并提供校验值。上游明确示例资源有独立权利边界，不将它们宣称为本仓库 GPL 资产。若发布包含该库的二进制，应另行保留其许可并提供该版本对应源码。
- **Gradle Wrapper**：保留上游脚本中的 Apache-2.0 声明；官方项目：https://github.com/gradle/gradle 。
- **Minecraft / Forge、TaCZ、LRTactical、Accelerated Rendering、JEI、Mixin**：构建坐标与版本见 build.gradle；分别遵循各自上游条款。本仓库 GPL 声明不重新许可这些独立依赖。

本次仅公开源码，不发布包含第三方库的二进制。发布者需核对实际产物的嵌套依赖及对应源码，不以依赖名称清单代替完整分发要求。
